package com.mondns.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export hasil parsing ELF (.so) ke file teks biasa. Berguna untuk lib dengan
 * puluhan-ribu simbol yang lebih enak dibaca/di-grep lewat teks editor / desktop
 * daripada discroll satu-satu di dalam app.
 */
object ExportUtils {

    // Batas byte per-fungsi yang di-dump ke hex, biar file export gak jebol buat .so
    // dengan ribuan simbol (mis. libil2cpp.so). Fungsi yang lebih besar dari ini akan
    // tetap di-dump tapi dipotong, dengan catatan di akhir blok.
    private const val MAX_HEX_BYTES_PER_FUNCTION = 4096

    fun exportElfInfoToFile(
        context: Context,
        info: ElfParser.ElfInfo,
        displayName: String,
        sourceFilePath: String? = null,
        includeHexDump: Boolean = true
    ): File {
        val outDir = File(android.os.Environment.getExternalStorageDirectory(), "MonToolKit/Inspector")
        if (!outDir.exists()) outDir.mkdirs()

        val safeName = displayName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "${safeName}_dump_$timestamp.txt")

        fun hex(v: Long) = "0x${v.toString(16).uppercase()}"

        // File sumber buat baca hex dump per-fungsi. Kalau null/gak ada/gak valid,
        // hex dump otomatis dilewati (fallback ke perilaku lama: cuma metadata + symbol list).
        val sourceFile = sourceFilePath?.let { File(it) }?.takeIf { it.exists() }
        val hexDumpEnabled = includeHexDump && sourceFile != null

        outFile.bufferedWriter().use { w ->
            w.write("MonToolkit — SO Inspector Dump\n")
            w.write("================================\n")
            w.write("File            : $displayName\n")
            w.write("SO Name         : ${info.soName ?: "-"}\n")
            w.write("Architecture    : ${info.architecture} (${if (info.is64Bit) "64-bit" else "32-bit"})\n")
            w.write("Entry point     : ${hex(info.entryPoint)}\n")
            w.write("Debug symbols   : ${if (info.isStripped) "Stripped" else "Not stripped"}\n")
            w.write("Build ID        : ${info.buildId ?: "-"}\n")
            w.write("SHA-256         : ${info.sha256 ?: "-"}\n")
            w.write("File size       : ${info.fileSize} bytes (${info.fileSize / 1024} KB)\n")
            w.write(
                "Hex dump        : ${
                    if (hexDumpEnabled) "Included per-function (max $MAX_HEX_BYTES_PER_FUNCTION bytes/fn)"
                    else "Not included (file mentah gak ke-cache/gak ditemukan)"
                }\n"
            )
            w.write("\n")

            w.write("Security / Hardening\n")
            w.write("--------------------------------\n")
            val nx = when (info.hasNxStack) { true -> "Yes"; false -> "No"; null -> "Unknown" }
            w.write("NX (non-exec stack) : $nx\n")
            w.write("RELRO                : ${info.relro}\n")
            w.write("Stack Canary         : ${if (info.hasStackCanary) "Yes" else "No"}\n")
            w.write("FORTIFY_SOURCE       : ${if (info.hasFortify) "Yes" else "No"}\n")
            if (info.symbolTableTruncated) {
                w.write("NOTE                 : Sebagian section dilewati karena berukuran tidak wajar/rusak.\n")
            }
            w.write("\n")

            w.write("Needed Libraries (${info.neededLibraries.size})\n")
            w.write("--------------------------------\n")
            if (info.neededLibraries.isEmpty()) {
                w.write("(tidak ada)\n")
            } else {
                info.neededLibraries.forEach { w.write("$it\n") }
            }
            w.write("\n")

            // Kelompokkan exported functions per namespace/class (dari hasil demangle) —
            // mirip konvensi dumper IL2CPP pada umumnya, tapi info per fungsi lebih lengkap:
            // RVA, FILE OFFSET (posisi asli di file .so), Size, dan Section-nya.
            w.write("=== Exported / Defined Functions (${info.definedSymbols.size}) ===\n\n")
            val grouped = info.definedSymbols
                .sortedWith(compareBy({ it.className ?: "" }, { it.address }))
                .groupBy { it.className }

            val orderedGroups = grouped.entries.sortedBy { it.key ?: "" }
            for ((className, symbols) in orderedGroups) {
                val header = className ?: "Global Functions (no class / C-style)"
                w.write("namespace/class: $header\n")
                w.write("-".repeat(minOf(60, header.length + 16)) + "\n")
                symbols.forEach { sym ->
                    val offsetStr = if (sym.fileOffset >= 0) hex(sym.fileOffset) else "-"
                    val section = sym.sectionName?.takeIf { it.isNotBlank() } ?: "-"
                    w.write("    ${sym.displayName}\n")
                    w.write("    // RVA: ${hex(sym.address)} | Offset: $offsetStr | Size: ${hex(sym.size)} | Section: $section\n")
                    if (sym.demangledName != null) {
                        w.write("    // raw: ${sym.name}\n")
                    }
                    if (hexDumpEnabled && sym.fileOffset >= 0 && sym.size > 0) {
                        val lengthToRead = sym.size.coerceAtMost(MAX_HEX_BYTES_PER_FUNCTION.toLong())
                        val bytes = ElfParser.readBytesAt(sourceFile!!, sym.fileOffset, lengthToRead)
                        if (bytes != null && bytes.isNotEmpty()) {
                            w.write("    --- hex dump ---\n")
                            HexDumpUtil.format(bytes, sym.address).lineSequence().forEach { line ->
                                if (line.isNotEmpty()) w.write("    $line\n")
                            }
                            if (sym.size > MAX_HEX_BYTES_PER_FUNCTION) {
                                w.write("    // ... dipotong, fungsi asli ${hex(sym.size)} byte\n")
                            }
                        }
                    }
                    w.write("\n")
                }
            }

            w.write("=== Imported / Undefined Functions (${info.undefinedSymbols.size}) ===\n\n")
            info.undefinedSymbols.forEach { sym ->
                if (sym.demangledName != null) {
                    w.write("${sym.demangledName}\t(raw: ${sym.name})\n")
                } else {
                    w.write("${sym.name}\n")
                }
            }
        }

        return outFile
    }

    fun shareTextFile(context: Context, file: File) {
        shareFile(context, file, "text/plain")
    }

    /** Sama seperti [shareTextFile] tapi untuk file apa pun (mis. .so hasil strip) dengan mime-type yang sesuai. */
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }
}
