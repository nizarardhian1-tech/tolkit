package com.mondns.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.mondns.app.databinding.FragmentSecurityScannerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * SecurityScannerFragment — UI buat SecurityScannerEngine: pilih APK, scan, tampilin
 * hasil per kategori (signing info, permission berbahaya, kemungkinan secret, URL/IP,
 * indikasi crypto lemah), plus export laporan ke file teks.
 */
class SecurityScannerFragment : Fragment() {

    private var _binding: FragmentSecurityScannerBinding? = null
    private val binding get() = _binding!!

    private var selectedApkFile: File? = null
    private var lastReport: SecurityScannerEngine.ScanReport? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleApkUri(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSecurityScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardPickApk.setOnClickListener {
            apkPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnExport.setOnClickListener { exportReport() }
    }

    // ==================== Pemilihan APK ====================

    private fun handleApkUri(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "app.apk"
            if (!name.endsWith(".apk", ignoreCase = true)) {
                Toast.makeText(requireContext(), getString(R.string.apk_signer_no_apk), Toast.LENGTH_SHORT).show()
                return
            }
            val tempFile = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            selectedApkFile = tempFile
            binding.tvApkName.text = name
            loadApkIcon(tempFile.absolutePath)
            // APK baru dipilih -> hasil scan lama (kalau ada) udah nggak relevan.
            binding.containerResults.visibility = View.GONE
            lastReport = null
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApkIcon(apkPath: String) {
        try {
            val pm = requireContext().packageManager
            val info = pm.getPackageArchiveInfo(apkPath, 0)
            val appInfo = info?.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = apkPath
                appInfo.publicSourceDir = apkPath
                binding.ivApkIcon.setImageDrawable(appInfo.loadIcon(pm))
                binding.ivApkIcon.imageTintList = null
                return
            }
            resetApkIconToDefault()
        } catch (e: Exception) {
            resetApkIconToDefault()
        }
    }

    private fun resetApkIconToDefault() {
        binding.ivApkIcon.setImageResource(R.drawable.ic_apk)
        binding.ivApkIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(binding.ivApkIcon, com.google.android.material.R.attr.colorPrimary)
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    /** Sama persis polanya dengan ApkSignerFragment/LuaEncryptor dll biar konsisten. */
    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Berikan izin All Files Access dulu buat simpan laporan.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
                return false
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(requireContext(), "Berikan izin penyimpanan dulu, lalu tekan tombolnya lagi.", Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2002)
                return false
            }
        }
        return true
    }

    // ==================== Scan ====================

    private fun startScan() {
        val apkFile = selectedApkFile
        if (apkFile == null) {
            Toast.makeText(requireContext(), getString(R.string.apk_signer_no_apk), Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutProgress.visibility = View.VISIBLE
        binding.containerResults.visibility = View.GONE
        binding.btnScan.isEnabled = false

        thread {
            try {
                val report = SecurityScannerEngine.scan(apkFile)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    lastReport = report
                    renderReport(report)
                    binding.layoutProgress.visibility = View.GONE
                    binding.containerResults.visibility = View.VISIBLE
                    binding.btnScan.isEnabled = true
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnScan.isEnabled = true
                    Toast.makeText(requireContext(), "Gagal scan: ${e.localizedMessage ?: e}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderReport(report: SecurityScannerEngine.ScanReport) {
        // --- Signing Info ---
        val signing = report.signing
        binding.tvSigningInfo.text = if (signing == null) {
            "Gagal baca info signing (APK mungkin belum di-sign / rusak)."
        } else {
            buildString {
                append(if (signing.verified) "✅ VERIFIED" else "❌ NOT VERIFIED").append('\n')
                append("Scheme: ")
                append(listOfNotNull(
                    "v1".takeIf { signing.v1 },
                    "v2".takeIf { signing.v2 },
                    "v3".takeIf { signing.v3 }
                ).ifEmpty { listOf("none") }.joinToString(", "))
                append('\n')
                if (signing.certSubject != null) append("Subject: ${signing.certSubject}\n")
                if (signing.certSha256 != null) append("SHA-256: ${signing.certSha256}\n")
                if (signing.errors.isNotEmpty()) {
                    append("Errors:\n")
                    signing.errors.take(5).forEach { append("  • $it\n") }
                }
            }.trim()
        }

        // --- Manifest Security ---
        val manifest = report.manifest
        binding.tvManifest.text = when {
            manifest == null -> "Gagal baca AndroidManifest.xml."
            manifest.parseError != null -> "Gagal parse manifest: ${manifest.parseError}"
            else -> buildString {
                if (manifest.packageName != null) append("Package: ${manifest.packageName}\n")
                if (manifest.versionName != null || manifest.versionCode != null) {
                    append("Version: ${manifest.versionName ?: "?"} (code ${manifest.versionCode ?: "?"})\n")
                }
                if (manifest.minSdkVersion != null || manifest.targetSdkVersion != null) {
                    append("SDK: min=${manifest.minSdkVersion ?: "?"}, target=${manifest.targetSdkVersion ?: "?"}\n")
                }
                append('\n')
                append(if (manifest.debuggable == true) "🔴 debuggable=true (JANGAN dirilis kayak gini!)\n" else "✅ debuggable=false/tidak diset\n")
                append(if (manifest.allowBackup == true) "⚠️ allowBackup=true (data bisa ditarik via adb backup)\n" else "✅ allowBackup=false/tidak diset\n")
                if (manifest.usesCleartextTraffic == true) append("⚠️ usesCleartextTraffic=true (trafik HTTP polos diizinkan)\n")
                if (manifest.hasNetworkSecurityConfig) append("ℹ️ Punya Network Security Config kustom\n")
                if (manifest.exportedWithoutPermission.isNotEmpty()) {
                    append("\n⚠️ Exported TANPA permission (${manifest.exportedWithoutPermission.size}):\n")
                    manifest.exportedWithoutPermission.take(30).forEach { append("  • $it\n") }
                } else {
                    append("\n✅ Nggak ada component exported tanpa permission\n")
                }
            }.trim()
        }

        // --- Dangerous Permissions ---
        binding.tvPermissions.text = if (report.dangerousPermissions.isEmpty()) {
            getString(R.string.security_scanner_none_found) + " (dari ${report.allPermissions.size} total permission)"
        } else {
            report.dangerousPermissions.joinToString("\n") { "⚠️ $it" } +
                "\n\n(${report.allPermissions.size} total permission diminta)"
        }

        // --- Secrets ---
        binding.tvSecrets.text = if (report.secrets.isEmpty()) {
            getString(R.string.security_scanner_none_found)
        } else {
            report.secrets.joinToString("\n\n") { "[${it.category}]\n${it.value}\n(di: ${it.source})" }
        }

        // --- URLs & IPs ---
        binding.tvNetwork.text = buildString {
            append("URLs (${report.urls.size}):\n")
            if (report.urls.isEmpty()) append("  ${getString(R.string.security_scanner_none_found)}\n")
            else report.urls.take(50).forEach { append("  $it\n") }
            append("\nIP Addresses (${report.ipAddresses.size}):\n")
            if (report.ipAddresses.isEmpty()) append("  ${getString(R.string.security_scanner_none_found)}\n")
            else report.ipAddresses.take(50).forEach { append("  $it\n") }
        }.trim()

        // --- Weak Crypto ---
        binding.tvCrypto.text = if (report.weakCrypto.isEmpty()) {
            getString(R.string.security_scanner_none_found)
        } else {
            report.weakCrypto.joinToString("\n") { "⚠️ ${it.category} (di: ${it.source})" }
        }

        // --- Detected SDKs ---
        binding.tvSdks.text = if (report.detectedSdks.isEmpty()) {
            getString(R.string.security_scanner_none_found)
        } else {
            report.detectedSdks.joinToString("\n") { "• $it" }
        }

        // --- Native Library Hardening ---
        binding.tvNativeLibs.text = if (report.nativeLibs.isEmpty()) {
            "Nggak ada native library (.so) di APK ini."
        } else {
            report.nativeLibs.joinToString("\n\n") { lib ->
                buildString {
                    append("${lib.fileName} (${lib.architecture})\n")
                    append("  Canary: ${if (lib.hasStackCanary) "✅" else "❌"}  ")
                    append("FORTIFY: ${if (lib.hasFortify) "✅" else "❌"}  ")
                    append("NX: ${when (lib.hasNxStack) { true -> "✅"; false -> "❌"; null -> "?" }}\n")
                    append("  RELRO: ${lib.relro}  Stripped: ${if (lib.isStripped) "ya" else "tidak"}")
                }
            }
        }
    }

    // ==================== Export ====================

    private fun exportReport() {
        val report = lastReport ?: return
        if (!checkStoragePermission()) return

        thread {
            try {
                val outDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/SecurityScanner")
                outDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = report.apkName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
                val outFile = File(outDir, "${safeName}_scan_$timestamp.txt")

                outFile.bufferedWriter().use { w ->
                    w.write("MonToolkit — Security Scanner Report\n")
                    w.write("APK: ${report.apkName}\n")
                    w.write("=".repeat(50) + "\n\n")
                    w.write("[Signing Info]\n${binding.tvSigningInfo.text}\n\n")
                    w.write("[Manifest Security]\n${binding.tvManifest.text}\n\n")
                    w.write("[Dangerous Permissions]\n${binding.tvPermissions.text}\n\n")
                    w.write("[Possible Hardcoded Secrets]\n${binding.tvSecrets.text}\n\n")
                    w.write("[URLs & IP Addresses]\n${binding.tvNetwork.text}\n\n")
                    w.write("[Weak Crypto Indicators]\n${binding.tvCrypto.text}\n\n")
                    w.write("[Detected Third-Party SDKs]\n${binding.tvSdks.text}\n\n")
                    w.write("[Native Library Hardening]\n${binding.tvNativeLibs.text}\n\n")
                    w.write(getString(R.string.security_scanner_disclaimer) + "\n")
                }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.tvExportResult.visibility = View.VISIBLE
                    binding.tvExportResult.text = getString(R.string.security_scanner_export_success, outFile.absolutePath)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), "Gagal export: ${e.localizedMessage ?: e}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
