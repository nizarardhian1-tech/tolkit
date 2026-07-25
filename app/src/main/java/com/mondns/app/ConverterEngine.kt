package com.mondns.app

import android.util.Base64
import android.util.Base64OutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Semua logic konversi file -> C++ array / Base64 ada di sini, terpisah dari UI/Fragment.
 * Dipanggil dari [ConversionService] supaya proses tetap jalan walau Activity/Fragment
 * sedang tidak terlihat (app di-background).
 *
 * Progress callback dipanggil dengan nilai 0..100. Implementasi menahan diri untuk
 * hanya memanggil callback saat persentase berubah, supaya tidak membanjiri
 * NotificationManager dengan update yang terlalu sering.
 */
object ConverterEngine {

    // Format daftar file yang didukung fitur ini. Item baru cukup ditambahkan di sini.
    val SUPPORTED_EXTENSIONS = setOf(
        // Native library & binary umum
        "so", "bin", "dat", "dex", "jar",
        // Paket aplikasi & arsip
        "apk", "zip",
        // Gambar
        "png", "jpg", "jpeg", "webp", "gif", "bmp",
        // Audio
        "mp3", "wav", "ogg", "m4a",
        // Video
        "mp4", "mov", "avi", "mkv",
        // Font & data teks
        "ttf", "otf", "json", "txt", "xml"
    )

    fun isSupported(file: File): Boolean =
        file.extension.lowercase() in SUPPORTED_EXTENSIONS

    /**
     * Estimasi ukuran file HASIL konversi. Penting buat cek disk space dulu SEBELUM mulai —
     * format Hex Array bisa membengkak ~6-7x dari ukuran file asli (tiap byte jadi teks
     * "0xXX, " = 6 karakter), jadi file .so 100MB bisa jadi header .h 600-700MB. Base64
     * jauh lebih hemat, cuma ~1.37x.
     */
    fun estimateOutputBytes(inputSize: Long, format: OutputFormat): Long {
        return when (format) {
            OutputFormat.HEX_ARRAY -> inputSize * 7 // termasuk newline setiap 12 byte
            OutputFormat.BASE64_HEADER, OutputFormat.BASE64_TEXT -> (inputSize * 137) / 100
        }
    }

    // Lookup table hex 0x00.."0xff" dihitung sekali saja, jauh lebih cepat
    // dibanding memanggil String.format() jutaan kali untuk file besar.
    private val HEX_LOOKUP: Array<String> = Array(256) { i -> String.format("0x%02x, ", i) }

    enum class OutputFormat { HEX_ARRAY, BASE64_HEADER, BASE64_TEXT }

    data class ConversionResult(val outputFile: File, val totalBytes: Long, val entryCount: Int)

    // Nama variabel C/C++ yang valid, dedupe otomatis kalau ada nama file sama
    private fun sanitizeVarName(rawName: String, usedNames: MutableSet<String>): String {
        var name = rawName.replace(Regex("[^A-Za-z0-9_]"), "_")
        if (name.isEmpty() || name[0].isDigit()) name = "_$name"
        var finalName = name
        var counter = 1
        while (!usedNames.add(finalName)) {
            finalName = "${name}_$counter"
            counter++
        }
        return finalName
    }

    // --- SINGLE FILE ---

