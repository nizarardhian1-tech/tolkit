package com.mondns.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentDevConverterBinding
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DevConverterFragment : Fragment() {
    private var _binding: FragmentDevConverterBinding? = null
    private val binding get() = _binding!!

    private var selectedFile: File? = null
    private var isConverting = false

    private val LARGE_FILE_THRESHOLD_BYTES = 20L * 1024 * 1024

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val file = getFileFromUri(uri)
                if (file != null && file.exists()) {
                    handleFileSelected(file)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.conv_error_read_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val conversionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConversionService.ACTION_PROGRESS -> {
                    val percent = intent.getIntExtra(ConversionService.EXTRA_PERCENT, 0)
                    updateProgressUI(percent)
                }
                ConversionService.ACTION_COMPLETE -> {
                    val outputName = intent.getStringExtra(ConversionService.EXTRA_OUTPUT_NAME) ?: ""
                    val outputName2 = intent.getStringExtra(ConversionService.EXTRA_OUTPUT_NAME_2) ?: ""
                    val mode = intent.getStringExtra(ConversionService.EXTRA_ENCRYPT_MODE) ?: ConversionService.MODE_OFF
                    onConversionComplete(outputName, outputName2, mode)
                }
                ConversionService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(ConversionService.EXTRA_MESSAGE) ?: ""
                    onConversionError(message)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevConverterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("filePath")?.let { path ->
            val file = File(path)
            if (file.exists() && !file.isDirectory) {
                handleFileSelected(file)
            }
        }

        // Auto-Scroll On Focus untuk mencegah field terpotong keyboard
        scrollToViewOnFocus(
            binding.etCryptoInput,
            binding.etCryptoKey,
            binding.etArrayName
        )

        // Mode Switching Listener (File Converter vs Text Crypto)
        binding.toggleConverterMode.check(R.id.tabFileConverter)
        binding.toggleConverterMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.tabFileConverter -> {
                    binding.containerFileConverter.visibility = View.VISIBLE
                    binding.containerTextCrypto.visibility = View.GONE
                }
                R.id.tabTextCrypto -> {
                    binding.containerFileConverter.visibility = View.GONE
                    binding.containerTextCrypto.visibility = View.VISIBLE
                }
            }
        }

        // Listeners Tab 1 (File Converter)
        binding.cardFileSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(intent)
        }

        binding.btnConvert.setOnClickListener { onConvertClicked() }

        binding.rgEncryptMode.setOnCheckedChangeListener { _, _ -> applyEncryptModeUI() }
        applyEncryptModeUI()

        // Listeners Tab 2 (Text Crypto & Code Generator)
        setupTextCryptoListeners()
    }

    /** Mendorong tampilan field ke atas keyboard saat di-tap/fokus */
    private fun scrollToViewOnFocus(vararg fields: EditText) {
        fields.forEach { field ->
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.postDelayed({
                        if (_binding == null) return@postDelayed
                        val scrollView = binding.converterScrollView
                        val viewPos = IntArray(2)
                        val scrollPos = IntArray(2)
                        v.getLocationOnScreen(viewPos)
                        scrollView.getLocationOnScreen(scrollPos)
                        val targetY = scrollView.scrollY + (viewPos[1] - scrollPos[1]) - 140
                        scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }, 250)
                }
            }
        }
    }

    // ====================================================================
    // TAB 2 LOGIC: TEXT CRYPTO & CODE GENERATOR STUDIO
    // ====================================================================

    private fun setupTextCryptoListeners() {
        binding.rgCryptoAlgorithm.setOnCheckedChangeListener { _, checkedId ->
            val isXor = checkedId == R.id.rbXorObfuscator
            val isAes = checkedId == R.id.rbAes256
            val needsKey = isXor || isAes

            binding.tilCryptoKey.visibility = if (needsKey) View.VISIBLE else View.GONE
            binding.btnCryptoDecrypt.visibility = if (isXor || checkedId == R.id.rbHashGen) View.GONE else View.VISIBLE

            if (isXor) binding.tilCryptoKey.hint = "XOR Key (e.g. 0x5A or 90)"
            else if (isAes) binding.tilCryptoKey.hint = "AES-256 Secret Password"
        }

        binding.btnCryptoEncrypt.setOnClickListener { processCrypto(isEncrypt = true) }
        binding.btnCryptoDecrypt.setOnClickListener { processCrypto(isEncrypt = false) }

        binding.btnCopyCryptoOutput.setOnClickListener {
            val text = binding.tvCryptoResult.text.toString()
            if (text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crypto Result", text))
                Toast.makeText(requireContext(), "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processCrypto(isEncrypt: Boolean) {
        val input = binding.etCryptoInput.text?.toString() ?: ""
        if (input.isBlank()) {
            Toast.makeText(requireContext(), "Please enter input text first!", Toast.LENGTH_SHORT).show()
            return
        }

        val checkedAlg = binding.rgCryptoAlgorithm.checkedRadioButtonId
        val keyStr = binding.etCryptoKey.text?.toString()?.trim().orEmpty()

        try {
            val resultText: String = when (checkedAlg) {
                R.id.rbXorObfuscator -> generateXorCode(input, keyStr)
                R.id.rbAes256 -> processAes256(input, keyStr, isEncrypt)
                R.id.rbBase64 -> if (isEncrypt) Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP) else String(Base64.decode(input, Base64.NO_WRAP))
                R.id.rbUrlEncode -> if (isEncrypt) URLEncoder.encode(input, "UTF-8") else URLDecoder.decode(input, "UTF-8")
                R.id.rbHashGen -> generateHashes(input)
                else -> ""
            }

            binding.tvCryptoResult.text = resultText
            binding.cardCryptoOutput.visibility = View.VISIBLE

            // Auto-scroll ke hasil output
            binding.converterScrollView.postDelayed({
                if (_binding != null) {
                    binding.converterScrollView.smoothScrollTo(0, binding.cardCryptoOutput.top)
                }
            }, 100)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Crypto Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateXorCode(input: String, keyStr: String): String {
        val keyHex = if (keyStr.startsWith("0x", ignoreCase = true)) {
            keyStr.substring(2).toIntOrNull(16) ?: 0x5A
        } else {
            keyStr.toIntOrNull() ?: 0x5A
        }
        val keyByte = keyHex.toByte()

        val inputBytes = input.toByteArray(Charsets.UTF_8)
        val encryptedHexList = inputBytes.map { "0x%02X".format(it.toInt() xor keyByte.toInt() and 0xFF) }

        val formattedHexArray = encryptedHexList.chunked(8).joinToString("\n    ") { it.joinToString(", ") }

        return """
// ====================================================================
// MonToolkit Generated C++ XOR String Obfuscator
// Original Length: ${inputBytes.size} bytes
// ====================================================================

static const uint8_t ENCRYPTED_STR[] = {
    $formattedHexArray
};
static const size_t STR_LEN = sizeof(ENCRYPTED_STR);
static const uint8_t XOR_KEY = 0x${"%02X".format(keyByte.toInt() and 0xFF)};

std::string getDecryptedString() {
    std::string s;
    s.reserve(STR_LEN);
    for (size_t i = 0; i < STR_LEN; ++i) {
        s += (char)(ENCRYPTED_STR[i] ^ XOR_KEY);
    }
    return s;
}

// --------------------------------------------------------------------
// Kotlin Decryptor Equivalent:
// val decrypted = String(byteArrayOf(${encryptedHexList.joinToString(", ")}).map { (it.toInt() xor 0x${"%02X".format(keyByte.toInt() and 0xFF)}).toByte() }.toByteArray())
// --------------------------------------------------------------------
        """.trimIndent()
    }

    private fun processAes256(input: String, passwordStr: String, isEncrypt: Boolean): String {
        if (passwordStr.isBlank()) throw IllegalArgumentException("Secret key password cannot be empty!")

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(passwordStr.toByteArray(Charsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        return if (isEncrypt) {
            val iv = ByteArray(16)
            java.security.SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))

            val cipherText = cipher.doFinal(input.toByteArray(Charsets.UTF_8))
            val combined = iv + cipherText
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } else {
            val combined = Base64.decode(input, Base64.NO_WRAP)
            if (combined.size <= 16) throw IllegalArgumentException("Invalid Base64 ciphertext format!")

            val iv = combined.copyOfRange(0, 16)
            val cipherText = combined.copyOfRange(16, combined.size)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(cipherText)
            String(decryptedBytes, Charsets.UTF_8)
        }
    }

    private fun generateHashes(input: String): String {
        fun hash(alg: String): String {
            val digest = MessageDigest.getInstance(alg).digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        return """
Input: "$input"

MD5    : ${hash("MD5")}
SHA-1  : ${hash("SHA-1")}
SHA-256: ${hash("SHA-256")}
        """.trimIndent()
    }

    // ====================================================================
    // TAB 1 LOGIC: FILE CONVERTER
    // ====================================================================

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ConversionService.ACTION_PROGRESS)
            addAction(ConversionService.ACTION_COMPLETE)
            addAction(ConversionService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(conversionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(conversionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(conversionReceiver)
        } catch (_: Exception) { }
    }

    private fun handleFileSelected(file: File) {
        if (file.length() == 0L) {
            showErrorDialog(getString(R.string.conv_error_unsupported_title), getString(R.string.conv_error_empty_file))
            return
        }

        if (!ConverterEngine.isSupported(file)) {
            showErrorDialog(
                getString(R.string.conv_error_unsupported_title),
                getString(R.string.conv_error_unsupported_message, file.extension.lowercase())
            )
            return
        }

        setFileUI(file)
    }

    private fun setFileUI(file: File) {
        selectedFile = file
        binding.tvFileName.text = file.name

        val sizeKb = (file.length() / 1024).toInt()
        val isZip = file.extension.equals("zip", ignoreCase = true)
        val isLarge = file.length() >= LARGE_FILE_THRESHOLD_BYTES

        binding.tvFileSize.text = when {
            isZip -> {
                val entryCount = countZipEntries(file)
                getString(R.string.conv_file_info_zip, sizeKb, entryCount)
            }
            isLarge -> getString(R.string.conv_file_info_large, sizeKb, file.extension.uppercase())
            else -> getString(R.string.conv_file_info, sizeKb, file.extension.uppercase())
        }

        val iconRes = when {
            isZip -> R.drawable.ic_type_archive
            file.extension.equals("apk", ignoreCase = true) -> R.drawable.ic_apk
            file.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> R.drawable.ic_type_image
            file.extension.lowercase() in listOf("mp3", "wav", "ogg", "m4a") -> R.drawable.ic_type_audio
            file.extension.lowercase() in listOf("mp4", "mov", "avi", "mkv") -> R.drawable.ic_type_video
            file.extension.equals("so", ignoreCase = true) -> R.drawable.ic_code_convert
            else -> R.drawable.ic_file
        }
        binding.ivFileIcon.setImageResource(iconRes)

        val safeName = file.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9]"), "_").lowercase()
        binding.etArrayName.setText("${safeName}_${file.extension}")

        binding.rbBase64Text.isEnabled = !isZip
        binding.rbBase64Text.alpha = if (isZip) 0.4f else 1f
        if (isZip && binding.rbBase64Text.isChecked) {
            binding.rbHexArray.isChecked = true
        }

        applyEncryptModeUI()
    }

    private enum class EncryptMode { OFF, THEN_CONVERT, ONLY }

    private fun getEncryptMode(): EncryptMode = when {
        binding.rbEncryptThenConvert.isChecked -> EncryptMode.THEN_CONVERT
        binding.rbEncryptOnly.isChecked -> EncryptMode.ONLY
        else -> EncryptMode.OFF
    }

    private fun applyEncryptModeUI() {
        val mode = getEncryptMode()

        binding.sectionFormat.visibility = if (mode == EncryptMode.ONLY) View.GONE else View.VISIBLE

        val lockFormat = mode == EncryptMode.THEN_CONVERT
        if (lockFormat) binding.rbHexArray.isChecked = true
        for (i in 0 until binding.rgFormat.childCount) {
            binding.rgFormat.getChildAt(i).isEnabled = !lockFormat
        }
        binding.etArrayName.isEnabled = !lockFormat
        binding.etArrayName.alpha = if (lockFormat) 0.5f else 1f

        binding.tvEncryptHint.visibility = if (mode == EncryptMode.OFF) View.GONE else View.VISIBLE
        binding.tvEncryptHint.text = when (mode) {
            EncryptMode.THEN_CONVERT -> getString(R.string.conv_encrypt_first_hint)
            EncryptMode.ONLY -> getString(R.string.conv_encrypt_only_hint)
            EncryptMode.OFF -> ""
        }
    }

    private fun countZipEntries(file: File): Int {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().toList().count { !it.isDirectory }
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun onConvertClicked() {
        if (isConverting) return

        val file = selectedFile
        if (file == null) {
            Toast.makeText(requireContext(), getString(R.string.conv_error_no_file), Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkStoragePermission()) return

        val mode = getEncryptMode()
        val isSo = file.extension.equals("so", ignoreCase = true)
        if (mode != EncryptMode.OFF && !isSo) {
            showErrorDialog(getString(R.string.conv_error_unsupported_title), getString(R.string.conv_encrypt_needs_so))
            return
        }

        val format = getSelectedFormat()

        val estimatedOutput = if (mode == EncryptMode.ONLY) {
            file.length() + 16L
        } else {
            ConverterEngine.estimateOutputBytes(file.length(), format)
        }
        val outDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/Converter")
        val availableSpace = (if (outDir.exists()) outDir else Environment.getExternalStorageDirectory()).usableSpace
        if (estimatedOutput > availableSpace - (50L * 1024 * 1024)) {
            showErrorDialog(
                getString(R.string.conv_error_generic_title),
                getString(
                    R.string.conv_error_insufficient_space,
                    estimatedOutput / (1024 * 1024),
                    availableSpace / (1024 * 1024)
                )
            )
            return
        }

        if (file.length() >= LARGE_FILE_THRESHOLD_BYTES) {
            val sizeMb = (file.length() / (1024 * 1024)).toInt()
            val message = if (format == ConverterEngine.OutputFormat.HEX_ARRAY && mode != EncryptMode.ONLY) {
                getString(R.string.conv_large_file_message, sizeMb) + "\n\n" + getString(R.string.conv_suggest_base64)
            } else {
                getString(R.string.conv_large_file_message, sizeMb)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.conv_large_file_title)
                .setMessage(message)
                .setPositiveButton(R.string.conv_large_file_continue) { _, _ -> startConversion(file) }
                .setNegativeButton(R.string.conv_large_file_cancel, null)
                .show()
        } else {
            startConversion(file)
        }
    }

    private fun getSelectedFormat(): ConverterEngine.OutputFormat = when {
        binding.rbHexArray.isChecked -> ConverterEngine.OutputFormat.HEX_ARRAY
        binding.rbBase64Header.isChecked -> ConverterEngine.OutputFormat.BASE64_HEADER
        else -> ConverterEngine.OutputFormat.BASE64_TEXT
    }

    private fun startConversion(file: File) {
        ensureNotificationPermission()

        var arrayName = binding.etArrayName.text.toString().trim()
        if (arrayName.isEmpty()) arrayName = "embedded_data"

        val outDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/Converter")
        if (!outDir.exists() && !outDir.mkdirs()) {
            showErrorDialog(getString(R.string.conv_error_generic_title), getString(R.string.conv_error_create_folder))
            return
        }

        val format = getSelectedFormat()
        val isZip = file.extension.equals("zip", ignoreCase = true)
        val mode = getEncryptMode()
        val modeExtra = when (mode) {
            EncryptMode.THEN_CONVERT -> ConversionService.MODE_THEN_CONVERT
            EncryptMode.ONLY -> ConversionService.MODE_ONLY
            EncryptMode.OFF -> ConversionService.MODE_OFF
        }

        isConverting = true
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.btnConvert.isEnabled = false
        binding.btnConvert.alpha = 0.6f

        val serviceIntent = Intent(requireContext(), ConversionService::class.java).apply {
            putExtra(ConversionService.EXTRA_INPUT_PATH, file.absolutePath)
            putExtra(ConversionService.EXTRA_OUTPUT_DIR, outDir.absolutePath)
            putExtra(ConversionService.EXTRA_ARRAY_NAME, arrayName)
            putExtra(ConversionService.EXTRA_FORMAT, format.name)
            putExtra(ConversionService.EXTRA_IS_ZIP, isZip)
            putExtra(ConversionService.EXTRA_ENCRYPT_MODE, modeExtra)
        }
        ContextCompat.startForegroundService(requireContext(), serviceIntent)
    }

    private fun updateProgressUI(percent: Int) {
        if (_binding == null) return
        binding.progressBar.progress = percent
    }

    private fun onConversionComplete(outputName: String, outputName2: String, mode: String) {
        isConverting = false
        if (_binding == null) return

        binding.layoutProgress.visibility = View.GONE
        binding.btnConvert.isEnabled = true
        binding.btnConvert.alpha = 1f

        if (mode == ConversionService.MODE_THEN_CONVERT) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.conv_done_title_encrypt)
                .setMessage(R.string.conv_done_body_encrypt)
                .setPositiveButton(R.string.conv_done_ok, null)
                .show()
            return
        }

        if (mode == ConversionService.MODE_ONLY) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.conv_done_title_encrypt_only)
                .setMessage(getString(R.string.conv_done_body_encrypt_only, outputName, outputName2))
                .setPositiveButton(R.string.conv_done_ok, null)
                .show()
            return
        }

        val arrayName = binding.etArrayName.text.toString().trim().ifEmpty { "embedded_data" }
        val isZip = selectedFile?.extension?.equals("zip", ignoreCase = true) == true
        val isHex = binding.rbHexArray.isChecked
        val isBase64Header = binding.rbBase64Header.isChecked

        val usageExample = when {
            isZip -> getString(R.string.conv_usage_zip, outputName)
            isHex -> getString(R.string.conv_usage_hex, outputName, arrayName)
            isBase64Header -> getString(R.string.conv_usage_base64_header, outputName, arrayName)
            else -> getString(R.string.conv_usage_base64_text, outputName)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.conv_done_title)
            .setMessage(getString(R.string.conv_done_body, outputName, usageExample))
            .setPositiveButton(R.string.conv_done_ok, null)
            .show()
    }

    private fun onConversionError(message: String) {
        isConverting = false
        if (_binding == null) return

        binding.layoutProgress.visibility = View.GONE
        binding.btnConvert.isEnabled = true
        binding.btnConvert.alpha = 1f

        showErrorDialog(getString(R.string.conv_error_generic_title), message)
    }

    private fun showErrorDialog(title: String, message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            var name = "temp_file_to_convert"
            cursor?.use {
                if (it.moveToFirst()) {
                    name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
            val tempFile = File(requireContext().cacheDir, name)

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        selectedFile?.let { if (it.parentFile == requireContext().cacheDir) it.delete() }
        _binding = null
    }
}