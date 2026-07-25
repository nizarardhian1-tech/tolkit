package com.mondns.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.mondns.app.databinding.FragmentApkDiffBinding
import java.io.File
import kotlin.concurrent.thread

/**
 * ApkDiffFragment — UI buat ApkDiffEngine: pilih 2 APK, bandingin, tampilin apa yang
 * berubah (permission, SDK, native lib, sertifikat, flag debuggable/allowBackup, dst).
 * Berguna buat cek APK hasil patch vs original, atau update versi baru vs lama.
 */
class ApkDiffFragment : Fragment() {

    private var _binding: FragmentApkDiffBinding? = null
    private val binding get() = _binding!!

    private var apkAFile: File? = null
    private var apkBFile: File? = null

    private val apkAPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleApkUri(uri, isA = true) }
        }
    }
    private val apkBPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleApkUri(uri, isA = false) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApkDiffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardPickApkA.setOnClickListener {
            apkAPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        binding.cardPickApkB.setOnClickListener {
            apkBPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        binding.btnCompare.setOnClickListener { startCompare() }
    }

    private fun handleApkUri(uri: Uri, isA: Boolean) {
        try {
            val name = queryDisplayName(uri) ?: "app.apk"
            if (!name.endsWith(".apk", ignoreCase = true)) {
                Toast.makeText(requireContext(), getString(R.string.apk_signer_no_apk), Toast.LENGTH_SHORT).show()
                return
            }
            val tempFile = File(requireContext().cacheDir, if (isA) "diffA_$name" else "diffB_$name")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (isA) {
                apkAFile = tempFile
                binding.tvApkAName.text = name
            } else {
                apkBFile = tempFile
                binding.tvApkBName.text = name
            }
            binding.cardResult.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage ?: "Error", Toast.LENGTH_SHORT).show()
        }
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

    private fun startCompare() {
        val apkA = apkAFile
        val apkB = apkBFile
        if (apkA == null || apkB == null) {
            Toast.makeText(requireContext(), "Pilih kedua APK dulu", Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutProgress.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE
        binding.btnCompare.isEnabled = false

        thread {
            try {
                val result = ApkDiffEngine.diff(apkA, apkB)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnCompare.isEnabled = true
                    binding.cardResult.visibility = View.VISIBLE
                    binding.tvDiffResult.text = renderDiff(result)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.layoutProgress.visibility = View.GONE
                    binding.btnCompare.isEnabled = true
                    Toast.makeText(requireContext(), "Gagal bandingin: ${e.localizedMessage ?: e}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderDiff(r: ApkDiffEngine.DiffResult): String = buildString {
        append("A: ${r.nameA} — ${r.versionA ?: "?"}\n")
        append("B: ${r.nameB} — ${r.versionB ?: "?"}\n\n")

        append("Sertifikat: ")
        append(
            when {
                r.certSha256A == null || r.certSha256B == null -> "gak kebaca di salah satu/keduanya\n"
                r.certChanged -> "❌ BEDA (APK B ditandatangani key yang beda — kalau ini update, user harus uninstall dulu)\n"
                else -> "✅ sama\n"
            }
        )

        if (r.debuggableA != r.debuggableB) append("⚠️ debuggable berubah: ${r.debuggableA} → ${r.debuggableB}\n")
        if (r.allowBackupA != r.allowBackupB) append("⚠️ allowBackup berubah: ${r.allowBackupA} → ${r.allowBackupB}\n")

        append("\nPermission ditambah (${r.permissionsAdded.size}):\n")
        if (r.permissionsAdded.isEmpty()) append("  -\n") else r.permissionsAdded.forEach { append("  + $it\n") }
        append("\nPermission dihapus (${r.permissionsRemoved.size}):\n")
        if (r.permissionsRemoved.isEmpty()) append("  -\n") else r.permissionsRemoved.forEach { append("  - $it\n") }

        if (r.exportedNoPermissionAdded.isNotEmpty()) {
            append("\n⚠️ Component exported tanpa permission (baru muncul di B):\n")
            r.exportedNoPermissionAdded.forEach { append("  + $it\n") }
        }

        append("\nSDK ditambah (${r.sdksAdded.size}):\n")
        if (r.sdksAdded.isEmpty()) append("  -\n") else r.sdksAdded.forEach { append("  + $it\n") }
        append("\nSDK dihapus (${r.sdksRemoved.size}):\n")
        if (r.sdksRemoved.isEmpty()) append("  -\n") else r.sdksRemoved.forEach { append("  - $it\n") }

        append("\nNative lib ditambah (${r.nativeLibsAdded.size}):\n")
        if (r.nativeLibsAdded.isEmpty()) append("  -\n") else r.nativeLibsAdded.forEach { append("  + $it\n") }
        append("\nNative lib dihapus (${r.nativeLibsRemoved.size}):\n")
        if (r.nativeLibsRemoved.isEmpty()) append("  -\n") else r.nativeLibsRemoved.forEach { append("  - $it\n") }
    }.trim()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
