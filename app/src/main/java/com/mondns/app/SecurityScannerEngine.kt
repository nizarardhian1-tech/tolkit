package com.mondns.app

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * SecurityScannerEngine — audit APK secara statis: cek skema signing, permission
 * berbahaya, kemungkinan hardcoded secret/API key, URL & IP address yang ke-embed,
 * dan indikasi pemakaian algoritma crypto lemah (DES/MD5/ECB/RC4).
 *
 * Pendekatannya sengaja "raw string scan" (baca byte mentah classes.dex/manifest,
 * ekstrak rentetan ASCII yang printable, lalu di-regex) — persis pola yang sudah
 * dipakai StringsExtractor buat .so, cuma diterapkan ke isi APK. Ini sengaja dipilih
 * ketimbang parsing bytecode DEX penuh (butuh library tambahan kayak dexlib2/smali
 * yang berat) karena string konstan (URL, key, nama algoritma crypto) selalu tersimpan
 * apa adanya di string pool DEX/manifest — nggak perlu ngerti bytecode buat nemuin itu.
 *
 * DISCLAIMER buat dipahami user: ini heuristik berbasis pattern-matching, BUKAN jaminan
 * "aman"/"berbahaya" mutlak. False positive & false negative pasti ada (data acak yang
 * kebetulan cocok pola, atau secret yang di-obfuscate/di-encode duluan jadi nggak kebaca
 * mentah). Cocok buat audit awal/cepat, bukan pengganti security review menyeluruh.
 */
object SecurityScannerEngine {

    data class Finding(val category: String, val value: String, val source: String)

    data class SigningInfo(
        val verified: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val certSubject: String?,
        val certSha256: String?,
        val errors: List<String>
    )

    data class ScanReport(
        val apkName: String,
        val signing: SigningInfo?,
        val allPermissions: List<String>,
        val dangerousPermissions: List<String>,
        val secrets: List<Finding>,
        val urls: List<String>,
        val ipAddresses: List<String>,
        val weakCrypto: List<Finding>
    )

    // Cap per kategori biar UI & memori nggak jebol buat APK gede yang banyak match-nya.
    private const val MAX_FINDINGS_PER_CATEGORY = 200

    private val DANGEROUS_PERMISSIONS = setOf(
        "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "READ_CONTACTS", "WRITE_CONTACTS",
        "GET_ACCOUNTS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        "RECORD_AUDIO", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "ADD_VOICEMAIL", "USE_SIP", "PROCESS_OUTGOING_CALLS",
        "BODY_SENSORS", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS",
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "ACTIVITY_RECOGNITION"
    )

    private val SECRET_PATTERNS = listOf(
        "Google API Key" to Regex("AIza[0-9A-Za-z_\\-]{35}"),
        "AWS Access Key ID" to Regex("AKIA[0-9A-Z]{16}"),
        "Firebase Realtime DB" to Regex("[a-z0-9-]+\\.firebaseio\\.com"),
        "Slack Token" to Regex("xox[baprs]-[0-9A-Za-z-]{10,48}"),
        "JWT Token" to Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        "Private Key Block" to Regex("-----BEGIN (RSA |EC |DSA |)PRIVATE KEY-----"),
        "Stripe Live Key" to Regex("sk_live_[0-9A-Za-z]{20,}"),
        "Generic API Key Assignment" to Regex("(?i)(api[_-]?key|apikey|secret[_-]?key|access[_-]?token)\\s*[:=]\\s*[\"'][A-Za-z0-9_\\-]{16,}[\"']"),
        "Hardcoded Password Assignment" to Regex("(?i)password\\s*[:=]\\s*[\"'][^\"'\\s]{4,}[\"']"),
        "Basic Auth in URL" to Regex("https?://[^/\\s:]+:[^/\\s@]+@[^\\s]+")
    )

    private val WEAK_CRYPTO_PATTERNS = listOf(
        "DES (algoritma lemah)" to Regex("\\bDES/(ECB|CBC)\\b"),
        "MD5 (nggak aman buat hashing password)" to Regex("\\bMD5\\b"),
        "ECB Mode (bocorin pola data)" to Regex("/ECB/"),
        "RC4 (deprecated)" to Regex("\\bRC4\\b")
    )

    private val URL_REGEX = Regex("https?://[\\w.\\-]+(?::\\d+)?(?:/[\\w\\-./?%&=~+]*)?")
    private val IP_REGEX = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")
    private val PERMISSION_REGEX = Regex("android\\.permission\\.[A-Z_]+")

