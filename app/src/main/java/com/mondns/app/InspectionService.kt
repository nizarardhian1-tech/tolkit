package com.mondns.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Menjalankan parsing ELF (.so) di foreground service, sama seperti ConversionService,
 * supaya:
 *  - File besar (IL2CPP/Unity, sering 100MB+) tetap diproses walau user pindah ke app lain
 *    — bukan cuma thread biasa yang bisa kena throttle/kill saat di-background.
 *  - User dapet notifikasi begitu selesai, gak perlu nungguin app kebuka terus.
 *
 * Hasilnya (ElfInfo) disimpan di companion object (in-memory) karena isinya bisa ribuan
 * simbol — terlalu besar buat lewat Intent extra / Binder. Aman karena Service ini jalan
 * di process yang sama dengan Fragment yang minta.
 */
class InspectionService : Service() {

    companion object {
        const val CHANNEL_ID = "inspection_channel"
        const val NOTIF_ID = 2001

        const val ACTION_COMPLETE = "com.mondns.app.INSPECTION_COMPLETE"
        const val ACTION_ERROR = "com.mondns.app.INSPECTION_ERROR"

        const val EXTRA_URI = "uri"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_SIZE_BYTES = "size_bytes"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_MESSAGE = "message"

        @Volatile var isRunning: Boolean = false
        @Volatile var runningRequestId: Long = -1L

        @Volatile var lastRequestId: Long = -1L
        @Volatile var lastResult: ElfParser.ElfInfo? = null
        @Volatile var lastErrorMessage: String? = null
        @Volatile var lastDisplayName: String = ""
        @Volatile var lastSizeBytes: Long = 0L
        // Path file cache HASIL COPY dari Uri — SENGAJA gak dihapus setelah parsing selesai
        // (beda dari sebelumnya), karena Hex Dump & Disassembler butuh baca ulang byte
        // mentah di offset tertentu. Dibersihkan otomatis begitu inspeksi BARU dimulai.
        @Volatile var lastCachedFilePath: String? = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.inspect_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.inspect_notif_channel_desc) }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(fileName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.inspect_notif_title_progress))
            .setContentText(getString(R.string.inspect_notif_text_progress, fileName))
            .setSmallIcon(R.drawable.ic_inspector)
            .setProgress(0, 0, true) // indeterminate — parsing ELF gak punya % yang natural
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildDoneNotification(fileName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.inspect_notif_title_done))
            .setContentText(getString(R.string.inspect_notif_text_done, fileName))
            .setSmallIcon(R.drawable.ic_inspector)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildErrorNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.inspect_notif_title_error))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_inspector)
            .setAutoCancel(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // SENGAJA tidak override onTaskRemoved di sini (beda dari ConversionService) — justru
    // tujuannya biar proses TETAP jalan walau user pindah/swipe ke app lain, karena file
    // besar bisa makan waktu dan itu yang diminta.

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        @Suppress("DEPRECATION")
        val uri: Uri? = intent?.getParcelableExtra(EXTRA_URI)
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "file.so"
        val sizeBytes = intent?.getLongExtra(EXTRA_SIZE_BYTES, 0L) ?: 0L
        val requestId = intent?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L

        if (uri == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        isRunning = true
        runningRequestId = requestId
        startForeground(NOTIF_ID, buildProgressNotification(displayName))

        // Bersihkan cache file dari inspeksi SEBELUMNYA (kalau ada) — supaya gak numpuk file
        // besar di cache tiap kali user inspect .so baru.
        lastCachedFilePath?.let { oldPath -> File(oldPath).takeIf { it.exists() }?.delete() }
        lastCachedFilePath = null

        thread {
            var errorMessage: String? = null
            var info: ElfParser.ElfInfo? = null
            var cacheFile: File? = null
            try {
                // Streaming copy Uri -> cache file (buffer kecil & tetap konstan meski
                // filenya ratusan MB). INI YANG BEDA dari sebelumnya: dulu pakai
                // `inputStream.readBytes()` yang me-load SELURUH file ke satu ByteArray
                // di RAM sekaligus — itu penyebab OOM untuk lib besar seperti IL2CPP/Unity.
                val tmp = File(cacheDir, "inspect_${requestId}.so")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                } ?: run { errorMessage = getString(R.string.inspector_error_read) }

                if (errorMessage == null) {
                    cacheFile = tmp
                    info = ElfParser.parse(tmp)
                    if (!info!!.isValid) errorMessage = info?.error ?: getString(R.string.inspector_error_invalid)
                }
            } catch (oom: OutOfMemoryError) {
                errorMessage = getString(R.string.inspector_error_oom)
            } catch (e: Exception) {
                errorMessage = e.message ?: getString(R.string.inspector_error_read)
            }
            // CATATAN: cacheFile TIDAK dihapus di sini kalau parsing sukses — beda dari
            // sebelumnya. Dibutuhkan tetap ada di disk buat fitur Hex Dump & Disassembler
            // (baca ulang byte mentah per-fungsi). Kalau parsing GAGAL, hapus karena gak
            // akan dipakai.
            if (errorMessage != null) {
                cacheFile?.delete()
            } else {
                lastCachedFilePath = cacheFile?.absolutePath
            }

            isRunning = false
            lastRequestId = requestId
            lastDisplayName = displayName
            lastSizeBytes = sizeBytes

            val manager = getSystemService(NotificationManager::class.java)

            if (errorMessage != null) {
                val msg = errorMessage!!
                lastResult = null
                lastErrorMessage = msg
                manager.notify(NOTIF_ID, buildErrorNotification(msg))
                sendBroadcast(Intent(ACTION_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_REQUEST_ID, requestId)
                    putExtra(EXTRA_MESSAGE, msg)
                })
            } else {
                lastResult = info
                lastErrorMessage = null
                manager.notify(NOTIF_ID, buildDoneNotification(displayName))
                sendBroadcast(Intent(ACTION_COMPLETE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_REQUEST_ID, requestId)
                })
            }

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }
}
