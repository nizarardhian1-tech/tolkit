package com.mondns.app

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash logger untuk MonToolkit sendiri (bukan untuk lib pihak ketiga — itu
 * tugasnya CrashAnalyzerFragment).
 *
 * Sebelumnya app ini TIDAK punya visibilitas sama sekali kalau dirinya sendiri
 * crash: kalau force-close, satu-satunya cara developer tahu adalah user
 * laporan manual. Sekarang setiap uncaught exception ditulis ke file .txt di
 * folder "MonToolkit/crash_logs" pada penyimpanan internal device — sengaja
 * ditaruh di situ (bukan folder privat app) supaya bisa langsung dibuka /
 * dishare lewat fitur File Manager yang sudah ada di app ini, tanpa perlu
 * bikin UI/viewer baru.
 *
 * Prinsip penting: logger ini TIDAK BOLEH ikut menyebabkan crash baru, dan
 * TIDAK BOLEH menelan/menyembunyikan crash asli. Jadi:
 *  - semua proses tulis file dibungkus try-catch sendiri
 *  - apa pun yang terjadi, default handler tetap selalu dipanggil di akhir,
 *    supaya perilaku sistem (dialog "App telah berhenti", proses kill, dsb)
 *    tetap normal seperti biasa kalau tidak ada logger ini sama sekali.
 */
object CrashLogger {

    private const val FOLDER_NAME = "MonToolkit/crash_logs"
    private const val MAX_LOGS = 30

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeLog(appContext, thread, throwable)
            } catch (loggingError: Throwable) {
                // Sengaja dibiarkan diam: kalau logger sendiri gagal (misal storage
                // penuh / permission dicabut), jangan sampai menutupi crash asli.
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    private fun writeLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = resolveLogDir(context)
        if (!dir.exists()) dir.mkdirs()
        pruneOldLogs(dir)

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$fileTimestamp.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("=== MonToolkit Crash Log ===")
        pw.println("Waktu       : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        pw.println("App version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        pw.println("Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("Android     : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("Thread      : ${thread.name}")
        pw.println()
        throwable.printStackTrace(pw)
        pw.flush()

        file.writeText(sw.toString())
    }

    /**
     * Prioritaskan folder di penyimpanan internal shared (browsable via File
     * Manager). Kalau karena suatu hal tidak bisa ditulis (permission storage
     * belum diberikan misalnya), fallback ke folder privat app (filesDir) yang
     * selalu bisa ditulis tanpa izin apa pun — supaya log tetap tersimpan.
     */
    private fun resolveLogDir(context: Context): File {
        return try {
            val external = Environment.getExternalStorageDirectory()
            val candidate = File(external, FOLDER_NAME)
            if (external != null && external.canWrite()) candidate
            else File(context.filesDir, "crash_logs")
        } catch (e: Exception) {
            File(context.filesDir, "crash_logs")
        }
    }

    private fun pruneOldLogs(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size >= MAX_LOGS) {
            files.take(files.size - MAX_LOGS + 1).forEach { it.delete() }
        }
    }
}