    fun scan(apkFile: File): ScanReport {
        val signing = try { readSigningInfo(apkFile) } catch (e: Exception) { null }

        val allPermissions = mutableSetOf<String>()
        val secretHits = mutableListOf<Finding>()
        val urlHits = mutableSetOf<String>()
        val ipHits = mutableSetOf<String>()
        val cryptoHits = mutableListOf<Finding>()

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList()

            // 1) Manifest: cuma buat narik daftar permission (string "android.permission.X"
            //    tersimpan apa adanya di string pool AXML, jadi cukup di-scan mentah).
            entries.find { it.name == "AndroidManifest.xml" }?.let { entry ->
                val text = extractPrintableStrings(zip.getInputStream(entry).readBytes())
                PERMISSION_REGEX.findAll(text).forEach { allPermissions.add(it.value.substringAfterLast('.')) }
            }

            // 2) classes*.dex: sumber utama buat secret, URL, IP, dan nama algoritma crypto,
            //    karena semua literal string di kode Java/Kotlin disimpan di sini.
            entries.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { entry ->
                val text = extractPrintableStrings(zip.getInputStream(entry).readBytes())
                scanText(text, "classes.dex", secretHits, urlHits, ipHits, cryptoHits)
            }

            // 3) Bonus: file config/text kecil yang mungkin kebawa ke assets/res (misal
            //    google-services.json, .env yang ketinggalan, config.properties, dll).
            entries.filter {
                !it.isDirectory && it.size in 1..(2 * 1024 * 1024) &&
                    it.name.substringAfterLast('.', "").lowercase() in setOf("json", "xml", "txt", "js", "properties", "cfg", "env", "yml", "yaml")
            }.forEach { entry ->
                val bytes = zip.getInputStream(entry).readBytes()
                val text = String(bytes, Charsets.UTF_8)
                scanText(text, entry.name, secretHits, urlHits, ipHits, cryptoHits)
            }
        }

        val dangerous = allPermissions.filter { it in DANGEROUS_PERMISSIONS }.sorted()

        return ScanReport(
            apkName = apkFile.name,
            signing = signing,
            allPermissions = allPermissions.sorted(),
            dangerousPermissions = dangerous,
            secrets = secretHits.distinctBy { it.category to it.value }.take(MAX_FINDINGS_PER_CATEGORY),
            urls = urlHits.sorted().take(MAX_FINDINGS_PER_CATEGORY),
            ipAddresses = ipHits.sorted().take(MAX_FINDINGS_PER_CATEGORY),
            weakCrypto = cryptoHits.distinctBy { it.category to it.value }.take(MAX_FINDINGS_PER_CATEGORY)
        )
    }

    private fun scanText(
        text: String,
        source: String,
        secretHits: MutableList<Finding>,
        urlHits: MutableSet<String>,
        ipHits: MutableSet<String>,
        cryptoHits: MutableList<Finding>
    ) {
        if (secretHits.size < MAX_FINDINGS_PER_CATEGORY) {
            for ((label, pattern) in SECRET_PATTERNS) {
                pattern.findAll(text).forEach { m ->
                    if (secretHits.size >= MAX_FINDINGS_PER_CATEGORY) return@forEach
                    secretHits.add(Finding(label, m.value.take(120), source))
                }
            }
        }
        URL_REGEX.findAll(text).forEach { urlHits.add(it.value) }
        IP_REGEX.findAll(text).forEach { ip ->
            // Loncatin IP lokal/placeholder umum (127.0.0.1, 0.0.0.0, versi angka semacam 1.2.3.4
            // di dalam string version-code) biar hasilnya nggak kebanjiran noise.
            if (ip.value != "0.0.0.0" && ip.value != "127.0.0.1") ipHits.add(ip.value)
        }
        if (cryptoHits.size < MAX_FINDINGS_PER_CATEGORY) {
            for ((label, pattern) in WEAK_CRYPTO_PATTERNS) {
                if (pattern.containsMatchIn(text)) {
                    cryptoHits.add(Finding(label, pattern.pattern, source))
                }
            }
        }
    }

    /** Sama persis pendekatannya dengan StringsExtractor: byte 32..126 dianggap printable ASCII. */
    private fun extractPrintableStrings(bytes: ByteArray, minLength: Int = 4): String {
        val sb = StringBuilder()
        val current = StringBuilder()
        for (b in bytes) {
            val v = b.toInt()
            if (v in 32..126) {
                current.append(v.toChar())
            } else {
                if (current.length >= minLength) {
                    sb.append(current).append('\n')
                }
                current.setLength(0)
            }
        }
        if (current.length >= minLength) sb.append(current)
        return sb.toString()
    }

    private fun readSigningInfo(apkFile: File): SigningInfo {
        val result = ApkVerifier.Builder(apkFile).build().verify()
        val cert = result.signerCertificates?.firstOrNull()
        val subject = cert?.subjectX500Principal?.name
        val sha256 = cert?.let {
            MessageDigest.getInstance("SHA-256").digest(it.encoded)
                .joinToString(":") { b -> "%02X".format(b) }
        }
        val errors = result.errors?.map { it.toString() } ?: emptyList()
        return SigningInfo(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            certSubject = subject,
            certSha256 = sha256,
            errors = errors
        )
    }
}
