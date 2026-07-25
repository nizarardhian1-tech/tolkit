package com.mondns.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.wind.meditor.core.ManifestEditor
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import com.wind.meditor.utils.NodeValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object XpatchEngine {

    fun patchApk(
        context: Context,
        srcApk: File,
        moduleApks: List<File>,
        outputDir: File,
        signatureBypassLevel: Int,
        overrideVersionCode: Int?,
        overrideVersionName: String?,
        outputApkName: String,
        injectProvider: Boolean,
        useMicroG: Boolean,
        antiDebug: Boolean = false,
        hideDlIteratePhdr: Boolean = false,
        trapPrctl: Boolean = false,
        normalizeTiming: Boolean = false,
        deviceSpoofProfile: DeviceProfile? = null,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit = {}
    ): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val tempUnzipDir = File(context.cacheDir, "lspatch_unzip_${System.currentTimeMillis()}")
        tempUnzipDir.mkdirs()

        val isSplitSet = ApkInstaller.isSplitApkSet(srcApk)
        val workingApk = if (isSplitSet) resolveBaseApkFromSplitSet(context, srcApk) else srcApk

        try {
            onProgress(10)
            onLog(context.getString(R.string.patch_console_log_unzip))
            unzip(workingApk, tempUnzipDir)
            onProgress(20)

            if (isSplitSet) {
                onLog(context.getString(R.string.patch_console_log_merge_split))
                injectMatchingAbiNativeLibs(context, srcApk, tempUnzipDir)
            }
            onProgress(25)

            onLog(context.getString(R.string.patch_console_log_strip_sig))
            deleteSignatureFiles(tempUnzipDir)
            onProgress(35)

            onLog(context.getString(R.string.patch_console_log_inject_loader))
            injectLoaderDex(context, tempUnzipDir)
            onProgress(45)

            onLog(context.getString(R.string.patch_console_log_inject_native))
            injectNativeLibraries(context, tempUnzipDir)
            onProgress(55)

            onLog(context.getString(R.string.patch_console_log_inject_meta))
            injectMetaLoaderDex(context, tempUnzipDir, signatureBypassLevel >= 1)
            onProgress(65)

            injectXposedModules(context, moduleApks, tempUnzipDir, onLog)
            onProgress(70)

            val (originalSignature, originalFactory) = getOriginalSignatureAndFactory(context, workingApk)
            if (originalSignature.isNotEmpty()) {
                onLog(context.getString(R.string.patch_console_log_signature_format, sha256Fingerprint(originalSignature)))
            } else {
                onLog(context.getString(R.string.patch_console_log_signature_missing))
            }

            if (signatureBypassLevel >= 1) {
                embedOriginApk(workingApk, tempUnzipDir)
            }
            onProgress(75)

            onLog(context.getString(R.string.patch_console_log_write_config, signatureBypassLevel))
            writeConfigJsonWithSafety(
                unzipDir = tempUnzipDir,
                sigBypassLevel = signatureBypassLevel,
                overrideVersionCode = overrideVersionCode,
                originalSignature = originalSignature,
                appComponentFactory = originalFactory,
                injectProvider = injectProvider,
                useMicroG = useMicroG,
                antiDebug = antiDebug,
                hideDlIteratePhdr = hideDlIteratePhdr,
                trapPrctl = trapPrctl,
                normalizeTiming = normalizeTiming,
                deviceSpoofProfile = deviceSpoofProfile,
                onLog = onLog
            )
            onProgress(80)

            onLog(context.getString(R.string.patch_console_log_manifest))
            modifyAndroidManifest(File(tempUnzipDir, "AndroidManifest.xml"), overrideVersionCode, overrideVersionName)
            onProgress(85)

            onLog(context.getString(R.string.patch_console_log_repack))
            val unsignedApk = File(context.cacheDir, "unsigned_temp.apk")
            zipWithAlignment(tempUnzipDir, unsignedApk)
            onProgress(90)

            onLog(context.getString(R.string.patch_console_log_sign))
            val signedApk = File(outputDir, "$outputApkName-patched.apk")
            signApkWithAndroidKeystore(unsignedApk, signedApk)

            unsignedApk.delete()
            onProgress(100)
            return signedApk

        } finally {
            tempUnzipDir.deleteRecursively()
            if (isSplitSet) workingApk.delete()
        }
    }

    private fun normalizeAbiToken(s: String) = s.lowercase().replace('-', '_')

    /** Entry di dalam .apks/.xapk yang polanya kelihatan seperti split config (ABI/density/bahasa), bukan base. */
    private fun entryLooksLikeSplit(entryName: String): Boolean {
        val n = entryName.substringAfterLast('/').lowercase()
        if (n.contains("config") || n.contains("split")) return true
        val abiOrDensityPattern = Regex(
            ".*\\.(arm64[-_]v8a|armeabi[-_]v7a|armeabi|x86_64|x86|xxxhdpi|xxhdpi|xhdpi|hdpi|mdpi|ldpi)\\.apk$"
        )
        return abiOrDensityPattern.matches(n)
    }

    /** Cari entry base.apk di dalam file .apks/.xapk. Fallback: apk terbesar yang bukan pola split. */
    private fun findBaseApkEntry(zip: ZipFile): ZipEntry? {
        val apkEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .toList()
        if (apkEntries.isEmpty()) return null

        apkEntries.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", ignoreCase = true) }
            ?.let { return it }

        val nonSplit = apkEntries.filterNot { entryLooksLikeSplit(it.name) }
        if (nonSplit.isNotEmpty()) return nonSplit.maxByOrNull { it.size }

        return apkEntries.maxByOrNull { it.size }
    }

    /** Cari SEMUA split .apk yang native lib-nya cocok sama ABI device. */
    private fun findMatchingAbiSplitEntries(zip: ZipFile): List<ZipEntry> {
        val supportedAbis = Build.SUPPORTED_ABIS.map { normalizeAbiToken(it) }
        val apkEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .toList()

        for (abi in supportedAbis) {
            val matchingEntries = apkEntries.filter { normalizeAbiToken(it.name).contains(abi) }
            if (matchingEntries.isNotEmpty()) {
                // Ambil juga split feature yang tidak punya tag ABI di namanya (misal: split_GNASDK.apk)
                // supaya library di dalamnya tidak ikut terlewat.
                val nonAbiEntries = apkEntries.filter { entry ->
                    supportedAbis.none { normalizeAbiToken(entry.name).contains(it) }
                }
                return matchingEntries + nonAbiEntries
            }
        }
        return apkEntries
    }

    /** Ekstrak base.apk dari dalam file .apks/.xapk ke file sementara, dipakai sebagai APK kerja utama. */
    private fun resolveBaseApkFromSplitSet(context: Context, splitSetFile: File): File {
        ZipFile(splitSetFile).use { zip ->
            val baseEntry = findBaseApkEntry(zip)
                ?: throw IllegalStateException("Didn't find base.apk in this APKS/XAPK file")
            val extracted = File(context.cacheDir, "npatch_base_${System.currentTimeMillis()}.apk")
            zip.getInputStream(baseEntry).use { input ->
                extracted.outputStream().use { output -> input.copyTo(output) }
            }
            return extracted
        }
    }

    /**
     * Cari SEMUA split yang native lib-nya cocok dengan ABI device, lalu salin isi folder lib/<abi>/ 
     * dari SEMUA split tersebut ke folder kerja hasil unzip base.apk secara digabung (merge).
     */
    private fun injectMatchingAbiNativeLibs(context: Context, splitSetFile: File, unzipDir: File) {
        ZipFile(splitSetFile).use { zip ->
            val abiEntries = findMatchingAbiSplitEntries(zip)
            if (abiEntries.isEmpty()) return

            for (abiEntry in abiEntries) {
                // Gunakan nanoTime untuk mencegah bentrok nama saat proses looping sangat cepat
                val tempSplitFile = File(context.cacheDir, "npatch_abisplit_${System.nanoTime()}.apk")
                try {
                    zip.getInputStream(abiEntry).use { input ->
                        tempSplitFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    ZipFile(tempSplitFile).use { splitZip ->
                        splitZip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("lib/") }
                            .forEach { libEntry ->
                                val destFile = File(unzipDir, libEntry.name)
                                destFile.parentFile?.mkdirs()
                                splitZip.getInputStream(libEntry).use { input ->
                                    destFile.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                    }
                } finally {
                    tempSplitFile.delete()
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destFile = File(targetDirectory, entry.name)
                destFile.parentFile?.mkdirs()
                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun deleteSignatureFiles(unzipDir: File) {
        val metaInf = File(unzipDir, "META-INF")
        if (metaInf.exists() && metaInf.isDirectory) {
            metaInf.listFiles()?.forEach { file ->
                val name = file.name.uppercase()
                if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".MF")) {
                    file.delete()
                }
            }
        }
    }

    private fun injectLoaderDex(context: Context, unzipDir: File) {
        val targetFile = File(unzipDir, "assets/npatch/loader.bin")
        targetFile.parentFile?.mkdirs()
        context.assets.open("npatch/loader.bin").use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun injectMetaLoaderDex(context: Context, unzipDir: File, willEmbedOriginApk: Boolean) {
        val dexFiles = unzipDir.listFiles()?.filter {
            it.name.startsWith("classes") && it.name.endsWith(".dex")
        } ?: emptyList()

        val targetDexFile: File
        if (willEmbedOriginApk) {
            // The real app classes get loaded from origin.apk at runtime (NPatch redirects
            // appInfo.sourceDir to it before the OS builds the app's real classloader), so the
            // original top-level dex files are never read once patched at this level. Keeping
            // them around just duplicates bytes that already live inside origin.apk.
            dexFiles.forEach { it.delete() }
            targetDexFile = File(unzipDir, "classes.dex")
        } else {
            // No origin.apk (sigBypassLevel == NONE): nothing will supply the original classes
            // at runtime, so they must stay in place. Append the loader as the next free slot.
            val nextDexIndex = dexFiles.size + 1
            val targetDexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"
            targetDexFile = File(unzipDir, targetDexName)
        }

        context.assets.open("npatch/metaloader.dex").use { input ->
            targetDexFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun injectNativeLibraries(context: Context, unzipDir: File) {
        val assetPath = "npatch/so"
        val architectures = context.assets.list(assetPath) ?: return

        for (arch in architectures) {
            val targetLibDir = File(unzipDir, "assets/npatch/so/$arch")
            targetLibDir.mkdirs()

            val files = context.assets.list("$assetPath/$arch") ?: continue
            for (fileName in files) {
                val targetFile = File(targetLibDir, fileName)
                context.assets.open("$assetPath/$arch/$fileName").use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun injectXposedModule(moduleApk: File, unzipDir: File, packageName: String) {
        val targetModuleFile = File(unzipDir, "assets/npatch/modules/$packageName.apk")
        targetModuleFile.parentFile?.mkdirs()
        moduleApk.inputStream().use { input ->
            targetModuleFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    data class ConflictWarning(val title: String, val detail: String)

    fun detectConflicts(context: Context, srcApk: File): List<ConflictWarning> {
        val warnings = mutableListOf<ConflictWarning>()
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        val checkApk = if (ApkInstaller.isSplitApkSet(srcApk)) {
            try {
                resolveBaseApkFromSplitSet(context, srcApk)
            } catch (_: Exception) {
                srcApk
            }
        } else {
            srcApk
        }
        val info = pm.getPackageArchiveInfo(checkApk.absolutePath, flags)

        val existingFactory = info?.applicationInfo?.appComponentFactory
        if (!existingFactory.isNullOrBlank()) {
            warnings.add(
                ConflictWarning(
                    context.getString(R.string.xpatch_warning_factory_title),
                    context.getString(R.string.xpatch_warning_factory_detail, existingFactory)
                )
            )
        }

        try {
            ZipFile(srcApk).use { zip ->
                if (zip.getEntry("assets/npatch/config.json") != null ||
                    zip.getEntry("assets/lspatch/config.json") != null) {
                    warnings.add(
                        ConflictWarning(
                            context.getString(R.string.xpatch_warning_already_patched_title),
                            context.getString(R.string.xpatch_warning_already_patched_detail)
                        )
                    )
                }
            }
        } catch (_: Exception) { /* ignore */ }

        val reqFeatures = info?.reqFeatures?.mapNotNull { it.name } ?: emptyList()
        if (reqFeatures.any { it.contains("safetynet", ignoreCase = true) || it.contains("playintegrity", ignoreCase = true) }) {
            warnings.add(
                ConflictWarning(
                    context.getString(R.string.xpatch_warning_integrity_title),
                    context.getString(R.string.xpatch_warning_integrity_detail)
                )
            )
        }

        val minSdk = info?.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > 0 && minSdk < 24) {
            warnings.add(
                ConflictWarning(
                    context.getString(R.string.xpatch_warning_minsdk_title, minSdk),
                    context.getString(R.string.xpatch_warning_minsdk_detail)
                )
            )
        }

        if (checkApk != srcApk) checkApk.delete()
        return warnings
    }

    @Suppress("DEPRECATION")
    private fun getOriginalSignatureAndFactory(context: Context, srcApk: File): Pair<String, String> {
        val pm = context.packageManager
        
        var flags = PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        var info = pm.getPackageArchiveInfo(srcApk.absolutePath, flags)
        var factory = info?.applicationInfo?.appComponentFactory ?: ""

        var certBytes: ByteArray? = null

        if (info != null) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                certBytes = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()
                } else {
                    signingInfo.signingCertificateHistory?.firstOrNull()?.toByteArray()
                }
            }
        }

        if (certBytes == null) {
            flags = PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
            info = pm.getPackageArchiveInfo(srcApk.absolutePath, flags)
            if (info != null) {
                factory = info.applicationInfo?.appComponentFactory ?: ""
                val signatures = info.signatures
                if (!signatures.isNullOrEmpty()) {
                    certBytes = signatures[0]?.toByteArray()
                }
            }
        }

        val signature = certBytes?.let { bytesToRawString(it) } ?: ""
        return signature to factory
    }

    private fun bytesToRawString(bytes: ByteArray): String {
        val chars = CharArray(bytes.size)
        for (i in bytes.indices) chars[i] = (bytes[i].toInt() and 0xFF).toChar()
        return String(chars)
    }

    /** Formats a signing cert's SHA-256 hash for display, e.g. "AB:CD:EF:…". */
    private fun sha256Fingerprint(rawCertString: String): String {
        val certBytes = ByteArray(rawCertString.length) { i -> rawCertString[i].code.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun writeConfigJson(
        unzipDir: File,
        sigBypassLevel: Int,
        overrideVersionCode: Boolean,
        originalSignature: String,
        appComponentFactory: String,
        injectProvider: Boolean,
        outputLog: Boolean,
        useManager: Boolean,
        managerPackageName: String,
        newPackage: String,
        useMicroG: Boolean,
        stealthMode: Boolean = true,
        antiDebug: Boolean = false,
        hideDlIteratePhdr: Boolean = false,
        trapPrctl: Boolean = false,
        normalizeTiming: Boolean = false,
        deviceSpoofProfile: DeviceProfile? = null
    ) {
        val configFile = File(unzipDir, "assets/npatch/config.json")
        configFile.parentFile?.mkdirs()

        // Bangun blok deviceSpoof dari profil yang dipilih
        val spoof = deviceSpoofProfile
        val spoofEnabled = spoof != null && spoof != DeviceProfiles.CUSTOM
        val deviceSpoofJson = if (spoofEnabled && spoof != null) {
            """
  "deviceSpoof": {
    "enabled": true,
    "manufacturer": "${jsonEscape(spoof.manufacturer)}",
    "brand":        "${jsonEscape(spoof.brand)}",
    "model":        "${jsonEscape(spoof.model)}",
    "device":       "${jsonEscape(spoof.device)}",
    "product":      "${jsonEscape(spoof.product)}",
    "hardware":     "${jsonEscape(spoof.hardware)}",
    "board":        "${jsonEscape(spoof.board)}",
    "fingerprint":  "${jsonEscape(spoof.fingerprint)}",
    "gpuRenderer":  "${jsonEscape(spoof.gpuRenderer)}",
    "gpuVendor":    "${jsonEscape(spoof.gpuVendor)}"
  }"""
        } else {
            """
  "deviceSpoof": { "enabled": false }"""
        }

        val json = """
        {
          "useManager": $useManager,
          "debuggable": $outputLog,
          "overrideVersionCode": $overrideVersionCode,
          "injectProvider": $injectProvider,
          "outputLog": $outputLog,
          "sigBypassLevel": $sigBypassLevel,
          "originalSignature": "${jsonEscape(originalSignature)}",
          "appComponentFactory": "${jsonEscape(appComponentFactory)}",
          "managerPackageName": "${jsonEscape(managerPackageName)}",
          "newPackage": "${jsonEscape(newPackage)}",
          "useMicroG": $useMicroG,
          "stealthMode": $stealthMode,
          "antiDebug": $antiDebug,
          "hideDlIteratePhdr": $hideDlIteratePhdr,
          "trapPrctl": $trapPrctl,
          "normalizeTiming": $normalizeTiming,$deviceSpoofJson
        }
        """.trimIndent()

        configFile.writeText(json, Charsets.UTF_8)
    }

    private fun modifyAndroidManifest(
        manifestFile: File,
        overrideVersionCode: Int?,
        overrideVersionName: String?
    ) {
        if (!manifestFile.exists()) return

        val property = ModificationProperty()

        // 1. Injeksi Stub Factory LSPatch
        property.addApplicationAttribute(
            AttributeItem(
                "appComponentFactory",
                "top.nkbe.npatch.metaloader.LSPAppComponentFactoryStub"
            )
        )

        // 2. Paksa ekstrak native libs
        property.addApplicationAttribute(AttributeItem("extractNativeLibs", true))

        // 3. Matikan syarat Split APK (AAB) dari Google Play
        property.addManifestAttribute(AttributeItem("isSplitRequired", false))
        property.addManifestAttribute(AttributeItem("requiredSplitTypes", ""))
        property.addManifestAttribute(AttributeItem("splitTypes", ""))
        property.addManifestAttribute(AttributeItem("isolatedSplits", false))

        // 🟢 TAMBAHKAN IZIN OVERLAY & QUERY PACKAGES DI SINI
        property.addUsesPermission("android.permission.SYSTEM_ALERT_WINDOW")
        property.addUsesPermission("android.permission.ACTION_MANAGE_OVERLAY_PERMISSION")
        property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES")

        if (overrideVersionCode != null) {
            property.addManifestAttribute(AttributeItem(NodeValue.Manifest.VERSION_CODE, overrideVersionCode))
        }
        if (!overrideVersionName.isNullOrBlank()) {
            property.addManifestAttribute(AttributeItem(NodeValue.Manifest.VERSION_NAME, overrideVersionName))
        }

        val outputBytes = ByteArrayOutputStream()
        manifestFile.inputStream().use { input ->
            ManifestEditor(input, outputBytes, property).processManifest()
        }
        manifestFile.writeBytes(outputBytes.toByteArray())
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    // Di XpatchEngine.kt:
    // Ubah dari 4096L (4KB) menjadi 16384L (16KB):
    private const val SO_ALIGNMENT = 16384L

    private fun zipWithAlignment(sourceDir: File, zipFile: File) {
    val counting = CountingOutputStream(FileOutputStream(zipFile))
    ZipOutputStream(counting).use { zipOut ->
        zipOut.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)

        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val entryName = file.relativeTo(sourceDir).path.replace('\\', '/')

                val isSo = entryName.endsWith(".so")
                val isArsc = entryName == "resources.arsc"
                val isOrigin = entryName == "assets/npatch/origin.apk"

                if (isSo || isArsc || isOrigin) {
                    // .so butuh alignment 16KB (16384L), arsc/origin 4-byte (4L)
                    val alignment = if (isSo) SO_ALIGNMENT else 4L

                    val fileSize = file.length()
                    val crc32 = CRC32().apply {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                update(buffer, 0, read)
                            }
                        }
                    }.value

                    val entry = ZipEntry(entryName).apply {
                        method = ZipEntry.STORED
                        size = fileSize
                        compressedSize = fileSize
                        crc = crc32
                    }

                    val nameLen = entryName.toByteArray(Charsets.UTF_8).size
                    val offsetBeforeExtra = counting.count + 30 + nameLen

                    // Rumus Alignment ZIP Extra Field yang Presisi
                    val requiredPad = ((alignment - ((offsetBeforeExtra + 4) % alignment)) % alignment).toInt()
                    val extraLen = 4 + requiredPad

                    val extra = ByteArray(extraLen)
                    extra[0] = 0xD9.toByte() // Android ZipAlign Header Tag (0xD935)
                    extra[1] = 0x35.toByte()
                    extra[2] = (requiredPad and 0xFF).toByte()
                    extra[3] = ((requiredPad shr 8) and 0xFF).toByte()
                    entry.setExtra(extra)

                    zipOut.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                } else {
                    zipOut.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
    }
}

    private fun signApkWithAndroidKeystore(unsignedApk: File, signedApk: File) {
    val alias = "mondns_patcher_key"
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    if (!keyStore.containsAlias(alias)) {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build()
        )
        kpg.generateKeyPair()
    }

    val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    val privateKey = entry.privateKey
    val cert = entry.certificate as X509Certificate

    val signerConfig = ApkSigner.SignerConfig.Builder(
        alias,
        privateKey,
        listOf(cert)
    ).build()

    val signer = ApkSigner.Builder(listOf(signerConfig))
        .setInputApk(unsignedApk)
        .setOutputApk(signedApk)
        .setMinSdkVersion(21) // Wajib set minSdk agar V1 & V2 otomatis aktif
        .setV1SigningEnabled(true)
        .setV2SigningEnabled(true)
        .build()

    signer.sign()
}

    private fun injectXposedModules(context: Context, moduleApks: List<File>, unzipDir: File, onLog: (String) -> Unit) {
        val injected = mutableListOf<String>()
        for (moduleApk in moduleApks) {
            if (!moduleApk.exists()) continue
            val info = context.packageManager.getPackageArchiveInfo(moduleApk.absolutePath, 0) ?: continue
            val pkg = info.packageName ?: continue
            if (pkg in injected) continue

            onLog(context.getString(R.string.patch_console_log_inject_module_format, pkg))
            injectXposedModule(moduleApk, unzipDir, pkg)
            injected.add(pkg)
        }
    }

    private fun embedOriginApk(workingApk: File, unzipDir: File) {
        val originFile = File(unzipDir, "assets/npatch/origin.apk")
        originFile.parentFile?.mkdirs()
        workingApk.inputStream().use { input ->
            GZIPOutputStream(originFile.outputStream()).use { gzipOut ->
                input.copyTo(gzipOut)
            }
        }
    }

    private fun writeConfigJsonWithSafety(
        unzipDir: File,
        sigBypassLevel: Int,
        overrideVersionCode: Int?,
        originalSignature: String,
        appComponentFactory: String,
        injectProvider: Boolean,
        useMicroG: Boolean,
        antiDebug: Boolean,
        hideDlIteratePhdr: Boolean,
        trapPrctl: Boolean,
        normalizeTiming: Boolean,
        deviceSpoofProfile: DeviceProfile?,
        onLog: (String) -> Unit
    ) {
        val actualTrapPrctl = if (sigBypassLevel == 4) {
            onLog("⚠ trapPrctl is automatically disabled due to conflict with Level 4 (Seccomp)")
            false
        } else {
            trapPrctl
        }

        writeConfigJson(
            unzipDir = unzipDir,
            sigBypassLevel = sigBypassLevel,
            overrideVersionCode = overrideVersionCode != null,
            originalSignature = originalSignature,
            appComponentFactory = appComponentFactory,
            injectProvider = injectProvider,
            outputLog = false,
            useManager = false,
            managerPackageName = "",
            newPackage = "",
            useMicroG = useMicroG,
            stealthMode = true,
            antiDebug = antiDebug,
            hideDlIteratePhdr = hideDlIteratePhdr,
            trapPrctl = actualTrapPrctl,
            normalizeTiming = normalizeTiming,
            deviceSpoofProfile = deviceSpoofProfile
        )
    }
}