package com.mondns.app

/**
 * Format byte mentah jadi hex dump ala `xxd` (alamat | hex | ascii). Murni tampilan,
 * gak butuh library apapun — datanya sendiri didapat dari [ElfParser.readBytesAt].
 */
object HexDumpUtil {

    fun format(bytes: ByteArray, baseAddress: Long): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val lineLen = minOf(16, bytes.size - i)
            sb.append(String.format("%08X: ", baseAddress + i))

            for (j in 0 until 16) {
                if (j < lineLen) {
                    sb.append(String.format("%02X ", bytes[i + j]))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(" ")
            }

            sb.append(" ")
            for (j in 0 until lineLen) {
                val b = bytes[i + j].toInt() and 0xFF
                sb.append(if (b in 32..126) b.toChar() else '.')
            }
            sb.append("\n")
            i += 16
        }
        return sb.toString()
    }
}
