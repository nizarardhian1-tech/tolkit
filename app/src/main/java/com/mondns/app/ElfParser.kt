package com.mondns.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser ELF (.so) murni Kotlin, tanpa dependency native/NDK toolchain.
 *
 * PENTING (fix OOM untuk lib besar seperti IL2CPP/Unity yang bisa ratusan MB):
 * Parser ini TIDAK PERNAH membaca seluruh isi file ke memori. Ia memakai
 * [RandomAccessFile] dan hanya membaca byte range yang benar-benar dibutuhkan:
 * ELF header (~64 byte), tabel section header (biasanya puluhan KB), dan isi
 * section simbol/string (.symtab/.dynsym/.strtab/.dynstr) — yang meski berisi
 * puluhan-ribu simbol, ukurannya tetap jauh lebih kecil dari total file, karena
 * mayoritas ukuran file (.text/.data — kode & aset native) sama sekali tidak
 * pernah disentuh/dibaca oleh parser ini.
 *
 * Referensi format: System V ABI - ELF Specification.
 * Support ELF32 & ELF64, little-endian (satu-satunya yang relevan untuk Android/ARM & x86).
 */
object ElfParser {

    data class ElfSymbol(
        val name: String,
        val address: Long,
        val size: Long,
        val isFunction: Boolean,
        val isDefined: Boolean, // punya alamat valid (bukan cuma referensi eksternal)
        val demangledName: String? = null, // hasil CppDemangler, null kalau bukan simbol C++ (_Z...) atau gagal parse
        val className: String? = null,     // "Namespace::Class" kalau simbol ini member function, null kalau bukan
        val sectionName: String? = null,   // nama section ELF tempat simbol ini berada, misal ".text"
        val fileOffset: Long = -1L         // posisi byte di dalam FILE .so (beda dari address/RVA!), -1 kalau gak diketahui
    ) {
        /** Nama yang enak dibaca: demangled kalau ada, fallback ke nama mentah. */
        val displayName: String get() = demangledName ?: name
    }

    data class ElfInfo(
        val isValid: Boolean,
        val error: String? = null,
        val is64Bit: Boolean = false,
        val architecture: String = "Unknown",
        val entryPoint: Long = 0,
        val isStripped: Boolean = true,
        val neededLibraries: List<String> = emptyList(),
        val soName: String? = null,
        val buildId: String? = null,
        val definedSymbols: List<ElfSymbol> = emptyList(),   // fungsi yang diexport (definisi ada di sini)
        val undefinedSymbols: List<ElfSymbol> = emptyList(), // fungsi yang diimport dari lib lain
        val fileSize: Long = 0,
        val symbolTableTruncated: Boolean = false, // true kalau ada section yang dilewati krn kelewat besar/rusak
        val sha256: String? = null,          // fingerprint file, dihitung streaming (bukan load full file)
        val hasStackCanary: Boolean = false, // ada __stack_chk_fail di undefined symbols
        val hasFortify: Boolean = false,     // ada fungsi _chk (mis. __memcpy_chk) -> FORTIFY_SOURCE aktif
        val hasNxStack: Boolean? = null,     // null = gak kebaca (gak ada program header), true = stack non-executable (aman)
        val relro: String = "None"           // "Full" | "Partial" | "None"
    )

    private const val ELF_MAGIC_0 = 0x7F.toByte()

    // Batas pengaman: kalau sebuah section MENGAKU berukuran lebih dari ini, kemungkinan
    // besar file korup/bukan ELF valid — kita skip section itu daripada nekat alokasi
    // besar dan berisiko OOM lagi. 64MB sudah sangat lebih dari cukup untuk tabel simbol
    // paling gemuk sekalipun (puluhan-ribu simbol biasanya cuma beberapa MB).
    private const val MAX_SAFE_SECTION_BYTES = 64L * 1024 * 1024

