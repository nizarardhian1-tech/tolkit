package com.mondns.app

import java.io.File

/**
 * ApkDiffEngine — bandingin dua APK (misal versi lama vs baru, atau original vs hasil
 * patch) dari sisi keamanan/konfigurasi. Sengaja nggak nulis ulang logika scan; cukup
 * panggil SecurityScannerEngine.scan() dua kali lalu diff dua ScanReport-nya — jadi
 * otomatis ikut kebagian semua analisis yang ada di sana (manifest, SDK, native lib, dst)
 * tanpa duplikasi kode.
 */
object ApkDiffEngine {

    data class DiffResult(
        val nameA: String,
        val nameB: String,
        val versionA: String?,
        val versionB: String?,
        val permissionsAdded: List<String>,
        val permissionsRemoved: List<String>,
        val sdksAdded: List<String>,
        val sdksRemoved: List<String>,
        val nativeLibsAdded: List<String>,
        val nativeLibsRemoved: List<String>,
        val certSha256A: String?,
        val certSha256B: String?,
        val certChanged: Boolean,
        val debuggableA: Boolean?,
        val debuggableB: Boolean?,
        val allowBackupA: Boolean?,
        val allowBackupB: Boolean?,
        val exportedNoPermissionAdded: List<String>
    )

    fun diff(apkA: File, apkB: File): DiffResult {
        val reportA = SecurityScannerEngine.scan(apkA)
        val reportB = SecurityScannerEngine.scan(apkB)

        val permsA = reportA.allPermissions.toSet()
        val permsB = reportB.allPermissions.toSet()
        val sdksA = reportA.detectedSdks.toSet()
        val sdksB = reportB.detectedSdks.toSet()
        val libsA = reportA.nativeLibs.map { it.fileName }.toSet()
        val libsB = reportB.nativeLibs.map { it.fileName }.toSet()
        val exportedA = reportA.manifest?.exportedWithoutPermission?.toSet() ?: emptySet()
        val exportedB = reportB.manifest?.exportedWithoutPermission?.toSet() ?: emptySet()

        val versionA = reportA.manifest?.let { "${it.versionName ?: "?"} (${it.versionCode ?: "?"})" }
        val versionB = reportB.manifest?.let { "${it.versionName ?: "?"} (${it.versionCode ?: "?"})" }

        return DiffResult(
            nameA = reportA.apkName,
            nameB = reportB.apkName,
            versionA = versionA,
            versionB = versionB,
            permissionsAdded = (permsB - permsA).sorted(),
            permissionsRemoved = (permsA - permsB).sorted(),
            sdksAdded = (sdksB - sdksA).sorted(),
            sdksRemoved = (sdksA - sdksB).sorted(),
            nativeLibsAdded = (libsB - libsA).sorted(),
            nativeLibsRemoved = (libsA - libsB).sorted(),
            certSha256A = reportA.signing?.certSha256,
            certSha256B = reportB.signing?.certSha256,
            certChanged = reportA.signing?.certSha256 != null &&
                reportB.signing?.certSha256 != null &&
                reportA.signing.certSha256 != reportB.signing.certSha256,
            debuggableA = reportA.manifest?.debuggable,
            debuggableB = reportB.manifest?.debuggable,
            allowBackupA = reportA.manifest?.allowBackup,
            allowBackupB = reportB.manifest?.allowBackup,
            exportedNoPermissionAdded = (exportedB - exportedA).sorted()
        )
    }
}
