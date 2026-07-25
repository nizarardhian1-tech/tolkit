package com.mondns.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Menjalankan proses konversi file di foreground service, supaya:
 *  - Tidak dibunuh sistem saat user pindah ke app lain (unlike thread biasa di Fragment).
 *  - User tetap bisa lihat progres lewat notifikasi walau tidak sedang membuka MonToolkit.
 *
 * Progress & hasil akhir juga di-broadcast secara lokal, supaya Fragment (kalau sedang
 * terbuka) bisa update UI in-app secara real-time.
 */
class ConversionService : Service() {

    companion object {
        const val CHANNEL_ID = "conversion_channel"
        const val NOTIF_ID = 1001

        const val ACTION_PROGRESS = "com.mondns.app.CONVERSION_PROGRESS"
        const val ACTION_COMPLETE = "com.mondns.app.CONVERSION_COMPLETE"
        const val ACTION_ERROR = "com.mondns.app.CONVERSION_ERROR"

        const val EXTRA_PERCENT = "percent"
        const val EXTRA_OUTPUT_NAME = "output_name"
        const val EXTRA_OUTPUT_NAME_2 = "output_name_2"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_MESSAGE = "message"

        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        const val EXTRA_ARRAY_NAME = "array_name"
        const val EXTRA_FORMAT = "format" // name of ConverterEngine.OutputFormat
        const val EXTRA_IS_ZIP = "is_zip"

        // Mode enkripsi: lihat DevConverterFragment.EncryptMode untuk versi UI-nya.
        const val EXTRA_ENCRYPT_MODE = "encrypt_mode"
        const val MODE_OFF = "OFF"
        const val MODE_THEN_CONVERT = "THEN_CONVERT" // enkripsi -> convert ke protected_lib_*.h
        const val MODE_ONLY = "ONLY" // enkripsi doang -> .enc + keyinfo.txt, gak ada convert
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.conv_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.conv_notif_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(fileName: String, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.conv_notif_title_progress))
            .setContentText(getString(R.string.conv_notif_text_progress, fileName, percent))
            .setSmallIcon(R.drawable.ic_code_convert)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildDoneNotification(outputName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.conv_notif_title_done))
            .setContentText(getString(R.string.conv_notif_text_done, outputName))
            .setSmallIcon(R.drawable.ic_code_convert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildErrorNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.conv_notif_title_error))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_code_convert)
            .setAutoCancel(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Kalau user swipe app dari Recent Apps sebelum thread konversi sempat
    // memanggil stopForeground() di blok finally, notifikasi "ongoing" bisa
    // nyangkut selamanya karena prosesnya keburu dikill sistem. onTaskRemoved
    // dipanggil Android SEBELUM proses benar-benar mati, jadi ini kesempatan
    // terakhir buat bersih-bersih: cancel notifikasi & hentikan service.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val inputPath = intent?.getStringExtra(EXTRA_INPUT_PATH)
        val outputDirPath = intent?.getStringExtra(EXTRA_OUTPUT_DIR)
        val arrayName = intent?.getStringExtra(EXTRA_ARRAY_NAME) ?: "embedded_data"
        val formatName = intent?.getStringExtra(EXTRA_FORMAT)
        val isZip = intent?.getBooleanExtra(EXTRA_IS_ZIP, false) ?: false
        val encryptMode = intent?.getStringExtra(EXTRA_ENCRYPT_MODE) ?: MODE_OFF

        if (inputPath == null || outputDirPath == null || formatName == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val inputFile = File(inputPath)
        val outputDir = File(outputDirPath)
        val format = ConverterEngine.OutputFormat.valueOf(formatName)

        startForeground(NOTIF_ID, buildProgressNotification(inputFile.name, 0))

        thread {
            try {
                val onProgress: (Int) -> Unit = { percent ->
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIF_ID, buildProgressNotification(inputFile.name, percent))

                    val broadcast = Intent(ACTION_PROGRESS).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PERCENT, percent)
                    }
                    sendBroadcast(broadcast)
                }

                var doneName = ""
                var doneName2 = ""
                when (encryptMode) {
                    MODE_THEN_CONVERT -> {
                        runEncryptThenConvertFlow(inputFile, outputDir, onProgress)
                        doneName = "protected_lib_data.h + protected_lib_key.h"
                    }
                    MODE_ONLY -> {
                        val (encName, keyInfoName) = runEncryptOnlyFlow(inputFile, outputDir, onProgress)
                        doneName = encName
                        doneName2 = keyInfoName
                    }
                    else -> {
                        val ext = if (format == ConverterEngine.OutputFormat.BASE64_TEXT) ".txt" else ".h"
                        val outFile = File(outputDir, "${arrayName}_out$ext")

                        when {
                            isZip && format == ConverterEngine.OutputFormat.HEX_ARRAY ->
                                ConverterEngine.convertZipToHexHeader(inputFile, outFile, onProgress)
                            isZip && format == ConverterEngine.OutputFormat.BASE64_HEADER ->
                                ConverterEngine.convertZipToBase64Header(inputFile, outFile, onProgress)
                            isZip ->
                                throw IllegalArgumentException(getString(R.string.conv_error_zip_base64text))
                            format == ConverterEngine.OutputFormat.HEX_ARRAY ->
                                ConverterEngine.convertToHexArray(inputFile, outFile, arrayName, onProgress)
                            format == ConverterEngine.OutputFormat.BASE64_HEADER ->
                                ConverterEngine.convertToBase64Header(inputFile, outFile, arrayName, onProgress)
                            else ->
                                ConverterEngine.convertToBase64Text(inputFile, outFile, onProgress)
                        }
                        doneName = outFile.name
                    }
                }

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIF_ID, buildDoneNotification(doneName))

                val doneBroadcast = Intent(ACTION_COMPLETE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_OUTPUT_NAME, doneName)
                    putExtra(EXTRA_OUTPUT_NAME_2, doneName2)
                    putExtra(EXTRA_OUTPUT_PATH, outputDir.absolutePath)
                    putExtra(EXTRA_ENCRYPT_MODE, encryptMode)
                }
                sendBroadcast(doneBroadcast)
            } catch (t: Throwable) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIF_ID, buildErrorNotification(t.message ?: "Unknown error"))

                val errorBroadcast = Intent(ACTION_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_MESSAGE, t.message ?: "Unknown error")
                }
                sendBroadcast(errorBroadcast)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    // Mode THEN_CONVERT: .so mentah -> SoEncryptor (.enc, AES-256-CTR) -> convert .enc ke
    // protected_lib_data.h + convert blob key/IV ke protected_lib_key.h.
    // Nama file & nama array output SENGAJA di-hardcode supaya persis sama dengan yang
    // di-include loader.cpp (lihat proyek loader_so_standalone), jadi user tinggal
    // copy-paste kedua file ini ke folder jni/ tanpa perlu edit nama apapun.
    private fun runEncryptThenConvertFlow(soFile: File, outputDir: File, onProgress: (Int) -> Unit) {
        val tempEnc = File(cacheDir, "${soFile.nameWithoutExtension}.enc")
        val encResult = SoEncryptor.encrypt(soFile, tempEnc) { p ->
            onProgress(p * 15 / 100) // fase encrypt: 0-15%
        }
        if (!encResult.success) {
            throw IllegalStateException(getString(R.string.conv_encrypt_first_error, encResult.error ?: "unknown"))
        }

        // WAJIB: decrypt ulang & bandingkan SHA-256 dengan plaintext asli. Ini yang nutup
        // celah bug korupsi file besar -- kalau hasil enkripsi ternyata gak identik pas
        // didekripsi ulang, proses BERHENTI DI SINI dengan error jelas, bukan lolos ke
        // header lalu baru ketauan gagal pas testing di HP.
        if (!SoEncryptor.verifyEncryption(encResult)) {
            tempEnc.delete()
            throw IllegalStateException(
                getString(R.string.conv_encrypt_verify_failed)
            )
        }
        onProgress(20)

        val tempBlob = File(cacheDir, "${soFile.nameWithoutExtension}_keyiv.bin")
        if (!SoEncryptor.writeKeyIvBlob(encResult, tempBlob)) {
            throw IllegalStateException(
                getString(R.string.conv_encrypt_first_error, "gagal menulis key/IV blob")
            )
        }

        val dataHeader = File(outputDir, "protected_lib_data.h")
        ConverterEngine.convertToHexArray(tempEnc, dataHeader, "protected_lib_data") { p ->
            onProgress(20 + (p * 70 / 100))
        }

        val keyHeader = File(outputDir, "protected_lib_key.h")
        ConverterEngine.convertToHexArray(tempBlob, keyHeader, "protected_lib_key") { p ->
            onProgress(90 + (p * 10 / 100))
        }

        tempEnc.delete()
        tempBlob.delete()
        onProgress(100)
    }

    // Mode ONLY: .so mentah -> SoEncryptor (.enc) doang, TANPA convert ke array/header apapun.
    // Cocok buat yang mau ship .enc sebagai asset terpisah (bukan embed C array), atau .so-nya
    // kegedean buat hex array (bisa bengkak ~7x). Key AES-256 disimpan dalam bentuk Base64
    // yang gampang dibaca di keyinfo.txt -- BUKAN dalam bentuk header C, karena mode ini
    // memang gak lewat convert sama sekali.
    private fun runEncryptOnlyFlow(soFile: File, outputDir: File, onProgress: (Int) -> Unit): Pair<String, String> {
        val baseName = soFile.name // contoh: "libgame.so"
        val encFile = File(outputDir, "$baseName.enc")
        val encResult = SoEncryptor.encrypt(soFile, encFile) { p ->
            onProgress(p * 85 / 100) // fase encrypt: 0-85%
        }
        if (!encResult.success) {
            throw IllegalStateException(getString(R.string.conv_encrypt_only_error, encResult.error ?: "unknown"))
        }

        // Sama kayak di runEncryptThenConvertFlow -- WAJIB, biar korupsi (kalau ada)
        // ketauan sekarang, bukan pas testing di HP.
        if (!SoEncryptor.verifyEncryption(encResult)) {
            encFile.delete()
            throw IllegalStateException(getString(R.string.conv_encrypt_verify_failed))
        }
        onProgress(90)

        val keyInfoFile = File(outputDir, "${soFile.nameWithoutExtension}.keyinfo.txt")
        keyInfoFile.writeText(
            getString(
                R.string.conv_keyinfo_content,
                encResult.keyBase64 ?: "",
                baseName,
                encFile.name
            )
        )
        onProgress(100)

        return Pair(encFile.name, keyInfoFile.name)
    }
}
