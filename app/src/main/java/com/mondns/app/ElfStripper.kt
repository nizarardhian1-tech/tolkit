package com.mondns.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strip .symtab / .strtab / .debug_* (dan .comment) dari file .so (ELF) — murni Kotlin,
 * TANPA dependency native (llvm-strip/binutils/NDK toolchain) apa pun. Konsisten
 * dengan filosofi [ElfParser]: cuma baca/tulis section header table + data section
 * yang benar-benar perlu, TIDAK PERNAH memuat seluruh file (bisa ratusan MB untuk
 * lib IL2CPP/Unity) sekaligus ke memori — porsi data section besar disalin lewat
 * FileChannel.transferTo (streaming), bukan ByteArray.
 *
 * KEAMANAN HASIL (biar nggak korup / nggak nyentuh apa pun yang berpengaruh ke
 * eksekusi kode saat runtime):
 *  - Section yang boleh dihapus HANYA yang TIDAK bertanda SHF_ALLOC — artinya
 *    section itu sudah pasti bukan bagian dari image yang dimuat ke memori
 *    (bukan bagian PT_LOAD manapun), jadi menghapusnya tidak mengubah perilaku
 *    program sama sekali.
 *  - .dynsym / .dynstr / .hash / .gnu.hash TIDAK PERNAH masuk daftar hapus
 *    (wajib ada untuk dynamic linker saat load .so).
 *  - .note.gnu.build-id TIDAK PERNAH dihapus (dipakai Crash Analyzer buat
 *    mencocokkan .so ini persis dengan build yang crash).
 *  - Kalau ternyata ada section yang DIPERTAHANKAN tapi masih merujuk (sh_link)
 *    ke salah satu section yang mau dihapus, penghapusan section target itu
 *    otomatis DIBATALKAN (fail-safe: lebih baik gagal hemat sedikit byte
 *    daripada menghasilkan .so yang corrupt).
 */
object ElfStripper {

    data class StripResult(
        val success: Boolean,
        val error: String? = null,
        val outputFile: File? = null,
        val originalSize: Long = 0,
        val strippedSize: Long = 0,
        val removedSections: List<String> = emptyList()
    )

    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val SHF_ALLOC = 0x2L
    private const val SHT_NULL = 0
    private const val SHT_NOBITS = 8   // .bss dkk — sh_offset/sh_size-nya BUKAN byte file nyata
    private const val SHT_STRTAB = 3
    private const val MAX_SHSTRTAB_BYTES = 16L * 1024 * 1024

    private val REMOVABLE_EXACT = setOf(".symtab", ".strtab", ".comment")

    private fun isRemovableName(name: String): Boolean {
        if (name in REMOVABLE_EXACT) return true
        return name.startsWith(".debug_") || name.startsWith(".zdebug_") || name == ".gnu_debuglink"
    }

    private class RawSection(
        val nameOff: Int,
        val type: Int,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addralign: Long,
        val entsize: Long
    ) {
        var name: String = ""
    }

    /**
     * @param sourceFile file .so asli — TIDAK PERNAH ditimpa/diubah.
     * @param outputFile file hasil strip (baru). Sengaja terpisah dari [sourceFile]
     *                   supaya versi asli (dengan simbol lengkap) selalu tetap ada
     *                   untuk keperluan debug di kemudian hari.
     */
    fun strip(sourceFile: File, outputFile: File): StripResult {
        return try {
            RandomAccessFile(sourceFile, "r").use { raf ->
                stripInternal(raf, sourceFile.length(), outputFile)
            }
        } catch (oom: OutOfMemoryError) {
            StripResult(success = false, error = "File terlalu besar untuk diproses (out of memory).")
        } catch (e: Exception) {
            StripResult(success = false, error = "Gagal strip: ${e.message}")
        }
    }