    fun convertToHexArray(
        inputFile: File,
        outputFile: File,
        arrayName: String,
        onProgress: (Int) -> Unit
    ): ConversionResult {
        val totalSize = inputFile.length().coerceAtLeast(1)
        var lastPercent = -1

        inputFile.inputStream().use { input ->
            outputFile.bufferedWriter(bufferSize = 1 shl 16).use { writer ->
                writer.write("unsigned char $arrayName[] = {\n  ")
                val buffer = ByteArray(1 shl 16)
                var bytesRead: Int
                var totalBytes = 0L
                var columnCount = 0
                val sb = StringBuilder(buffer.size * 6)

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    sb.setLength(0)
                    for (i in 0 until bytesRead) {
                        sb.append(HEX_LOOKUP[buffer[i].toInt() and 0xFF])
                        columnCount++
                        if (columnCount == 12) {
                            sb.append("\n  ")
                            columnCount = 0
                        }
                    }
                    writer.write(sb.toString())
                    writer.flush()
                    totalBytes += bytesRead

                    val percent = ((totalBytes * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
                writer.write("\n};\nunsigned int ${arrayName}_len = $totalBytes;\n")
                writer.flush()
            }
        }
        onProgress(100)
        return ConversionResult(outputFile, inputFile.length(), 1)
    }

    fun convertToBase64Text(inputFile: File, outputFile: File, onProgress: (Int) -> Unit): ConversionResult {
        val totalSize = inputFile.length().coerceAtLeast(1)
        var lastPercent = -1
        inputFile.inputStream().use { input ->
            outputFile.outputStream().use { output ->
                val b64out = Base64OutputStream(output, Base64.NO_WRAP)
                val buffer = ByteArray(1 shl 16)
                var bytesRead: Int
                var totalBytes = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    b64out.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    val percent = ((totalBytes * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
                b64out.close()
            }
        }
        onProgress(100)
        return ConversionResult(outputFile, inputFile.length(), 1)
    }

    fun convertToBase64Header(
        inputFile: File,
        outputFile: File,
        arrayName: String,
        onProgress: (Int) -> Unit
    ): ConversionResult {
        val totalSize = inputFile.length().coerceAtLeast(1)
        var lastPercent = -1
        FileOutputStream(outputFile, false).use { fos ->
            fos.write("const char* $arrayName = \"".toByteArray())
            val b64out = Base64OutputStream(fos, Base64.NO_WRAP)
            inputFile.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var bytesRead: Int
                var totalBytes = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    b64out.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    val percent = ((totalBytes * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            b64out.close()
        }
        FileOutputStream(outputFile, true).use { fos -> fos.write("\";\n".toByteArray()) }
        onProgress(100)
        return ConversionResult(outputFile, inputFile.length(), 1)
    }

    // --- ZIP BATCH: gabung semua isi zip jadi SATU header + tabel lookup ---
    // Pakai ZipFile (bukan ZipInputStream) supaya ukuran tiap entry sudah diketahui
    // di awal, tanpa harus decode dulu, sehingga progress % akurat.

    fun convertZipToHexHeader(zipFile: File, outputFile: File, onProgress: (Int) -> Unit): ConversionResult {
        val usedNames = mutableSetOf<String>()
        data class Entry(val varName: String, val lenName: String, val originalPath: String)
        val entries = mutableListOf<Entry>()

        ZipFile(zipFile).use { zip ->
            val zipEntries = zip.entries().toList().filter { !it.isDirectory }
            val totalSize = zipEntries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var processed = 0L
            var lastPercent = -1

            outputFile.bufferedWriter(bufferSize = 1 shl 16).use { writer ->
                writer.write("// Auto-generated oleh MonToolkit Dev Converter.\n")
                writer.write("// Berisi semua file dari '${zipFile.name}' sebagai unsigned char array.\n\n")
                writer.write("#pragma once\n\n")

                zipEntries.forEach { zipEntry ->
                    val varName = sanitizeVarName(
                        zipEntry.name.substringAfterLast('/').substringBeforeLast('.') +
                            "_" + zipEntry.name.substringAfterLast('.', "bin"),
                        usedNames
                    )
                    val lenName = "${varName}_len"

                    writer.write("static const unsigned char $varName[] = {\n  ")
                    zip.getInputStream(zipEntry).use { entryInput ->
                        val buffer = ByteArray(1 shl 16)
                        var bytesRead: Int
                        var totalBytes = 0L
                        var columnCount = 0
                        val sb = StringBuilder(buffer.size * 6)
                        while (entryInput.read(buffer).also { bytesRead = it } != -1) {
                            sb.setLength(0)
                            for (i in 0 until bytesRead) {
                                sb.append(HEX_LOOKUP[buffer[i].toInt() and 0xFF])
                                columnCount++
                                if (columnCount == 12) {
                                    sb.append("\n  ")
                                    columnCount = 0
                                }
                            }
                            writer.write(sb.toString())
                            writer.flush()
                            totalBytes += bytesRead
                            processed += bytesRead
                            val percent = ((processed * 100) / totalSize).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                        writer.write("\n};\nstatic const unsigned int $lenName = $totalBytes;\n\n")
                    }
                    entries.add(Entry(varName, lenName, zipEntry.name))
                }

                writer.write("struct EmbeddedAsset {\n")
                writer.write("  const char* filename;\n")
                writer.write("  const unsigned char* data;\n")
                writer.write("  unsigned int length;\n")
                writer.write("};\n\n")
                writer.write("static const EmbeddedAsset embeddedAssets[${entries.size}] = {\n")
                entries.forEach { e ->
                    writer.write("  { \"${e.originalPath}\", ${e.varName}, ${e.lenName} },\n")
                }
                writer.write("};\n\n")
                writer.write("static const unsigned int embeddedAssetsCount = ${entries.size};\n")
            }
        }
        onProgress(100)
        return ConversionResult(outputFile, zipFile.length(), entries.size)
    }

    fun convertZipToBase64Header(zipFile: File, outputFile: File, onProgress: (Int) -> Unit): ConversionResult {
        val usedNames = mutableSetOf<String>()
        data class Entry(val varName: String, val originalPath: String)
        val entries = mutableListOf<Entry>()

        ZipFile(zipFile).use { zip ->
            val zipEntries = zip.entries().toList().filter { !it.isDirectory }
            val totalSize = zipEntries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var processed = 0L
            var lastPercent = -1

            outputFile.bufferedWriter(bufferSize = 1 shl 16).use { writer ->
                writer.write("// Auto-generated oleh MonToolkit Dev Converter.\n")
                writer.write("// Berisi semua file dari '${zipFile.name}' sebagai string Base64.\n\n")
                writer.write("#pragma once\n\n")

                zipEntries.forEach { zipEntry ->
                    val varName = sanitizeVarName(
                        zipEntry.name.substringAfterLast('/').substringBeforeLast('.') +
                            "_" + zipEntry.name.substringAfterLast('.', "bin"),
                        usedNames
                    )
                    val bytes = zip.getInputStream(zipEntry).use { it.readBytes() }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    writer.write("static const char* $varName = \"$b64\";\n\n")
                    entries.add(Entry(varName, zipEntry.name))

                    processed += zipEntry.size.coerceAtLeast(0)
                    val percent = ((processed * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }

                writer.write("struct EmbeddedAssetB64 {\n")
                writer.write("  const char* filename;\n")
                writer.write("  const char* base64Data;\n")
                writer.write("};\n\n")
                writer.write("static const EmbeddedAssetB64 embeddedAssetsB64[${entries.size}] = {\n")
                entries.forEach { e -> writer.write("  { \"${e.originalPath}\", ${e.varName} },\n") }
                writer.write("};\n\n")
                writer.write("static const unsigned int embeddedAssetsB64Count = ${entries.size};\n")
            }
        }
        onProgress(100)
        return ConversionResult(outputFile, zipFile.length(), entries.size)
    }
}
