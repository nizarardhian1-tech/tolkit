package com.mondns.app

import com.android.apksig.ApkSigner
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * ApkSignerEngine — sign APK pakai Google apksig (library resmi di balik `apksigner` CLI,
 * sudah ada di dependencies project ini: com.android.tools.build:apksig).
 *
 * Dipilih dibanding ZipSigner ala Lucky Patcher (v1 jar-signing lama) karena apksig
 * support skema v2/v3 (APK Signature Scheme) — wajib biar APK jalan mulus di Android
 * modern; tanpa v2/v3 sebagian OEM/Android versi baru bisa nolak install atau kasih
 * peringatan "app not installed" / unverified.
 */
object ApkSignerEngine {

    /**
     * @param outputFile APK hasil sign
     * @param idsigFile file signature V4 terpisah (App/Play "incremental install" — Android 11+),
     *                  null kalau v4Enabled=false
     */
    data class SignResult(val outputFile: File, val idsigFile: File?)

    /**
     * @param inputApk APK yang mau ditandatangani (APK belum signed, atau re-sign APK yang sudah ada)
     * @param outputApk lokasi hasil APK yang sudah signed
     * @param privateKey private key dari keystore (hasil KeystoreManager.generate / loadPkcs12)
     * @param certChain rantai sertifikat yang cocok dengan privateKey di atas
     * @param minSdkVersion dipakai apksig buat nentuin skema mana yang wajib aktif
     * @param v1Enabled aktifin v1 (jar signing) — perlu buat kompatibilitas Android lama (<7.0)
     * @param v2Enabled aktifin v2 — default true, standar sejak Android 7.0
     * @param v3Enabled aktifin v3 — default true, standar sejak Android 9.0 (support key rotation)
     * @param v4Enabled aktifin v4 — buat incremental install/APK verity (Android 11+). Hasilnya file
     *                  `.idsig` terpisah di sebelah output APK, bukan disisipkan ke dalam APK-nya.
     *                  V4 butuh V2 atau V3 aktif juga (kalau keduanya off, apksig bakal nolak sign).
     *
     * Catatan soal "zipalign": nggak ada parameter terpisah buat itu — apksig otomatis nge-align
     * entry APK (4-byte boundary) sebagai bagian dari proses v2/v3 signing, jadi zipalign manual
     * kayak workflow `apksigner` CLI jaman dulu udah nggak diperlukan lagi di sini.
     */
    fun sign(
        inputApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        certChain: List<X509Certificate>,
        signerName: String = "MonToolkit",
        minSdkVersion: Int = 21,
        v1Enabled: Boolean = true,
        v2Enabled: Boolean = true,
        v3Enabled: Boolean = true,
        v4Enabled: Boolean = false
    ): SignResult {
        val signerConfig = ApkSigner.SignerConfig.Builder(signerName, privateKey, certChain)
            .build()

        outputApk.parentFile?.mkdirs()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(minSdkVersion)
            .setV1SigningEnabled(v1Enabled)
            .setV2SigningEnabled(v2Enabled)
            .setV3SigningEnabled(v3Enabled)
            .setV4SigningEnabled(v4Enabled)
            .build()

        signer.sign()

        val idsigFile = if (v4Enabled) File(outputApk.parentFile, "${outputApk.name}.idsig") else null
        return SignResult(outputApk, idsigFile)
    }
}
