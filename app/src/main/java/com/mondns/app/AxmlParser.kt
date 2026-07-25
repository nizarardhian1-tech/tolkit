package com.mondns.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AxmlParser — parser minimal buat format AXML (AndroidManifest.xml biner hasil kompilasi
 * aapt). Ini BUKAN parser XML umum: raw string scan (kayak dipakai buat cari nama
 * permission di SecurityScannerEngine) nggak cukup buat baca ATRIBUT ber-tipe kayak
 * android:debuggable="true" atau android:exported="false", karena nilainya disimpan
 * sebagai typed binary data (boolean/int), bukan teks biasa.
 *
 * Format referensi: struktur chunk ResChunk_header dari AOSP (frameworks/base
 * ResourceTypes.h) — string pool chunk (0x0001) diikuti node-node XML
 * (START_ELEMENT/END_ELEMENT/dst). Parser ini sengaja cuma ambil yang dibutuhkan buat
 * audit manifest (nama elemen + atribut), bukan reproduksi XML lengkap.
 *
 * Kalau parsing gagal di tengah jalan (manifest custom/obfuscated/rusak), fungsi ini
 * melempar exception yang HARUS ditangani pemanggil — jangan biarkan gagal parse manifest
 * menggagalkan seluruh proses scan APK.
 */
object AxmlParser {

    data class Element(
        val name: String,
        val attributes: Map<String, AttrValue>,
        val children: MutableList<Element> = mutableListOf()
    )

    /** Nilai atribut apa adanya: string, boolean, atau integer mentah (termasuk resource ref). */
    data class AttrValue(val type: Int, val raw: Int, val stringValue: String?) {
        val asBoolean: Boolean? get() = if (type == TYPE_INT_BOOLEAN) raw != 0 else null
        val asString: String? get() = stringValue

        /** Representasi tampilan apa adanya, apapun tipe datanya (string/boolean/integer). */
        fun displayValue(): String = when (type) {
            TYPE_STRING -> stringValue ?: ""
            TYPE_INT_BOOLEAN -> (raw != 0).toString()
            else -> raw.toString()
        }
    }

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML_START_NAMESPACE = 0x0100
    private const val CHUNK_XML_END_NAMESPACE = 0x0101
    private const val CHUNK_XML_START_ELEMENT = 0x0102
    private const val CHUNK_XML_END_ELEMENT = 0x0103

    /** Parse AXML bytes jadi satu tree Element root (biasanya <manifest>). */
    fun parse(bytes: ByteArray): Element {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Header dokumen: type(u16) headerSize(u16) size(u32) — dilewatin, gak perlu divalidasi ketat.
        buf.position(8)

        var strings: List<String> = emptyList()
        val stack = ArrayDeque<Element>()
        var root: Element? = null

        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val size = buf.int
            if (size <= 0 || chunkStart + size > bytes.size) break // chunk gak valid, berhenti aman

            when (type) {
                CHUNK_STRING_POOL -> {
                    strings = parseStringPool(bytes, chunkStart, size)
                }
                CHUNK_XML_START_ELEMENT -> {
                    // Setelah header umum node (lineNumber u32, comment u32) di posisi chunkStart+headerSize
                    val p = ByteBuffer.wrap(bytes, chunkStart + headerSize, size - headerSize).order(ByteOrder.LITTLE_ENDIAN)
                    val nsIdx = p.int // namespaceUri
                    val nameIdx = p.int
                    val attrStart = p.short.toInt() and 0xFFFF
                    val attrSize = p.short.toInt() and 0xFFFF
                    val attrCount = p.short.toInt() and 0xFFFF
                    // idIndex/classIndex/styleIndex — gak dipakai
                    p.short; p.short; p.short

                    val elementName = strings.getOrNull(nameIdx) ?: "?"
                    val attrs = mutableMapOf<String, AttrValue>()

                    for (i in 0 until attrCount) {
                        val base = chunkStart + headerSize + attrStart + i * attrSize
                        if (base + 20 > bytes.size) break
                        val ap = ByteBuffer.wrap(bytes, base, 20).order(ByteOrder.LITTLE_ENDIAN)
                        ap.int // attr namespace — diabaikan (biasanya "android" ns, gak perlu dibedain per-attr di sini)
                        val attrNameIdx = ap.int
                        val rawValueIdx = ap.int
                        ap.short // typedValue.size
                        ap.get()  // res0
                        val dataType = ap.get().toInt() and 0xFF
                        val data = ap.int

                        val attrName = strings.getOrNull(attrNameIdx) ?: continue
                        val strVal = if (dataType == TYPE_STRING) strings.getOrNull(data) else strings.getOrNull(rawValueIdx)
                        attrs[attrName] = AttrValue(dataType, data, strVal)
                    }

                    val el = Element(elementName, attrs)
                    stack.lastOrNull()?.children?.add(el)
                    if (root == null) root = el
                    stack.addLast(el)
                }
                CHUNK_XML_END_ELEMENT -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                // Namespace start/end & CDATA sengaja dilewatin — gak dibutuhkan buat audit atribut.
            }

            buf.position(chunkStart + size)
        }

