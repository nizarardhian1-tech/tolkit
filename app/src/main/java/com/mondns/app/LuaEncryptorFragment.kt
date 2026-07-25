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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentLuaEncryptorBinding
import java.io.File
import kotlin.concurrent.thread

class LuaEncryptorFragment : Fragment() {
    private var _binding: FragmentLuaEncryptorBinding? = null
    private val binding get() = _binding!!
    private var selectedFile: File? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleFileUri(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLuaEncryptorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardFileSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(intent)
        }

        binding.switchExpiry.setOnCheckedChangeListener { _, isChecked ->
            binding.tilExpiryDate.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        binding.switchCompile.setOnCheckedChangeListener { _, isChecked ->
            binding.switchCorrupt.isEnabled = isChecked
            if (!isChecked) binding.switchCorrupt.isChecked = false
        }

        binding.btnEncrypt.setOnClickListener { startEncryption() }
    }

    private fun handleFileUri(uri: Uri) {
        try {
            var name = "script.lua"
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
            val tempFile = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            
            if (!name.endsWith(".lua", true)) {
                Toast.makeText(requireContext(), "Please select a .lua file", Toast.LENGTH_SHORT).show()
                return
            }

            selectedFile = tempFile
            binding.tvFileName.text = name
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to read file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Grant All Files Access to save encrypted script.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
                return false
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                return false
            }
        }
        return true
    }

    private fun startEncryption() {
        val file = selectedFile
        if (file == null) {
            Toast.makeText(requireContext(), "Select a Lua file first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkStoragePermission()) return

        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnEncrypt.isEnabled = false
        binding.btnEncrypt.alpha = 0.5f

        val outputDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/Encryptor")
        val outName = file.name

        val addExpiry = binding.switchExpiry.isChecked
        val expiryDate = binding.etExpiryDate.text.toString().trim()
        val addAntiLog = binding.switchAntiLog.isChecked
        val obfStrings = binding.switchObfuscate.isChecked
        val compile = binding.switchCompile.isChecked
        val corrupt = binding.switchCorrupt.isChecked

        thread {
            try {
                val resultFile = LuaEncryptorEngine.encrypt(
                    file, outputDir, outName, addExpiry, expiryDate, addAntiLog, obfStrings, compile, corrupt
                ) { progress, status ->
                    activity?.runOnUiThread {
                        if (_binding != null) {
                            binding.progressBar.progress = progress
                            binding.tvProgressLabel.text = status
                        }
                    }
                }

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    resetUI()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Encryption Success 🎉")
                        .setMessage("Encrypted script saved to:\n\n${resultFile.absolutePath}")
                        .setPositiveButton("OK", null)
                        .show()
                }

            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    resetUI()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Encryption Failed")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun resetUI() {
        binding.layoutProgress.visibility = View.GONE
        binding.btnEncrypt.isEnabled = true
        binding.btnEncrypt.alpha = 1.0f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}