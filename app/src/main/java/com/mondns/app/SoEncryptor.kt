package com.mondns.app

import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SoEncryptor
 * -----------
 * Tool BUILD-TIME untuk mengenkripsi file .so milikmu sendiri sebelum di-ship di APK.
 * Ini BUKAN alat runtime, BUKAN dropper, dan TIDAK menjalankan/mengeksekusi apa pun.
 * Input: file .so plaintext -> Output: file .enc (AES-256-CTR).
 *
 * Format file output (.enc):
 *   [0..16)   = IV / initial counter block (16 byte)
 *   [16..EOF) = ciphertext (sama panjang dengan file .so asli, CTR = stream cipher)
 *
 * PENTING soal key:
 * - Key di-generate RANDOM setiap kali encrypt() dipanggil. Tool ini TIDAK PERNAH
 *   menyimpan key ke disk/log/clipboard sendiri.
 * - Key HANYA muncul di EncryptResult.keyBase64 -- simpan sendiri baik-baik.
 *   Kalau hilang, file .enc yang dihasilkan tidak bisa didekripsi lagi (by design).
 * - Key ini nanti kamu pakai di sisi loader untuk mendekripsi .so tersebut di
 *   memori saat app-mu berjalan.
 *
 * CATATAN PERBAIKAN (streaming): versi sebelumnya baca seluruh file ke satu ByteArray
 * lalu panggil cipher.doFinal(plain) sekali jalan. Untuk file besar (lib IL2Cpp/engine
 * game, puluhan MB) ini terbukti bisa menghasilkan ciphertext yang KORUP di beberapa
 * device/API level -- gejalanya: loader tetap berhasil dlopen (header ELF di awal file
 * masih valid), tapi library-nya gak benar-benar jalan karena isi di bagian tengah/akhir
 * file rusak. Sekarang encrypt() diproses per-chunk lewat Cipher.update(), yang jauh
 * lebih aman untuk buffer besar dan juga hemat memori (gak perlu nampung seluruh file
 * sekaligus di RAM).
 */
object SoEncryptor {

    private const val TRANSFORM = "AES/CTR/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val IV_SIZE_BYTES = 16
    private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024 // 4MB per chunk

    data class EncryptResult(
        val success: Boolean,
        val error: String? = null,
        val outputFile: File? = null,
        val keyBase64: String? = null,
        val originalSizeBytes: Long = 0,
        val encryptedSizeBytes: Long = 0,
        val originalSha256: String? = null // dipakai verifyEncryption() buat cek korupsi
    )

