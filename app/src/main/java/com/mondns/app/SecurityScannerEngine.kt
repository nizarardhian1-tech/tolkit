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

    data class ManifestSecurityInfo(
        val packageName: String?,
        val versionCode: String?,
        val versionName: String?,
        val debuggable: Boolean?,
        val allowBackup: Boolean?,
        val usesCleartextTraffic: Boolean?,
        val hasNetworkSecurityConfig: Boolean,
        val minSdkVersion: String?,
        val targetSdkVersion: String?,
        val exportedWithoutPermission: List<String>,
        val parseError: String?
    )

    data class NativeLibReport(
        val fileName: String,
        val architecture: String,
        val hasStackCanary: Boolean,
        val hasFortify: Boolean,
        val hasNxStack: Boolean?,
        val relro: String,
        val isStripped: Boolean
    )

    data class ScanReport(
        val apkName: String,
        val signing: SigningInfo?,
        val manifest: ManifestSecurityInfo?,
        val allPermissions: List<String>,
        val dangerousPermissions: List<String>,
        val secrets: List<Finding>,
        val urls: List<String>,
        val ipAddresses: List<String>,
        val weakCrypto: List<Finding>,
        val detectedSdks: List<String>,
        val nativeLibs: List<NativeLibReport>
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

    // Prefix package -> nama SDK yang enak dibaca. Dicek dengan cara nyari literal string
    // "Lcom/google/firebase/..." dkk di dex (format JVM internal pakai '/' bukan '.', jadi
    // regex-nya sengaja pakai '/' — beda dari URL_REGEX/PERMISSION_REGEX yang baca teks biasa).
    private val KNOWN_SDK_PREFIXES = listOf(
        "com/google/firebase" to "Firebase",
        "com/google/android/gms" to "Google Play Services",
        "com/facebook" to "Facebook SDK",
        "com/unity3d" to "Unity Engine",
        "com/google/ads" to "Google Ads",
        "com/google/android/gms/ads" to "Google AdMob",
        "com/applovin" to "AppLovin",
        "com/ironsource" to "IronSource",
        "com/mopub" to "MoPub",
        "com/vungle" to "Vungle",
        "com/chartboost" to "Chartboost",
        "com/adjust/sdk" to "Adjust (attribution tracker)",
        "com/appsflyer" to "AppsFlyer (attribution tracker)",
        "com/flurry" to "Flurry Analytics",
        "com/crashlytics" to "Crashlytics",
        "com/bugsnag" to "Bugsnag",
        "com/squareup/okhttp" to "OkHttp",
        "retrofit2" to "Retrofit",
        "com/squareup/picasso" to "Picasso",
        "com/bumptech/glide" to "Glide",
        "org/greenrobot/eventbus" to "EventBus",
        "com/tencent" to "Tencent SDK (mis. Bugly/MTA)",
        "com/umeng" to "Umeng Analytics",
        "com/alipay" to "Alipay SDK",
        "com/xiaomi" to "Xiaomi Push/SDK",
        "com/huawei/hms" to "Huawei Mobile Services",
        "io/branch" to "Branch.io",
        "com/onesignal" to "OneSignal Push",
        "com/urbanairship" to "Airship (push)",
        "com/stripe" to "Stripe SDK"
    )

    fun scan(apkFile: File): ScanReport {
        val signing = try { readSigningInfo(apkFile) } catch (e: Exception) { null }

        val allPermissions = mutableSetOf<String>()
        val secretHits = mutableListOf<Finding>()
        val urlHits = mutableSetOf<String>()
        val ipHits = mutableSetOf<String>()
        val cryptoHits = mutableListOf<Finding>()
        val sdkHits = mutableSetOf<String>()
        val nativeLibs = mutableListOf<NativeLibReport>()
        var manifestInfo: ManifestSecurityInfo? = null

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList()

            // 1) Manifest: dua lapis analisis atas file yang sama.
            entries.find { it.name == "AndroidManifest.xml" }?.let { entry ->
                val rawBytes = zip.getInputStream(entry).readBytes()

                // 1a) Raw string scan — cukup buat daftar nama permission (selalu plain string di pool).
                val text = extractPrintableStrings(rawBytes)
                PERMISSION_REGEX.findAll(text).forEach { allPermissions.add(it.value.substringAfterLast('.')) }

                // 1b) Parse AXML beneran — buat baca ATRIBUT (debuggable/allowBackup/exported/dst)
                //     yang nilainya typed binary, gak bisa ditangkap raw string scan.
                manifestInfo = try {
                    analyzeManifest(rawBytes)
                } catch (e: Exception) {
                    ManifestSecurityInfo(
                        packageName = null, versionCode = null, versionName = null,
                        debuggable = null, allowBackup = null,
                        usesCleartextTraffic = null, hasNetworkSecurityConfig = false,
                        minSdkVersion = null, targetSdkVersion = null,
                        exportedWithoutPermission = emptyList(),
                        parseError = e.message ?: "Gagal parse manifest"
                    )
                }
            }

            // 2) classes*.dex: sumber utama buat secret, URL, IP, nama algoritma crypto, dan
            //    deteksi SDK pihak ketiga (semua literal string & referensi package ada di sini).
            entries.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { entry ->
                val bytes = zip.getInputStream(entry).readBytes()
                val text = extractPrintableStrings(bytes)
                scanText(text, "classes.dex", secretHits, urlHits, ipHits, cryptoHits)
                for ((prefix, label) in KNOWN_SDK_PREFIXES) {
                    if (text.contains(prefix)) sdkHits.add(label)
                }
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

            // 4) Native libs (lib/**/*.so): reuse ElfParser yang sudah ada buat baca hardening
            //    flag (stack canary, FORTIFY, NX stack, RELRO). Tiap .so diekstrak ke file
            //    sementara dulu karena ElfParser butuh RandomAccessFile (gak baca dari stream zip).
            entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.forEach { entry ->
                try {
                    val tmp = File.createTempFile("scan_", "_${entry.name.substringAfterLast('/')}")
                    tmp.outputStream().use { out -> zip.getInputStream(entry).copyTo(out) }
                    val info = ElfParser.parse(tmp)
                    tmp.delete()
                    if (info.isValid) {
                        nativeLibs.add(
                            NativeLibReport(
                                fileName = entry.name.substringAfterLast('/'),
                                architecture = info.architecture,
                                hasStackCanary = info.hasStackCanary,
                                hasFortify = info.hasFortify,
                                hasNxStack = info.hasNxStack,
                                relro = info.relro,
                                isStripped = info.isStripped
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Satu .so gagal diparse (korup/format aneh) — jangan gagalin seluruh scan.
                }
            }
        }

        val dangerous = allPermissions.filter { it in DANGEROUS_PERMISSIONS }.sorted()

        return ScanReport(
            apkName = apkFile.name,
            signing = signing,
            manifest = manifestInfo,
            allPermissions = allPermissions.sorted(),
            dangerousPermissions = dangerous,
            secrets = secretHits.distinctBy { it.category to it.value }.take(MAX_FINDINGS_PER_CATEGORY),
            urls = urlHits.sorted().take(MAX_FINDINGS_PER_CATEGORY),
            ipAddresses = ipHits.sorted().take(MAX_FINDINGS_PER_CATEGORY),
            weakCrypto = cryptoHits.distinctBy { it.category to it.value }.take(MAX_FINDINGS_PER_CATEGORY),
            detectedSdks = sdkHits.sorted(),
            nativeLibs = nativeLibs.sortedBy { it.fileName }
        )
    }

    /** Jalanin AxmlParser lalu tarik atribut-atribut yang relevan buat audit keamanan. */
    private fun analyzeManifest(rawBytes: ByteArray): ManifestSecurityInfo {
        val root = AxmlParser.parse(rawBytes)

        val packageName = root.attributes["package"]?.displayValue()
        val versionCode = root.attributes["versionCode"]?.displayValue()
        val versionName = root.attributes["versionName"]?.displayValue()
        val usesSdk = root.children.find { it.name == "uses-sdk" }
        val minSdk = usesSdk?.attributes?.get("minSdkVersion")?.displayValue()
        val targetSdk = usesSdk?.attributes?.get("targetSdkVersion")?.displayValue()

        val application = root.children.find { it.name == "application" }
        val debuggable = application?.attributes?.get("debuggable")?.asBoolean
        val allowBackup = application?.attributes?.get("allowBackup")?.asBoolean
        val cleartext = application?.attributes?.get("usesCleartextTraffic")?.asBoolean
        val hasNetSecConfig = application?.attributes?.containsKey("networkSecurityConfig") == true

        val exportedNoPermission = mutableListOf<String>()
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
        application?.children?.forEach { comp ->
            if (comp.name !in componentTags) return@forEach
            val name = comp.attributes["name"]?.displayValue() ?: "?"
            val exportedAttr = comp.attributes["exported"]?.asBoolean
            val hasIntentFilter = comp.children.any { it.name == "intent-filter" }
            val hasPermission = comp.attributes.containsKey("permission")
            // Explicit exported=true, ATAU implicit export (attr gak ada tapi punya intent-filter —
            // perilaku default pre-Android 12) — dua-duanya dianggap "exported" buat audit ini.
            val isExported = exportedAttr == true || (exportedAttr == null && hasIntentFilter)
            if (isExported && !hasPermission) {
                exportedNoPermission.add("${comp.name}: $name")
            }
        }

        return ManifestSecurityInfo(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            debuggable = debuggable,
            allowBackup = allowBackup,
            usesCleartextTraffic = cleartext,
            hasNetworkSecurityConfig = hasNetSecConfig,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            exportedWithoutPermission = exportedNoPermission,
            parseError = null
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
