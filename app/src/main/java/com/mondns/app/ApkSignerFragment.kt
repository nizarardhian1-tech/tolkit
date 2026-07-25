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
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.mondns.app.databinding.FragmentApkSignerBinding
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

class ApkSignerFragment : Fragment() {

    private var _binding: FragmentApkSignerBinding? = null
    private val binding get() = _binding!!

    private var selectedApkFile: File? = null
    private var selectedApkPath: String? = null
    private var selectedKeystoreFile: File? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleApkUri(uri) }
        }
    }

    private val keystorePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleKeystoreUri(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApkSignerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGenerateKeystore.setOnClickListener { startGenerateKeystore() }
        binding.cardPickApk.setOnClickListener {
            apkPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        binding.cardPickKeystore.setOnClickListener {
            keystorePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        binding.btnSignApk.setOnClickListener { startSignApk() }
        binding.btnGeneratePassword.setOnClickListener { fillRandomPassword() }
        binding.headerGenAdvanced.setOnClickListener { toggleGenAdvanced() }

        scrollToViewOnFocus(
            binding.etKeystoreName,
            binding.etGenAlias,
            binding.etGenPassword,
            binding.etCommonName,
            binding.etOrgName,
            binding.etOrgUnit,
            binding.etCity,
            binding.etState,
            binding.etCountryCode,
            binding.etSignAlias,
            binding.etSignPassword,
            binding.etOutputName
        )
    }

    private fun scrollToViewOnFocus(vararg fields: EditText) {
        fields.forEach { field ->
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.postDelayed({
                        val scrollView = binding.apkSignerScrollView
                        val viewPos = IntArray(2)
                        val scrollPos = IntArray(2)
                        v.getLocationOnScreen(viewPos)
                        scrollView.getLocationOnScreen(scrollPos)
                        val targetY = scrollView.scrollY + (viewPos[1] - scrollPos[1]) - 120
                        scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }, 250)
                }
            }
        }
    }

    private fun toggleGenAdvanced() {
        val container = binding.containerGenAdvanced
        val expanding = container.visibility != View.VISIBLE
        container.visibility = if (expanding) View.VISIBLE else View.GONE
        binding.ivGenAdvancedChevron.animate()
            .rotation(if (expanding) 180f else 0f)
            .setDuration(150)
            .start()
    }

    private fun fillRandomPassword() {
        val charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*"
        val random = SecureRandom()
        val password = buildString {
            repeat(16) { append(charset[random.nextInt(charset.length)]) }
        }
        binding.etGenPassword.setText(password)
        binding.etGenPassword.setSelection(password.length)
    }

    private fun selectedKeySize(): Int = when (binding.toggleKeySize.checkedButtonId) {
        R.id.btnKeySize3072 -> 3072
        R.id.btnKeySize4096 -> 4096
        else -> 2048
    }

    /** Langsung buka izin tanpa Toast delay */
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
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2001)
                return false
            }
        }
        return true
    }

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
            selectedApkPath = tempFile.absolutePath
            binding.tvApkName.text = name
            loadApkIcon(tempFile.absolutePath)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApkIcon(apkPath: String) {
        try {
            val pm = requireContext().packageManager
            val info = pm.getPackageArchiveInfo(apkPath, 0)
            if (info != null) {
                info.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = apkPath
                    appInfo.publicSourceDir = apkPath
                    val icon = appInfo.loadIcon(pm)
                    binding.ivApkIcon.setImageDrawable(icon)
                    binding.ivApkIcon.imageTintList = null
                    return
                }
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

    private fun handleKeystoreUri(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "keystore.p12"
            val tempFile = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            selectedKeystoreFile = tempFile
            binding.tvKeystoreName.text = name
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

    private fun startGenerateKeystore() {
        if (!checkStoragePermission()) return

        val keyName = binding.etKeystoreName.text?.toString()?.trim().orEmpty()
        val alias = binding.etGenAlias.text?.toString()?.trim().orEmpty()
        val passwordStr = binding.etGenPassword.text?.toString().orEmpty()
        val commonName = binding.etCommonName.text?.toString()?.trim().orEmpty().ifEmpty { "MonToolkit" }
        val orgName = binding.etOrgName.text?.toString()?.trim()
        val orgUnit = binding.etOrgUnit.text?.toString()?.trim()
        val city = binding.etCity.text?.toString()?.trim()
        val state = binding.etState.text?.toString()?.trim()
        val countryCode = binding.etCountryCode.text?.toString()?.trim()
        val keySize = selectedKeySize()

        if (keyName.isEmpty() || alias.isEmpty() || passwordStr.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill in name, alias, and password fields.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvGenerateResult.visibility = View.GONE
        showProgress("Generating RSA $keySize-bit key pair...")

        thread {
            try {
                val outDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/ApkSigner/keys")
                val outputFile = File(outDir, "$keyName.p12")
                val password = passwordStr.toCharArray()

                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.tvProgressLabel.text = "Generating X.509 certificate..."
                    }
                }

                val result = KeystoreManager.generate(
                    outputFile = outputFile,
                    alias = alias,
                    storePassword = password,
                    commonName = commonName,
                    organizationName = orgName,
                    organizationalUnit = orgUnit,
                    locality = city,
                    state = state,
                    countryCode = countryCode,
                    keySize = keySize
                )

                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.tvProgressLabel.text = "Saving PKCS12 keystore..."
                    }
                }
                Thread.sleep(150)

                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread
                    hideProgress()
                    
                    binding.tvGenerateResult.alpha = 0f
                    binding.tvGenerateResult.visibility = View.VISIBLE
                    binding.tvGenerateResult.text = getString(R.string.apk_signer_generate_success, result.file.absolutePath)
                    binding.tvGenerateResult.animate().alpha(1f).setDuration(250).start()

                    selectedKeystoreFile = result.file
                    binding.tvKeystoreName.text = result.file.name
                    binding.etSignAlias.setText(alias)
                    binding.etSignPassword.setText(passwordStr)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread
                    hideProgress()
                    binding.tvGenerateResult.alpha = 0f
                    binding.tvGenerateResult.visibility = View.VISIBLE
                    binding.tvGenerateResult.text = getString(R.string.apk_signer_generate_failed, e.localizedMessage ?: e.toString())
                    binding.tvGenerateResult.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
    }

    private fun startSignApk() {
        if (!checkStoragePermission()) return

        val apkFile = selectedApkFile
        val keystoreFile = selectedKeystoreFile
        val alias = binding.etSignAlias.text?.toString()?.trim().orEmpty()
        val passwordStr = binding.etSignPassword.text?.toString().orEmpty()
        val outputName = binding.etOutputName.text?.toString()?.trim().orEmpty().ifEmpty { "signed-app" }
        val v3Enabled = binding.switchV3.isChecked
        val v4Enabled = binding.switchV4.isChecked

        if (apkFile == null) {
            Toast.makeText(requireContext(), getString(R.string.apk_signer_no_apk), Toast.LENGTH_SHORT).show()
            return
        }
        if (keystoreFile == null) {
            Toast.makeText(requireContext(), getString(R.string.apk_signer_no_keystore), Toast.LENGTH_SHORT).show()
            return
        }
        if (alias.isEmpty() || passwordStr.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter keystore alias and password.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvSignResult.visibility = View.GONE
        showProgress(getString(R.string.apk_signer_signing))

        thread {
            try {
                val password = passwordStr.toCharArray()
                val loadedKey = KeystoreManager.loadPkcs12(
                    file = keystoreFile,
                    storePassword = password,
                    alias = alias
                )

                val outDir = File(Environment.getExternalStorageDirectory(), "MonToolKit/ApkSigner/signed")
                val outputFile = File(outDir, "$outputName.apk")

                val result = ApkSignerEngine.sign(
                    inputApk = apkFile,
                    outputApk = outputFile,
                    privateKey = loadedKey.privateKey,
                    certChain = loadedKey.certChain,
                    v3Enabled = v3Enabled,
                    v4Enabled = v4Enabled
                )

                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread
                    hideProgress()
                    binding.tvSignResult.alpha = 0f
                    binding.tvSignResult.visibility = View.VISIBLE
                    val idsigNote = if (result.idsigFile != null) "\n+ ${result.idsigFile.name}" else ""
                    binding.tvSignResult.text = getString(R.string.apk_signer_sign_success, result.outputFile.absolutePath) + idsigNote
                    binding.tvSignResult.animate().alpha(1f).setDuration(250).start()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread
                    hideProgress()
                    binding.tvSignResult.alpha = 0f
                    binding.tvSignResult.visibility = View.VISIBLE
                    binding.tvSignResult.text = getString(R.string.apk_signer_sign_failed, e.localizedMessage ?: e.toString())
                    binding.tvSignResult.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
    }

    private fun showProgress(label: String) {
        binding.layoutProgress.apply {
            alpha = 0f
            translationY = 16f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
        binding.tvProgressLabel.text = label
        binding.btnGenerateKeystore.isEnabled = false
        binding.btnGenerateKeystore.alpha = 0.6f
        binding.btnSignApk.isEnabled = false
        binding.btnSignApk.alpha = 0.6f

        binding.apkSignerScrollView.postDelayed({
            if (_binding != null) {
                binding.apkSignerScrollView.smoothScrollTo(0, binding.layoutProgress.bottom)
            }
        }, 100)
    }

    private fun hideProgress() {
        binding.layoutProgress.animate()
            .alpha(0f)
            .translationY(16f)
            .setDuration(150)
            .withEndAction {
                if (_binding != null) {
                    binding.layoutProgress.visibility = View.GONE
                }
            }
            .start()

        binding.btnGenerateKeystore.isEnabled = true
        binding.btnGenerateKeystore.alpha = 1.0f
        binding.btnSignApk.isEnabled = true
        binding.btnSignApk.alpha = 1.0f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}