    // e_machine values yang relevan buat Android
    private fun machineName(value: Int): String = when (value) {
        0x03 -> "x86 (386)"
        0x08 -> "MIPS"
        0x28 -> "ARM (armeabi-v7a)"
        0x3E -> "x86_64"
        0xB7 -> "AArch64 (arm64-v8a)"
        else -> "Unknown (0x${value.toString(16)})"
    }

    // Tipe simbol (bit 0-3 dari st_info)
    private fun symType(stInfo: Int): Int = stInfo and 0xF
    private const val STT_FUNC = 2
    private const val SHN_UNDEF = 0

    fun parse(file: File): ElfInfo {
        return try {
            val info = RandomAccessFile(file, "r").use { raf ->
                parseRandomAccess(raf, file.length())
            }
            if (info.isValid) info.copy(sha256 = computeSha256(file)) else info
        } catch (oom: OutOfMemoryError) {
            ElfInfo(isValid = false, error = "File terlalu besar untuk diproses (out of memory).")
        } catch (e: Exception) {
            ElfInfo(isValid = false, error = "Gagal membaca file: ${e.message}")
        }
    }

    /** SHA-256 dihitung streaming (chunk 64KB) — TIDAK PERNAH load seluruh file ke RAM,
     *  konsisten dengan pendekatan hemat-memori di seluruh parser ini. */
    private fun computeSha256(file: File): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Baca sejumlah byte pada offset tertentu tanpa pernah menyentuh sisa file. */
    /**
     * Baca byte MENTAH pada range tertentu di file .so — dipakai fitur Hex Dump &
     * Disassembler buat ambil isi 1 fungsi spesifik (pakai fileOffset+size dari ElfSymbol).
     * Sengaja dibatasi 8MB per panggilan (fungsi asli jarang sebesar itu; kalau size di
     * ElfSymbol somehow ngaco/korup, ini nyegah alokasi besar yang gak perlu).
     */
    fun readBytesAt(file: File, offset: Long, length: Long): ByteArray? {
        val cappedLength = length.coerceAtMost(8L * 1024 * 1024)
        if (offset < 0 || cappedLength <= 0) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (offset + cappedLength > file.length()) return null
                readAt(raf, offset, cappedLength.toInt())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    /** Sama seperti [readAt], tapi mengembalikan null (bukan melempar) kalau range dianggap
     *  tidak aman/rusak, supaya section bermasalah cukup dilewati, bukan bikin seluruh
     *  parsing gagal. */
    private fun readAtSafe(raf: RandomAccessFile, offset: Long, length: Long, fileSize: Long): ByteArray? {
        if (offset < 0 || length < 0) return null
        if (offset + length > fileSize) return null
        if (length > MAX_SAFE_SECTION_BYTES) return null
        return try {
            readAt(raf, offset, length.toInt())
        } catch (e: Exception) {
            null
        }
    }

    private fun cstr(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseRandomAccess(raf: RandomAccessFile, fileSize: Long): ElfInfo {
        if (fileSize < 20) return ElfInfo(isValid = false, error = "File terlalu kecil untuk jadi ELF (.so) yang valid.")

        // Header ELF64 maksimal 64 byte — cukup untuk baca header ELF32 maupun ELF64.
        val headerSize = if (fileSize >= 64) 64 else fileSize.toInt()
        val header = readAt(raf, 0, headerSize)

        if (header.size < 20 || header[0] != ELF_MAGIC_0 || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return ElfInfo(isValid = false, error = "Bukan file ELF (.so) yang valid.")
        }

        val is64 = header[4].toInt() == 2 // EI_CLASS: 1 = ELF32, 2 = ELF64
        val isLittleEndian = header[5].toInt() == 1 // EI_DATA: 1 = LSB
        if (!isLittleEndian) {
            return ElfInfo(isValid = false, error = "Format big-endian tidak didukung (jarang dipakai di Android).")
        }

        val hbuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val eMachine: Int
        val eEntry: Long
        val eShoff: Long
        val eShentsize: Int
        val eShnum: Int
        val eShstrndx: Int
        val ePhoff: Long
        val ePhentsize: Int
        val ePhnum: Int

        if (is64) {
            eMachine = hbuf.getShort(18).toInt() and 0xFFFF
            eEntry = hbuf.getLong(24)
            ePhoff = hbuf.getLong(32)
            eShoff = hbuf.getLong(40)
            ePhentsize = hbuf.getShort(54).toInt() and 0xFFFF
            ePhnum = hbuf.getShort(56).toInt() and 0xFFFF
            eShentsize = hbuf.getShort(58).toInt() and 0xFFFF
            eShnum = hbuf.getShort(60).toInt() and 0xFFFF
            eShstrndx = hbuf.getShort(62).toInt() and 0xFFFF
        } else {
            eMachine = hbuf.getShort(18).toInt() and 0xFFFF
            eEntry = (hbuf.getInt(24).toLong() and 0xFFFFFFFFL)
            ePhoff = (hbuf.getInt(28).toLong() and 0xFFFFFFFFL)
            eShoff = (hbuf.getInt(32).toLong() and 0xFFFFFFFFL)
            ePhentsize = hbuf.getShort(42).toInt() and 0xFFFF
            ePhnum = hbuf.getShort(44).toInt() and 0xFFFF
            eShentsize = hbuf.getShort(46).toInt() and 0xFFFF
            eShnum = hbuf.getShort(48).toInt() and 0xFFFF
            eShstrndx = hbuf.getShort(50).toInt() and 0xFFFF
        }

        if (eShoff <= 0 || eShnum <= 0) {
            // Ada .so valid tapi tanpa section header (sudah di-strip habis-habisan / raw).
            return ElfInfo(
                isValid = true,
                is64Bit = is64,
                architecture = machineName(eMachine),
                entryPoint = eEntry,
                isStripped = true,
                fileSize = fileSize
            )
        }

        data class Section(
            val nameOff: Int, val type: Int, val offset: Long,
            val size: Long, val link: Int, val entSize: Long, val addr: Long
        )

        // Tabel section header biasanya cuma puluhan-KB walau ada ratusan section — aman dibaca sekaligus.
        val shTableBytes = readAtSafe(raf, eShoff, eShnum.toLong() * eShentsize, fileSize)
            ?: return ElfInfo(isValid = false, error = "Section header table tidak valid/rusak.")
        val shBuf = ByteBuffer.wrap(shTableBytes).order(ByteOrder.LITTLE_ENDIAN)

        val sections = ArrayList<Section>(eShnum)
        for (i in 0 until eShnum) {
            val base = i * eShentsize
            if (base + eShentsize > shTableBytes.size) break
            if (is64) {
                sections.add(
                    Section(
                        nameOff = shBuf.getInt(base),
                        type = shBuf.getInt(base + 4),
                        addr = shBuf.getLong(base + 16),
                        offset = shBuf.getLong(base + 24),
                        size = shBuf.getLong(base + 32),
                        link = shBuf.getInt(base + 40),
                        entSize = shBuf.getLong(base + 56)
                    )
                )
            } else {
                sections.add(
                    Section(
                        nameOff = shBuf.getInt(base),
                        type = shBuf.getInt(base + 4),
                        addr = (shBuf.getInt(base + 12).toLong() and 0xFFFFFFFFL),
                        offset = (shBuf.getInt(base + 16).toLong() and 0xFFFFFFFFL),
                        size = (shBuf.getInt(base + 20).toLong() and 0xFFFFFFFFL),
                        link = shBuf.getInt(base + 24),
                        entSize = (shBuf.getInt(base + 36).toLong() and 0xFFFFFFFFL)
                    )
                )
            }
        }

        var symbolTableTruncated = false

        // Nama-nama section (.text, .symtab, dst) ada di section string table — biasanya kecil.
        val shstrtab = if (eShstrndx in sections.indices) sections[eShstrndx] else null
        val shstrBytes = shstrtab?.let { readAtSafe(raf, it.offset, it.size, fileSize) }
        if (shstrtab != null && shstrBytes == null) symbolTableTruncated = true
        fun sectionName(s: Section): String = shstrBytes?.let { cstr(it, s.nameOff) } ?: ""

        val sectionByName = sections.associateBy { sectionName(it) }

        // SHT_SYMTAB = 2, SHT_DYNSYM = 11, SHT_DYNAMIC = 6, SHT_STRTAB = 3
        val symtabSection = sections.firstOrNull { it.type == 2 }
        val dynsymSection = sections.firstOrNull { it.type == 11 }
        val hasFullSymtab = symtabSection != null

        // Baca HANYA isi section simbol & string table yang relevan (bukan seluruh file).
        // Untuk lib dengan puluhan-ribu simbol ini biasanya cuma beberapa MB, jauh di
        // bawah ukuran total file yang bisa ratusan MB.
        fun readSymbols(symSection: Section, strSection: Section): List<ElfSymbol> {
            val symBytes = readAtSafe(raf, symSection.offset, symSection.size, fileSize)
            val strBytes = readAtSafe(raf, strSection.offset, strSection.size, fileSize)
            if (symBytes == null || strBytes == null) {
                symbolTableTruncated = true
                return emptyList()
            }
            val symBuf = ByteBuffer.wrap(symBytes).order(ByteOrder.LITTLE_ENDIAN)
            val result = ArrayList<ElfSymbol>()
            if (symSection.entSize <= 0) return result
            val count = (symBytes.size / symSection.entSize).toInt()
            for (i in 0 until count) {
                val base = (i * symSection.entSize).toInt()
                if (base + symSection.entSize > symBytes.size) break
                val nameOff: Int
                val value: Long
                val size: Long
                val info: Int
                val shndx: Int
                if (is64) {
                    nameOff = symBuf.getInt(base)
                    info = symBytes[base + 4].toInt() and 0xFF
                    shndx = symBuf.getShort(base + 6).toInt() and 0xFFFF
                    value = symBuf.getLong(base + 8)
                    size = symBuf.getLong(base + 16)
                } else {
                    nameOff = symBuf.getInt(base)
                    value = (symBuf.getInt(base + 4).toLong() and 0xFFFFFFFFL)
                    size = (symBuf.getInt(base + 8).toLong() and 0xFFFFFFFFL)
                    info = symBytes[base + 12].toInt() and 0xFF
                    shndx = symBuf.getShort(base + 14).toInt() and 0xFFFF
                }
                val name = cstr(strBytes, nameOff)
                if (name.isBlank()) continue

                // Section tempat simbol ini berada + FILE OFFSET (posisi bytenya di dalam
                // file .so itu sendiri) — beda dari RVA/address yang cuma alamat relatif
                // pas library di-load ke memori. SHT_NOBITS (.bss) gak punya isi di file,
                // jadi file offset-nya gak berlaku (ditandai -1).
                val sectionForSymbol = if (shndx in sections.indices) sections[shndx] else null
                val symSectionName = sectionForSymbol?.let { sectionName(it) }
                val symFileOffset = if (sectionForSymbol != null && sectionForSymbol.type != 8) {
                    sectionForSymbol.offset + (value - sectionForSymbol.addr)
                } else -1L

                // Demangle kalau ini simbol C++ (mangled name Itanium ABI selalu berawalan "_Z").
                // Biaya ini sebanding panjang nama, bukan ukuran file — murah walau puluhan-ribu simbol.
                var demangled: String? = null
                var className: String? = null
                if (name.startsWith("_Z")) {
                    val r = CppDemangler.demangle(name)
                    if (r.success) {
                        demangled = r.signature
                        className = r.className
                    }
                }

                result.add(
                    ElfSymbol(
                        name = name,
                        address = value,
                        size = size,
                        isFunction = symType(info) == STT_FUNC,
                        isDefined = shndx != SHN_UNDEF,
                        demangledName = demangled,
                        className = className,
                        sectionName = symSectionName,
                        fileOffset = symFileOffset
                    )
                )
            }
            return result
        }

        val allSymbols = ArrayList<ElfSymbol>()
        listOfNotNull(
            symtabSection?.let { it to sections.getOrNull(it.link) },
            dynsymSection?.let { it to sections.getOrNull(it.link) }
        ).forEach { (symSec, strSec) ->
            if (strSec != null) allSymbols.addAll(readSymbols(symSec, strSec))
        }
        // Dedupe berdasarkan nama+alamat (symtab & dynsym sering tumpang tindih)
        val distinctSymbols = allSymbols.distinctBy { "${it.name}:${it.address}" }
        val defined = distinctSymbols.filter { it.isDefined && it.isFunction }.sortedBy { it.address }
        val undefined = distinctSymbols.filter { !it.isDefined }.distinctBy { it.name }.sortedBy { it.name }

        // DT_NEEDED (lib dependency) ada di section .dynamic, di-resolve namanya lewat .dynstr
        val neededLibs = ArrayList<String>()
        var soName: String? = null
        var dtBindNowFlag = false // DF_BIND_NOW / DF_1_NOW / DT_BIND_NOW -> penanda "Full RELRO"
        val dynamicSection = sections.firstOrNull { it.type == 6 } // SHT_DYNAMIC
        val dynstrSection = sectionByName[".dynstr"]
        if (dynamicSection != null && dynstrSection != null) {
            val dynBytes = readAtSafe(raf, dynamicSection.offset, dynamicSection.size, fileSize)
            val dynstrBytes = readAtSafe(raf, dynstrSection.offset, dynstrSection.size, fileSize)
            if (dynBytes != null && dynstrBytes != null) {
                val dynBuf = ByteBuffer.wrap(dynBytes).order(ByteOrder.LITTLE_ENDIAN)
                val entrySize = if (is64) 16 else 8
                val count = (dynBytes.size / entrySize)
                for (i in 0 until count) {
                    val base = i * entrySize
                    if (base + entrySize > dynBytes.size) break
                    val tag: Long
                    val value: Long
                    if (is64) {
                        tag = dynBuf.getLong(base)
                        value = dynBuf.getLong(base + 8)
                    } else {
                        tag = (dynBuf.getInt(base).toLong())
                        value = (dynBuf.getInt(base + 4).toLong() and 0xFFFFFFFFL)
                    }
                    if (tag.toInt() == 0) break // DT_NULL = akhir tabel dynamic
                    when (tag) {
                        1L -> neededLibs.add(cstr(dynstrBytes, value.toInt())) // DT_NEEDED
                        14L -> soName = cstr(dynstrBytes, value.toInt())       // DT_SONAME
                        24L -> dtBindNowFlag = true                            // DT_BIND_NOW
                        30L -> if (value and 0x8L != 0L) dtBindNowFlag = true   // DT_FLAGS, bit DF_BIND_NOW
                        0x6ffffffbL -> if (value and 0x1L != 0L) dtBindNowFlag = true // DT_FLAGS_1, bit DF_1_NOW
                    }
                }
            } else {
                symbolTableTruncated = true
            }
        }

        // Program header table: dibutuhkan buat cek NX stack (PT_GNU_STACK) & RELRO
        // (PT_GNU_RELRO). Ukurannya kecil (belasan entry biasanya), aman dibaca sekaligus.
        var hasNxStack: Boolean? = null
        var hasRelroSegment = false
        if (ePhoff > 0 && ePhnum > 0) {
            val phBytes = readAtSafe(raf, ePhoff, ePhnum.toLong() * ePhentsize, fileSize)
            if (phBytes != null) {
                val phBuf = ByteBuffer.wrap(phBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until ePhnum) {
                    val base = i * ePhentsize
                    if (base + ePhentsize > phBytes.size) break
                    val pType: Long
                    val pFlags: Int
                    if (is64) {
                        pType = phBuf.getInt(base).toLong() and 0xFFFFFFFFL
                        pFlags = phBuf.getInt(base + 4)
                    } else {
                        pType = phBuf.getInt(base).toLong() and 0xFFFFFFFFL
                        pFlags = phBuf.getInt(base + 24) // beda posisi di ELF32!
                    }
                    when (pType) {
                        0x6474e551L -> hasNxStack = (pFlags and 0x1) == 0 // PT_GNU_STACK, PF_X=1
                        0x6474e552L -> hasRelroSegment = true             // PT_GNU_RELRO
                    }
                }
            }
        }
        val relro = when {
            hasRelroSegment && dtBindNowFlag -> "Full"
            hasRelroSegment -> "Partial"
            else -> "None"
        }

        // Stack canary & FORTIFY_SOURCE: proxy sederhana (sama seperti tool `checksec`)
        // berdasarkan ada-tidaknya fungsi bantuan compiler tertentu di undefined symbols.
        val hasStackCanary = undefined.any { it.name == "__stack_chk_fail" }
        val hasFortify = undefined.any { it.name.endsWith("_chk") && it.name.startsWith("__") }

        // Build ID (.note.gnu.build-id) — dipakai buat mastiin .so yang dilampirkan di
        // Crash Analyzer BENAR-BENAR versi yang sama persis dengan yang crash.
        var buildId: String? = null
        val noteSection = sectionByName[".note.gnu.build-id"]
        if (noteSection != null) {
            val noteBytes = readAtSafe(raf, noteSection.offset, noteSection.size, fileSize)
            if (noteBytes != null && noteBytes.size >= 12) {
                val noteBuf = ByteBuffer.wrap(noteBytes).order(ByteOrder.LITTLE_ENDIAN)
                val namesz = noteBuf.getInt(0)
                val descsz = noteBuf.getInt(4)
                val type = noteBuf.getInt(8)
                val nameAligned = (namesz + 3) and 3.inv()
                val descOffset = 12 + nameAligned
                if (type == 3 && descsz > 0 && descOffset + descsz <= noteBytes.size) { // NT_GNU_BUILD_ID
                    val sb = StringBuilder()
                    for (i in 0 until descsz) {
                        sb.append(String.format("%02x", noteBytes[descOffset + i].toInt() and 0xFF))
                    }
                    buildId = sb.toString()
                }
            }
        }

        return ElfInfo(
            isValid = true,
            is64Bit = is64,
            architecture = machineName(eMachine),
            entryPoint = eEntry,
            isStripped = !hasFullSymtab,
            neededLibraries = neededLibs,
            soName = soName,
            buildId = buildId,
            definedSymbols = defined,
            undefinedSymbols = undefined,
            fileSize = fileSize,
            symbolTableTruncated = symbolTableTruncated,
            hasStackCanary = hasStackCanary,
            hasFortify = hasFortify,
            hasNxStack = hasNxStack,
            relro = relro
        )
    }

    /**
     * Dipakai oleh Crash Analyzer: cari simbol fungsi yang "menaungi" sebuah offset
     * (alamat pc dikurangi base load address). Karena kita tidak selalu punya DWARF
     * debug info, resolusinya berbasis symbol table: cari simbol ber-alamat tertinggi
     * yang masih <= offset target (nearest-preceding-symbol match).
     */
    fun resolveOffset(info: ElfInfo, offset: Long): ElfSymbol? {
        var result: ElfSymbol? = null
        for (sym in info.definedSymbols) {
            if (sym.address <= offset) {
                if (result == null || sym.address > result!!.address) result = sym
            } else break // definedSymbols sudah terurut naik, aman untuk stop lebih awal
        }
        return result
    }
}