    private fun cstr(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun stripInternal(raf: RandomAccessFile, fileSize: Long, outputFile: File): StripResult {
        if (fileSize < 64) return StripResult(success = false, error = "File terlalu kecil untuk jadi ELF valid.")

        val header = ByteArray(64)
        raf.seek(0)
        raf.readFully(header)
        if (header[0] != ELF_MAGIC_0 || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return StripResult(success = false, error = "Bukan file ELF (.so) yang valid.")
        }
        val is64 = header[4].toInt() == 2
        val isLittleEndian = header[5].toInt() == 1
        if (!isLittleEndian) return StripResult(success = false, error = "Format big-endian tidak didukung.")

        val hbuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize: Int
        val eShoff: Long
        val eShentsize: Int
        val eShnum: Int
        val eShstrndx: Int

        if (is64) {
            headerSize = 64
            eShoff = hbuf.getLong(40)
            eShentsize = hbuf.getShort(58).toInt() and 0xFFFF
            eShnum = hbuf.getShort(60).toInt() and 0xFFFF
            eShstrndx = hbuf.getShort(62).toInt() and 0xFFFF
        } else {
            headerSize = 52
            eShoff = (hbuf.getInt(32).toLong() and 0xFFFFFFFFL)
            eShentsize = hbuf.getShort(46).toInt() and 0xFFFF
            eShnum = hbuf.getShort(48).toInt() and 0xFFFF
            eShstrndx = hbuf.getShort(50).toInt() and 0xFFFF
        }

        if (eShoff <= 0 || eShnum <= 0) {
            return StripResult(success = false, error = "File sudah tidak punya section header table (sudah di-strip habis) — tidak ada lagi yang bisa dipangkas.")
        }
        if (eShnum >= 0xFF00) {
            return StripResult(success = false, error = "Jumlah section tidak wajar, file kemungkinan korup.")
        }

        // --- Baca seluruh section header table (biasa cuma puluhan KB) ---
        val shTableSize = eShnum.toLong() * eShentsize
        if (shTableSize <= 0 || eShoff + shTableSize > fileSize) {
            return StripResult(success = false, error = "Section header table tidak valid/rusak.")
        }
        val shBytes = ByteArray(shTableSize.toInt())
        raf.seek(eShoff)
        raf.readFully(shBytes)
        val shBuf = ByteBuffer.wrap(shBytes).order(ByteOrder.LITTLE_ENDIAN)

        val sections = ArrayList<RawSection>(eShnum)
        for (i in 0 until eShnum) {
            val base = i * eShentsize
            val sec = if (is64) {
                RawSection(
                    nameOff = shBuf.getInt(base), type = shBuf.getInt(base + 4),
                    flags = shBuf.getLong(base + 8), addr = shBuf.getLong(base + 16),
                    offset = shBuf.getLong(base + 24), size = shBuf.getLong(base + 32),
                    link = shBuf.getInt(base + 40), info = shBuf.getInt(base + 44),
                    addralign = shBuf.getLong(base + 48), entsize = shBuf.getLong(base + 56)
                )
            } else {
                RawSection(
                    nameOff = shBuf.getInt(base), type = shBuf.getInt(base + 4),
                    flags = (shBuf.getInt(base + 8).toLong() and 0xFFFFFFFFL),
                    addr = (shBuf.getInt(base + 12).toLong() and 0xFFFFFFFFL),
                    offset = (shBuf.getInt(base + 16).toLong() and 0xFFFFFFFFL),
                    size = (shBuf.getInt(base + 20).toLong() and 0xFFFFFFFFL),
                    link = shBuf.getInt(base + 24), info = shBuf.getInt(base + 28),
                    addralign = (shBuf.getInt(base + 32).toLong() and 0xFFFFFFFFL),
                    entsize = (shBuf.getInt(base + 36).toLong() and 0xFFFFFFFFL)
                )
            }
            sections.add(sec)
        }

        if (eShstrndx < 0 || eShstrndx >= sections.size) {
            return StripResult(success = false, error = "Index shstrtab tidak valid.")
        }

        // --- Resolve nama tiap section dari shstrtab lama ---
        val shstrtabSec = sections[eShstrndx]
        if (shstrtabSec.offset < 0 || shstrtabSec.size < 0 ||
            shstrtabSec.offset + shstrtabSec.size > fileSize || shstrtabSec.size > MAX_SHSTRTAB_BYTES
        ) {
            return StripResult(success = false, error = "shstrtab tidak valid/rusak.")
        }
        val oldShstrBytes = ByteArray(shstrtabSec.size.toInt())
        raf.seek(shstrtabSec.offset)
        raf.readFully(oldShstrBytes)
        for (sec in sections) sec.name = cstr(oldShstrBytes, sec.nameOff)

        // --- Tandai kandidat section yang mau dihapus ---
        val removeFlags = BooleanArray(sections.size)
        for (i in sections.indices) {
            if (i == 0) continue                 // SHT_NULL, jangan disentuh
            if (i == eShstrndx) continue          // shstrtab lama, ditangani khusus (dibangun ulang)
            val sec = sections[i]
            if (isRemovableName(sec.name) && (sec.flags and SHF_ALLOC) == 0L) {
                removeFlags[i] = true
            }
        }

        // --- Fail-safe: batalkan hapus kalau masih dirujuk sh_link oleh section yang dipertahankan ---
        var changed = true
        while (changed) {
            changed = false
            for (i in sections.indices) {
                if (removeFlags[i]) continue
                val link = sections[i].link
                if (link in sections.indices && removeFlags[link]) {
                    removeFlags[link] = false
                    changed = true
                }
            }
        }

        val removedNames = sections.indices.filter { removeFlags[it] }.map { sections[it].name }
        if (removedNames.isEmpty()) {
            return StripResult(
                success = false,
                error = "Tidak ada section yang bisa dihapus dengan aman (kemungkinan file sudah stripped)."
            )
        }

        // --- Susun daftar section yang DIPERTAHANKAN (shstrtab lama dikeluarkan, dibangun ulang) ---
        val keptIndices = sections.indices.filter { !removeFlags[it] && it != eShstrndx }
        val oldToNewIndex = HashMap<Int, Int>()
        keptIndices.forEachIndexed { newIdx, oldIdx -> oldToNewIndex[oldIdx] = newIdx }
        oldToNewIndex[eShstrndx] = keptIndices.size // shstrtab baru = index terakhir

        // --- Batas byte terakhir dari data section yang dipertahankan (SHT_NOBITS/.bss dilewati:
        //     offset/size-nya bukan byte file nyata) ---
        var dataEnd = headerSize.toLong()
        for (i in keptIndices) {
            val sec = sections[i]
            if (sec.type != SHT_NULL && sec.type != SHT_NOBITS) {
                val end = sec.offset + sec.size
                if (end > dataEnd) dataEnd = end
            }
        }
        if (dataEnd > fileSize) {
            return StripResult(success = false, error = "Ukuran section tidak konsisten dengan ukuran file.")
        }

        // --- Bangun shstrtab BARU: hanya nama section yang dipertahankan + nama shstrtab sendiri ---
        val newShstrBuffer = ByteArrayOutputStream()
        newShstrBuffer.write(0) // offset 0 = string kosong (konvensi standar ELF)
        val nameOffsetMap = HashMap<Int, Int>()
        for (i in keptIndices) {
            nameOffsetMap[i] = newShstrBuffer.size()
            newShstrBuffer.write(sections[i].name.toByteArray(Charsets.UTF_8))
            newShstrBuffer.write(0)
        }
        val shstrtabNameOffset = newShstrBuffer.size()
        newShstrBuffer.write(".shstrtab".toByteArray(Charsets.UTF_8))
        newShstrBuffer.write(0)
        val newShstrBytes = newShstrBuffer.toByteArray()

        val shstrtabOffset = dataEnd
        val shTableOffsetUnaligned = shstrtabOffset + newShstrBytes.size
        val align = 8L
        val shTableOffset = (shTableOffsetUnaligned + (align - 1)) / align * align
        val paddingSize = (shTableOffset - shTableOffsetUnaligned).toInt()

        val newShnum = keptIndices.size + 1
        val newShstrndx = keptIndices.size
        val newEntSize = if (is64) 64 else 40
        val newShTableBytes = ByteArray(newShnum * newEntSize)
        val outBuf = ByteBuffer.wrap(newShTableBytes).order(ByteOrder.LITTLE_ENDIAN)

        fun writeSection(
            idx: Int, nameOff: Int, type: Int, flags: Long, addr: Long,
            offset: Long, size: Long, link: Int, info: Int, addralign: Long, entsize: Long
        ) {
            val base = idx * newEntSize
            if (is64) {
                outBuf.putInt(base, nameOff); outBuf.putInt(base + 4, type)
                outBuf.putLong(base + 8, flags); outBuf.putLong(base + 16, addr)
                outBuf.putLong(base + 24, offset); outBuf.putLong(base + 32, size)
                outBuf.putInt(base + 40, link); outBuf.putInt(base + 44, info)
                outBuf.putLong(base + 48, addralign); outBuf.putLong(base + 56, entsize)
            } else {
                outBuf.putInt(base, nameOff); outBuf.putInt(base + 4, type)
                outBuf.putInt(base + 8, flags.toInt()); outBuf.putInt(base + 12, addr.toInt())
                outBuf.putInt(base + 16, offset.toInt()); outBuf.putInt(base + 20, size.toInt())
                outBuf.putInt(base + 24, link); outBuf.putInt(base + 28, info)
                outBuf.putInt(base + 32, addralign.toInt()); outBuf.putInt(base + 36, entsize.toInt())
            }
        }

        keptIndices.forEachIndexed { newIdx, oldIdx ->
            val sec = sections[oldIdx]
            // sh_link diremap ke index baru (dia SELALU merujuk index section lain).
            // sh_info SENGAJA tidak diremap: untuk SHT_SYMTAB/SHT_DYNSYM artinya bukan
            // index section (melainkan "index simbol non-local pertama"), dan untuk
            // .rela.dyn/.rela.plt pada shared library yang sudah di-link, nilainya 0.
            val remappedLink = oldToNewIndex[sec.link] ?: sec.link
            writeSection(
                newIdx, nameOff = nameOffsetMap[oldIdx] ?: 0, type = sec.type, flags = sec.flags,
                addr = sec.addr, offset = sec.offset, size = sec.size,
                link = remappedLink, info = sec.info, addralign = sec.addralign, entsize = sec.entsize
            )
        }
        writeSection(
            keptIndices.size, nameOff = shstrtabNameOffset, type = SHT_STRTAB, flags = 0L,
            addr = 0L, offset = shstrtabOffset, size = newShstrBytes.size.toLong(),
            link = 0, info = 0, addralign = 1L, entsize = 0L
        )

        // --- Update header ELF: e_shoff / e_shnum / e_shstrndx ---
        val newHeader = header.copyOf()
        val newHbuf = ByteBuffer.wrap(newHeader).order(ByteOrder.LITTLE_ENDIAN)
        if (is64) {
            newHbuf.putLong(40, shTableOffset)
            newHbuf.putShort(60, newShnum.toShort())
            newHbuf.putShort(62, newShstrndx.toShort())
        } else {
            newHbuf.putInt(32, shTableOffset.toInt())
            newHbuf.putShort(48, newShnum.toShort())
            newHbuf.putShort(50, newShstrndx.toShort())
        }

        // --- Tulis file baru: header(updated) + [headerSize..dataEnd) apa adanya (STREAMING,
        //     bukan ByteArray, biar aman untuk lib ratusan MB) + shstrtab baru + padding + SHT baru ---
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        RandomAccessFile(outputFile, "rw").use { outRaf ->
            outRaf.setLength(0)
            outRaf.write(newHeader, 0, headerSize)

            val srcChannel = raf.channel
            val dstChannel = outRaf.channel
            var pos = headerSize.toLong()
            while (pos < dataEnd) {
                val transferred = srcChannel.transferTo(pos, dataEnd - pos, dstChannel)
                if (transferred <= 0) break
                pos += transferred
            }

            outRaf.seek(dataEnd)
            outRaf.write(newShstrBytes)
            if (paddingSize > 0) outRaf.write(ByteArray(paddingSize))
            outRaf.write(newShTableBytes)
        }

        return StripResult(
            success = true,
            outputFile = outputFile,
            originalSize = fileSize,
            strippedSize = outputFile.length(),
            removedSections = removedNames
        )
    }
}