    /**
     * Enkripsi [sourceSo] (.so plaintext, hasil compile NDK biasa) menjadi [outputEnc].
     * Diproses streaming per-chunk (4MB), aman untuk file besar. [onProgress] opsional,
     * dipanggil dengan persentase 0-100 selama proses baca+enkripsi berlangsung.
     */
    fun encrypt(sourceSo: File, outputEnc: File, onProgress: ((Int) -> Unit)? = null): EncryptResult {
        if (!sourceSo.exists()) {
            return EncryptResult(success = false, error = "File source tidak ditemukan: ${sourceSo.path}")
        }
        val totalSize = sourceSo.length()
        if (totalSize == 0L) {
            return EncryptResult(success = false, error = "File source kosong (0 byte).")
        }

        return try {
            // 1. Generate key AES-256 random
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(KEY_SIZE_BITS, SecureRandom())
            val secretKey: SecretKey = keyGen.generateKey()

            // 2. Generate IV / initial counter (16 byte, HARUS unik per file)
            val iv = ByteArray(IV_SIZE_BYTES)
            SecureRandom().nextBytes(iv)

            // 3. Setup cipher CTR mode
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

            // Hash plaintext ASLI sambil jalan (dipakai verifyEncryption() nanti buat
            // mastiin hasil decrypt di loader bakal identik byte-per-byte).
            val digest = MessageDigest.getInstance("SHA-256")

            outputEnc.parentFile?.mkdirs()

            var totalRead = 0L
            outputEnc.outputStream().buffered().use { out ->
                out.write(iv)

                sourceSo.inputStream().buffered().use { input ->
                    val chunk = ByteArray(CHUNK_SIZE_BYTES)
                    while (true) {
                        val n = input.read(chunk)
                        if (n <= 0) break

                        digest.update(chunk, 0, n)

                        val encChunk = cipher.update(chunk, 0, n)
                        if (encChunk != null && encChunk.isNotEmpty()) {
                            out.write(encChunk)
                        }

                        totalRead += n
                        if (onProgress != null && totalSize > 0) {
                            onProgress(((totalRead * 100) / totalSize).toInt().coerceIn(0, 99))
                        }
                    }

                    // doFinal() TANPA argumen di sini -- untuk stream cipher (CTR/NoPadding)
                    // ini cuma ngeflush sisa buffer internal (biasanya kosong), BUKAN
                    // ngerjain ulang cipher.update() yang udah dipanggil di atas.
                    val finalChunk = cipher.doFinal()
                    if (finalChunk != null && finalChunk.isNotEmpty()) {
                        out.write(finalChunk)
                    }
                }
            }
            onProgress?.invoke(100)

            if (totalRead != totalSize) {
                return EncryptResult(
                    success = false,
                    error = "Baca file source gak lengkap ($totalRead dari $totalSize byte) -- " +
                        "kemungkinan file berubah/terhapus di tengah proses."
                )
            }

            EncryptResult(
                success = true,
                outputFile = outputEnc,
                keyBase64 = Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP),
                originalSizeBytes = totalRead,
                encryptedSizeBytes = outputEnc.length(),
                originalSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            )
        } catch (oom: OutOfMemoryError) {
            EncryptResult(success = false, error = "File terlalu besar untuk dienkripsi (out of memory).")
        } catch (e: Exception) {
            EncryptResult(success = false, error = "Gagal enkripsi: ${e.message}")
        }
    }

    /**
     * Verifikasi PALING PENTING sebelum kamu hapus .so plaintext dari proyekmu:
     * decrypt ulang [result.outputFile] pakai key yang barusan dihasilkan, lalu bandingkan
     * SHA-256-nya dengan [EncryptResult.originalSha256] (dihitung SAAT encrypt, dari
     * plaintext asli). Diproses streaming juga -- aman dipanggil untuk file besar.
     *
     * Return true HANYA kalau hasil decrypt identik byte-per-byte dengan plaintext asli.
     */
    fun verifyEncryption(result: EncryptResult): Boolean {
        val encFile = result.outputFile ?: return false
        val keyB64 = result.keyBase64 ?: return false
        val expectedSha = result.originalSha256 ?: return false

        return try {
            val keyBytes = Base64.decode(keyB64, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")

            val digest = MessageDigest.getInstance("SHA-256")

            encFile.inputStream().buffered().use { input ->
                val iv = ByteArray(IV_SIZE_BYTES)
                if (input.read(iv) != IV_SIZE_BYTES) return false

                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

                val chunk = ByteArray(CHUNK_SIZE_BYTES)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    val decChunk = cipher.update(chunk, 0, n)
                    if (decChunk != null && decChunk.isNotEmpty()) {
                        digest.update(decChunk)
                    }
                }
                val finalChunk = cipher.doFinal()
                if (finalChunk != null && finalChunk.isNotEmpty()) {
                    digest.update(finalChunk)
                }
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            actualSha.equals(expectedSha, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Versi lama (baca semua ke memori) -- dipertahankan buat kompatibilitas kalau ada
     * kode lain yang manggil ini, tapi UNTUK FILE BESAR pakai encrypt() + verifyEncryption()
     * di atas, JANGAN ini.
     */
    @Deprecated("Untuk file besar bisa OOM. Pakai encrypt() + verifyEncryption() (streaming).")
    fun verifyRoundTrip(originalSo: File, encFile: File, keyBase64: String): Boolean {
        return try {
            val encBytes = encFile.readBytes()
            if (encBytes.size <= IV_SIZE_BYTES) return false

            val iv = encBytes.copyOfRange(0, IV_SIZE_BYTES)
            val cipherBytes = encBytes.copyOfRange(IV_SIZE_BYTES, encBytes.size)

            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(cipherBytes)

            decrypted.contentEquals(originalSo.readBytes())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Helper tambahan: tulis KEY (32 byte) + IV (16 byte) jadi satu file biner kecil
     * (48 byte total), supaya bisa langsung di-drag ke fitur "Dev Converter" (hex array)
     * yang sudah ada di MonToolkit -- hasilnya nanti berupa header .h kecil berisi
     * array 48 byte, tinggal di-include bareng header hasil convert file .enc di loader.cpp.
     *
     * Format: [0..32) = AES key, [32..48) = IV (diambil dari 16 byte pertama file .enc).
     */
    fun writeKeyIvBlob(result: EncryptResult, outFile: File): Boolean {
        val keyB64 = result.keyBase64 ?: return false
        val encFile = result.outputFile ?: return false
        return try {
            val keyBytes = Base64.decode(keyB64, Base64.NO_WRAP)
            val iv = encFile.inputStream().use { input ->
                val buf = ByteArray(IV_SIZE_BYTES)
                if (input.read(buf) != IV_SIZE_BYTES) return false
                buf
            }
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { out ->
                out.write(keyBytes)
                out.write(iv)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
