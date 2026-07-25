package com.mondns.app

import java.io.File

object StringsExtractor {
    /**
     * Membaca file mentah dan mengekstrak semua rentetan karakter ASCII yang bisa dibaca.
     */
    fun extract(file: File, originalName: String, minLength: Int = 5): File {
        val outDir = File(android.os.Environment.getExternalStorageDirectory(), "MonToolKit/Inspector")
        if (!outDir.exists()) outDir.mkdirs()

        // Gunakan nama file asli (misal libil2cpp.so -> libil2cpp)
        val safeName = originalName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
        val outFile = File(outDir, "${safeName}_strings.txt")

        outFile.bufferedWriter().use { writer ->
            writer.write("MonToolkit — Strings Extractor\n")
            writer.write("File: $originalName\n")
            writer.write("================================\n\n")

            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                val currentString = java.lang.StringBuilder()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead) {
                        val b = buffer[i].toInt()
                        // Cek apakah byte adalah karakter ASCII yang bisa diprint
                        if (b in 32..126) {
                            currentString.append(b.toChar())
                        } else {
                            if (currentString.length >= minLength) {
                                writer.write(currentString.toString())
                                writer.write("\n")
                            }
                            currentString.setLength(0) // Reset buffer
                        }
                    }
                }
                // Tulis sisa terakhir jika ada
                if (currentString.length >= minLength) {
                    writer.write(currentString.toString())
                    writer.write("\n")
                }
            }
        }
        return outFile
    }
}