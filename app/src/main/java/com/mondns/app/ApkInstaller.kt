package com.mondns.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

/**
 * ApkInstaller — titik pusat untuk semua urusan pasang-memasang APK di MonToolkit.
 * Dipakai oleh File Manager (buka file .apk/.apks langsung) dan Xpatch (install hasil patch).
 *
 * Kenapa disatukan di sini: supaya perilaku "APK yang sama tapi signature beda" konsisten
 * di seluruh aplikasi — dicek DULU sebelum dilempar ke installer sistem, bukan nunggu
 * user dapet error mentah "App not installed".
 */
object ApkInstaller {

    fun authorityFor(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Dialog peringatan bentrok signature, gaya "Warning" 3-tombol (Install Langsung /
     * Batal / Uninstall dulu baru Install) — dipakai bareng oleh File Manager & Xpatch
     * supaya perilakunya konsisten di seluruh app.
     */
    fun showConflictDialog(
        context: Context,
        conflictPackage: String,
        onInstallDirectly: () -> Unit,
        onUninstallThenInstall: () -> Unit
    ) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.fm_install_conflict_title))
            .setMessage(context.getString(R.string.fm_install_conflict_detail, conflictPackage))
            .setNegativeButton(context.getString(R.string.fm_install_conflict_direct)) { _, _ -> onInstallDirectly() }
            .setNeutralButton(context.getString(R.string.fm_cancel), null)
            .setPositiveButton(context.getString(R.string.fm_install_conflict_uninstall)) { _, _ -> onUninstallThenInstall() }
            .show()
    }

    /** Deteksi apakah file ini kemungkinan besar adalah split APK set (dari APKMirror/APKPure dkk). */
    fun isSplitApkSet(file: File): Boolean {
        val name = file.name.lowercase()
        if (!name.endsWith(".apks") && !name.endsWith(".xapk")) return false
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Cek apakah APK ini akan BENTROK kalau langsung dipasang: package name sama dengan
     * yang sudah terinstall, tapi signature-nya beda (kasus paling umum: hasil re-patch
     * dari APK yang sama, ditandatangani ulang dengan key debug MonToolkit).
     *
     * Return nama package yang bentrok, atau null kalau aman dipasang langsung.
     *
     * CATATAN PENTING: awalnya fungsi ini pakai PackageManager.getPackageArchiveInfo() buat
     * baca signature APK yang belum terinstall — ternyata itu TERBUKTI tidak reliable di
     * banyak device (signingInfo/signatures balik null walau APK-nya sah), jadi konflik luput
     * kedeteksi dan user malah nabrak dialog "update" bawaan sistem yang berujung gagal install
     * mentah. Sekarang certificate APK baru dibaca LANGSUNG dari isi ZIP-nya (cara yang dipakai
     * installer pihak ketiga yang lebih robust), sama sekali tanpa lewat PackageManager.
     */
    @Suppress("DEPRECATION")
    fun findSignatureConflict(context: Context, apkFile: File): String? {
        val pm = context.packageManager

        val packageName = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
        } catch (_: Exception) {
            null
        } ?: return null

        val installedCert = readCertOnly(pm) {
            try {
                pm.getPackageInfo(packageName, it)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        } ?: return null // belum pernah terpasang -> aman

        val newCert = readCertFromApkFile(apkFile) ?: return null

        return if (!newCert.contentEquals(installedCert)) packageName else null
    }

    /**
     * Baca certificate signing APK pakai ApkVerifier dari library apksig (Google, resmi) —
     * ini cover ketiga skema signing (V1/JAR, V2, V3) sekaligus, sama persis kayak yang
     * dipakai Android sendiri buat verifikasi install. Jauh lebih akurat dibanding baca
     * manual lewat JarFile (yang cuma jalan kalau APK-nya pakai skema V1/JAR) atau lewat
     * PackageManager.getPackageArchiveInfo() (sering null buat APK yang belum terinstall).
     *
     * CATATAN: fungsi ini baca & hash SELURUH isi file APK, jadi WAJIB dipanggil dari
     * background thread — jangan dari main thread, bisa bikin UI freeze/ANR buat APK besar.
     */
    private fun readCertFromApkFile(apkFile: File): ByteArray? {
        return try {
            val result = com.android.apksig.ApkVerifier.Builder(apkFile).build().verify()
            result.signerCertificates?.firstOrNull()?.encoded
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readCertOnly(
        pm: PackageManager,
        fetch: (Int) -> android.content.pm.PackageInfo?
    ): ByteArray? {
        var info = fetch(PackageManager.GET_SIGNING_CERTIFICATES)
        var cert = firstCertBytes(info?.signingInfo)

        if (cert == null) {
            info = fetch(PackageManager.GET_SIGNATURES)
            val sigs = info?.signatures
            cert = if (!sigs.isNullOrEmpty()) sigs[0]?.toByteArray() else null
        }
        return cert
    }

    private fun firstCertBytes(signingInfo: android.content.pm.SigningInfo?): ByteArray? {
        signingInfo ?: return null
        return if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            signingInfo.signingCertificateHistory?.firstOrNull()?.toByteArray()
        }
    }

    /** Intent buat minta user uninstall package tertentu (butuh konfirmasi sistem). */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }

    /** Intent buat pasang satu file .apk lewat installer sistem (via FileProvider). */
    fun installSingleApkIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, authorityFor(context), apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Pasang file .apks/.xapk (split APK set) lewat PackageInstaller session:
     * tiap entry .apk di dalam zip ditulis sebagai satu split dalam satu sesi install.
     * Harus dipanggil dari background thread (I/O berat).
     */
    fun installSplitApkSet(context: Context, file: File, onResult: (Boolean, String?) -> Unit) {
        var session: PackageInstaller.Session? = null
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)

            val entries = ZipFile(file).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".apk", ignoreCase = true) }.toList()
                    .also { list ->
                        for (entry in list) {
                            zip.getInputStream(entry).use { input ->
                                session.openWrite(entry.name, 0, entry.size).use { out ->
                                    input.copyTo(out)
                                    session.fsync(out)
                                }
                            }
                        }
                    }
            }

            if (entries.isEmpty()) {
                session.abandon()
                onResult(false, "Tidak ditemukan file .apk di dalam paket ini")
                return
            }

            session.commit(buildStatusIntentSender(context, sessionId))
            onResult(true, null)
        } catch (e: Exception) {
            try { session?.abandon() } catch (_: Exception) {}
            onResult(false, e.message)
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }

    private fun buildStatusIntentSender(context: Context, sessionId: Int): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_INSTALL_STATUS
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
        return pendingIntent.intentSender
    }
}