        return root ?: throw IllegalStateException("Gagal menemukan elemen root di AXML (format gak dikenali)")
    }

    /** String pool: UTF-16 atau UTF-8 tergantung flag, masing-masing string diawali panjangnya. */
    private fun parseStringPool(bytes: ByteArray, chunkStart: Int, chunkSize: Int): List<String> {
        val p = ByteBuffer.wrap(bytes, chunkStart, chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        p.position(chunkStart + 8) // lewatin ResChunk_header (type,headerSize,size) — posisi ByteBuffer.wrap(arr,off,len) itu ABSOLUT ke arr, bukan relatif ke off
        val stringCount = p.int
        p.int // styleCount — diabaikan
        val flags = p.int
        val stringsStart = p.int
        p.int // stylesStart — diabaikan
        val isUtf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = p.int

        val result = ArrayList<String>(stringCount)
        val dataBase = chunkStart + stringsStart
        for (i in 0 until stringCount) {
            val strOffset = dataBase + offsets[i]
            if (strOffset < 0 || strOffset >= bytes.size) { result.add(""); continue }
            result.add(
                if (isUtf8) readUtf8String(bytes, strOffset)
                else readUtf16String(bytes, strOffset)
            )
        }
        return result
    }

    private fun readUtf16String(bytes: ByteArray, offset: Int): String {
        // Length prefix 1x u16 (cukup buat semua string manifest praktis — semua jauh < 32767 char).
        if (offset + 2 > bytes.size) return ""
        val len = ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8))
        val charLen = len and 0x7FFF
        val start = offset + 2
        val byteLen = charLen * 2
        if (start + byteLen > bytes.size) return ""
        val sb = StringBuilder(charLen)
        var i = start
        var remaining = charLen
        while (remaining > 0) {
            val c = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(c.toChar())
            i += 2
            remaining--
        }
        return sb.toString()
    }

    private fun readUtf8String(bytes: ByteArray, offset: Int): String {
        // Dua length prefix (UTF-16 char count lalu UTF-8 byte count), masing-masing 1-2 byte
        // (high bit di byte pertama nandain "ada byte kedua"). Kita cuma butuh yang kedua (byte count).
        var pos = offset
        fun readLen(): Int {
            if (pos >= bytes.size) return 0
            val first = bytes[pos].toInt() and 0xFF
            pos++
            return if (first and 0x80 != 0) {
                if (pos >= bytes.size) return 0
                val second = bytes[pos].toInt() and 0xFF
                pos++
                ((first and 0x7F) shl 8) or second
            } else first
        }
        readLen() // char count — diabaikan
        val byteLen = readLen()
        if (pos + byteLen > bytes.size || byteLen < 0) return ""
        return String(bytes, pos, byteLen, Charsets.UTF_8)
    }
}
