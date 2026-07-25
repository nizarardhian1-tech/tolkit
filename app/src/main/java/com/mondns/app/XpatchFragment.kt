package com.mondns.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.DialogPatchConsoleBinding
import com.mondns.app.databinding.FragmentXpatchBinding
import com.mondns.app.databinding.ItemAppBinding
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class XpatchFragment : Fragment() {
    companion object {
        private const val XPATCH_NOTIF_CHANNEL_ID = "xpatch_channel"
        private const val XPATCH_NOTIF_ID = 2001
    }

    private var _binding: FragmentXpatchBinding? = null
    private var selectedDeviceProfile: DeviceProfile? = null  // null = tidak ada spoof
    private val binding get() = _binding!!

    data class PickedApk(val uri: Uri, val displayName: String, val icon: Drawable? = null, val packageName: String? = null)
    data class AppItem(val name: String, val packageName: String, val icon: Drawable, val apkPath: String)

    private val selectedTargets = mutableListOf<PickedApk>()
    private val selectedModules = mutableListOf<PickedApk>()

    private var consoleBinding: DialogPatchConsoleBinding? = null
    private var consoleDialog: BottomSheetDialog? = null
    private var pendingInstallApk: File? = null
    private var conflictPackageName: String? = null

    private val consoleUninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstallApk
            val pkg = conflictPackageName
            pendingInstallApk = null
            conflictPackageName = null
            
            if (apk != null && pkg != null && _binding != null) {
                if (!isPackageInstalled(requireContext(), pkg)) {
                    consoleLog(getString(R.string.patch_console_log_installing))
                    launchApkInstaller(apk)
                } else {
                    consoleLog("⚠ Installation aborted: Old version of app has not been removed.")
                    Toast.makeText(requireContext(), "Installation aborted: Old version of app has not been removed.", Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private val targetFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = extractUrisFromResult(result.data)
            val valid = mutableListOf<PickedApk>()
            val invalidNames = mutableListOf<String>()

            for (uri in picked) {
                val displayName = uri.path?.substringAfterLast('/') ?: "selected_app.apk"
                if (!isValidApkFile(uri)) {
                    invalidNames.add(displayName)
                    continue
                }
                val icon = getApkIcon(requireContext(), uri)
                valid.add(PickedApk(uri, displayName, icon))
            }

            if (valid.isNotEmpty()) {
                selectedTargets.clear()
                selectedTargets.addAll(valid)
                refreshTargetSummary()
            }

            if (invalidNames.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.xpatch_invalid_apk_format, invalidNames.joinToString(", ")),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val moduleFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = extractUrisFromResult(result.data)
            val valid = mutableListOf<PickedApk>()
            val invalidNames = mutableListOf<String>()
            val unconfirmedNames = mutableListOf<String>()

            for (uri in picked) {
                val displayName = uri.path?.substringAfterLast('/') ?: "module.apk"
                if (!isValidApkFile(uri)) {
                    invalidNames.add(displayName)
                    continue
                }
                if (!looksLikeXposedModule(uri)) {
                    unconfirmedNames.add(displayName)
                }
                val icon = getApkIcon(requireContext(), uri)
                valid.add(PickedApk(uri, displayName, icon))
            }

            if (valid.isNotEmpty()) {
                selectedModules.clear()
                selectedModules.addAll(valid)
                refreshModuleSummary()
            }

            if (invalidNames.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.xpatch_invalid_apk_format, invalidNames.joinToString(", ")),
                    Toast.LENGTH_LONG
                ).show()
            } else if (unconfirmedNames.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.xpatch_module_not_confirmed, unconfirmedNames.joinToString(", ")),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun isValidApkFile(uri: Uri): Boolean {
        return try {
            val tempFile = File(requireContext().cacheDir, "validate_${System.currentTimeMillis()}.apk")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val info = requireContext().packageManager.getPackageArchiveInfo(tempFile.path, 0)
            tempFile.delete()
            info != null
        } catch (e: Exception) {
            false
        }
    }

    private fun looksLikeXposedModule(uri: Uri): Boolean {
        return try {
            val tempFile = File(requireContext().cacheDir, "modcheck_${System.currentTimeMillis()}.apk")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val pm = requireContext().packageManager
            val info = pm.getPackageArchiveInfo(tempFile.path, PackageManager.GET_META_DATA)
            val metadata = info?.applicationInfo?.metaData
            tempFile.delete()
            metadata?.getBoolean("xposedmodule") == true ||
                metadata?.containsKey("xposedsharedprefs") == true ||
                metadata?.containsKey("xposedminversion") == true
        } catch (e: Exception) {
            true 
        }
    }

    private fun extractUrisFromResult(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val clipData = data.clipData
        if (clipData != null) {
            return (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        }
        return listOfNotNull(data.data)
    }

    private fun getApkIcon(context: Context, uri: Uri): Drawable? {
        return try {
            val pm = context.packageManager
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val info = pm.getPackageArchiveInfo(path, 0)
                info?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = path
                    appInfo.publicSourceDir = path
                    appInfo.loadIcon(pm)
                }
            } else {
                val tempFile = File(context.cacheDir, "temp_icon_${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val info = pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
                val icon = info?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = tempFile.absolutePath
                    appInfo.publicSourceDir = tempFile.absolutePath
                    appInfo.loadIcon(pm)
                }
                tempFile.delete()
                icon
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentXpatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun setPatchButtonBusy(busy: Boolean, labelRes: Int = R.string.xpatch_processing_button) {
        if (_binding == null) return
        binding.btnPatch.isEnabled = !busy
        binding.btnPatch.alpha = if (busy) 0.55f else 1f
        binding.tvBtnPatchLabel.text = if (busy) getString(labelRes) else getString(R.string.xpatch_build_button)
        binding.pbBtnPatchBusy.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /**
     * Update UI toggle berdasarkan level yang dipilih
     * - trapPrctl: otomatis disable & unchecked jika Level 4
     * - normalizeTiming: kasih peringatan visual
     */
    private fun updateExperimentalToggles(level: Int) {
        if (_binding == null) return
        val isLevel4 = level == 4

        // trapPrctl: disable & paksa false jika Level 4
        binding.switchTrapPrctl.isEnabled = !isLevel4
        if (isLevel4) {
            binding.switchTrapPrctl.isChecked = false
            binding.switchTrapPrctl.alpha = 0.5f
            binding.tvTrapPrctlHint.visibility = View.VISIBLE
        } else {
            binding.switchTrapPrctl.alpha = 1f
            binding.tvTrapPrctlHint.visibility = View.GONE
        }

        // normalizeTiming: tetap enable tapi kasih peringatan jika di-check
        if (binding.switchNormalizeTiming.isChecked) {
            binding.tvNormalizeTimingHint.visibility = View.VISIBLE
        } else {
            binding.tvNormalizeTimingHint.visibility = View.GONE
        }

        // Tambahkan listener untuk normalizeTiming
        binding.switchNormalizeTiming.setOnCheckedChangeListener { _, isChecked ->
            binding.tvNormalizeTimingHint.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("targetApkPath")?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val uri = Uri.fromFile(file)
                val icon = getApkIcon(requireContext(), uri)
                selectedTargets.add(PickedApk(uri, file.name, icon))
            }
        }

        binding.cardFileSelect.setOnClickListener { showSourceSelectionDialog(isTarget = true) }
        binding.cardModuleSelect.setOnClickListener { showSourceSelectionDialog(isTarget = false) }

        // Device Spoof: card/button dengan tag "card_device_spoof" di layout XML
        // Tambahkan di fragment_xpatch.xml (di bawah Advanced section):
        //   <com.google.android.material.card.MaterialCardView
        //       android:id="@+id/cardDeviceSpoof"
        //       ... />
        // Atau gunakan view.findViewWithTag untuk backward compat tanpa binding codegen ulang:
        binding.root.findViewWithTag<View>("card_device_spoof")?.setOnClickListener {
            showDeviceSpoofDialog()
        }
        // Jika kamu tambah id cardDeviceSpoof ke binding, uncomment baris di bawah:
        binding.cardDeviceSpoof.setOnClickListener { showDeviceSpoofDialog() }

        // 🔥 LOGIKA UI CERDAS: Pantau perubahan Level Bypass
        binding.rgBypassLevel.setOnCheckedChangeListener { _, checkedId ->
            val level = when (checkedId) {
                R.id.rbBypassPM -> 1
                R.id.rbBypassLibc -> 2
                R.id.rbBypassExtreme -> 3
                R.id.rbBypassSeccomp -> 4
                else -> 0
            }
            updateExperimentalToggles(level)
        }

        // Jalankan sekali untuk set initial state
        val initialLevel = when (binding.rgBypassLevel.checkedRadioButtonId) {
            R.id.rbBypassPM -> 1
            R.id.rbBypassLibc -> 2
            R.id.rbBypassExtreme -> 3
            R.id.rbBypassSeccomp -> 4
            else -> 0
        }
        updateExperimentalToggles(initialLevel)

        binding.btnPatch.setOnClickListener {
            if (selectedTargets.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.xpatch_error_no_target), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!checkStoragePermission()) return@setOnClickListener

            val signatureBypassLevel = when (binding.rgBypassLevel.checkedRadioButtonId) {
                R.id.rbBypassPM -> 1
                R.id.rbBypassLibc -> 2
                R.id.rbBypassExtreme -> 3
                R.id.rbBypassSeccomp -> 4
                else -> 0
            }

            val overrideCodeStr = binding.etOverrideCode.text?.toString()
            val overrideCode = if (!overrideCodeStr.isNullOrBlank()) overrideCodeStr.toIntOrNull() else null
            val overrideName = binding.etOverrideName.text?.toString()

            val injectProvider = binding.switchInjectProvider.isChecked
            val useMicroG = binding.switchMicroG.isChecked

            runConflictCheckThenPatch(
                bypassLevel = signatureBypassLevel,
                overrideCode = overrideCode,
                overrideName = overrideName,
                injectProvider = injectProvider,
                useMicroG = useMicroG
            )
        }

        refreshTargetSummary()
        refreshModuleSummary()
    }

    private fun runConflictCheckThenPatch(
        bypassLevel: Int,
        overrideCode: Int?,
        overrideName: String?,
        injectProvider: Boolean,
        useMicroG: Boolean
    ) {
        setPatchButtonBusy(true, R.string.xpatch_checking_conflicts_button)
        thread {
            val allWarnings = mutableListOf<Pair<String, List<XpatchEngine.ConflictWarning>>>()
            for (target in selectedTargets) {
                try {
                    val originalExt = target.displayName.substringAfterLast('.', "apk")
                    val tempCheckFile = File(requireContext().cacheDir, "conflict_check_${System.currentTimeMillis()}.$originalExt")
                    requireContext().contentResolver.openInputStream(target.uri)?.use { input ->
                        tempCheckFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val warnings = XpatchEngine.detectConflicts(requireContext(), tempCheckFile)
                    if (warnings.isNotEmpty()) {
                        allWarnings.add(target.displayName to warnings)
                    }
                    tempCheckFile.delete()
                } catch (_: Exception) { }
            }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (allWarnings.isEmpty()) {
                    startBatchPatchingProcess(
                        bypassLevel, overrideCode, overrideName,
                        injectProvider, useMicroG
                    )
                } else {
                    setPatchButtonBusy(false)
                    showConflictDialog(
                        allWarnings, bypassLevel, overrideCode, overrideName,
                        injectProvider, useMicroG
                    )
                }
            }
        }
    }

    private fun showConflictDialog(
        warnings: List<Pair<String, List<XpatchEngine.ConflictWarning>>>,
        bypassLevel: Int,
        overrideCode: Int?,
        overrideName: String?,
        injectProvider: Boolean,
        useMicroG: Boolean
    ) {
        val message = StringBuilder()
        for ((appName, warningList) in warnings) {
            message.append("📦 $appName\n")
            for (w in warningList) {
                message.append("  ⚠️ ${w.title}\n")
                message.append("     ${w.detail}\n\n")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.xpatch_conflicts_title))
            .setMessage(message.toString().trim())
            .setPositiveButton(getString(R.string.xpatch_continue_anyway)) { _, _ ->
                startBatchPatchingProcess(
                    bypassLevel, overrideCode, overrideName,
                    injectProvider, useMicroG
                )
            }
            .setNegativeButton(getString(R.string.xpatch_cancel), null)
            .show()
    }

    private fun showDeviceSpoofDialog() {
    val profiles = DeviceProfiles.ALL
    val labels = profiles.map { p ->
        if (p == DeviceProfiles.CUSTOM) p.label
        else "${p.label}\n${p.chipset}"
    }.toTypedArray()

    val currentIdx = profiles.indexOfFirst { it == selectedDeviceProfile }
        .coerceAtLeast(0)

    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Device / GPU Profile")
        .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
            val picked = profiles[which]
            selectedDeviceProfile = if (picked == DeviceProfiles.CUSTOM) null else picked
            // Update label di UI
            binding.tvDeviceSpoofLabel.text = if (picked == DeviceProfiles.CUSTOM) {
                "Off"
            } else {
                picked.label
            }
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

    private fun showSourceSelectionDialog(isTarget: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isTarget) getString(R.string.xpatch_target_selection_title) else getString(R.string.xpatch_module_selection_title))
            .setItems(arrayOf(getString(R.string.xpatch_choose_installed_apps), getString(R.string.xpatch_choose_apk_files))) { _, which ->
                if (which == 0) {
                    showAppListBottomSheet(isTarget)
                } else {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    if (isTarget) targetFilePicker.launch(intent) else moduleFilePicker.launch(intent)
                }
            }
            .show()
    }

    private fun showAppListBottomSheet(isTarget: Boolean) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.fragment_apk_extractor, null)
        dialog.setContentView(view)

        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val rvApps = view.findViewById<RecyclerView>(R.id.rvApps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvSelectionCount = view.findViewById<android.widget.TextView>(R.id.tvSelectionCount)
        val btnDoneMulti = view.findViewById<MaterialButton>(R.id.btnDoneMulti)

        tvSelectionCount.visibility = View.VISIBLE
        btnDoneMulti.visibility = View.VISIBLE

        rvApps.layoutManager = LinearLayoutManager(requireContext())
        rvApps.itemAnimator = null
        rvApps.setHasFixedSize(true)

        val items = mutableListOf<AppItem>()
        val filteredItems = mutableListOf<AppItem>()
        val pickedInThisSheet = mutableListOf<AppItem>()

        val currentPaths = if (isTarget) {
            selectedTargets.mapNotNull { it.uri.path }
        } else {
            selectedModules.mapNotNull { it.uri.path }
        }

        fun updateCounter() {
            tvSelectionCount.text = getString(R.string.xpatch_selected_count_format, pickedInThisSheet.size)
        }

        val adapter = AppSelectionAdapter(filteredItems, pickedInThisSheet) { selectedApp ->
            if (selectedApp in pickedInThisSheet) {
                pickedInThisSheet.remove(selectedApp)
            } else {
                pickedInThisSheet.add(selectedApp)
            }
            updateCounter()
        }
        rvApps.adapter = adapter

        fun updateFilteredItems(newItems: List<AppItem>) {
            val oldItems = filteredItems.toList()
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    oldItems[oldPos].packageName == newItems[newPos].packageName
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    oldItems[oldPos] == newItems[newPos]
            })
            filteredItems.clear()
            filteredItems.addAll(newItems)
            diffResult.dispatchUpdatesTo(adapter)
        }

        btnDoneMulti.setOnClickListener {
            if (pickedInThisSheet.isEmpty()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            if (isTarget) {
                selectedTargets.clear()
                for (app in pickedInThisSheet) {
                    selectedTargets.add(PickedApk(Uri.fromFile(File(app.apkPath)), app.name, app.icon, app.packageName))
                }
                refreshTargetSummary()
            } else {
                selectedModules.clear()
                for (app in pickedInThisSheet) {
                    selectedModules.add(PickedApk(Uri.fromFile(File(app.apkPath)), app.name, app.icon, app.packageName))
                }
                refreshModuleSummary()
            }
            dialog.dismiss()
        }

        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        thread {
            val pm = requireContext().packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            val tempList = mutableListOf<AppItem>()

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                if (!isTarget) {
                    val metadata = appInfo.metaData
                    val isXposed = metadata?.getBoolean("xposedmodule") == true ||
                                   metadata?.containsKey("xposedsharedprefs") == true
                    if (!isXposed) continue
                } else {
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                        continue
                    }
                }

                val name = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                val path = appInfo.sourceDir
                tempList.add(AppItem(name, pkg.packageName, icon, path))
            }

            tempList.sortBy { it.name.lowercase() }

            activity?.runOnUiThread {
                if (_binding == null || !dialog.isShowing) return@runOnUiThread
                items.clear()
                items.addAll(tempList)

                for (item in tempList) {
                    if (item.apkPath in currentPaths) {
                        pickedInThisSheet.add(item)
                    }
                }
                updateCounter()

                updateFilteredItems(tempList)
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                val newFiltered = if (query.isEmpty()) {
                    items.toList()
                } else {
                    items.filter { it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
                }
                updateFilteredItems(newFiltered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.show()
    }

    private fun refreshTargetSummary() {
        if (_binding == null) return
        val size = selectedTargets.size

        when (size) {
            0 -> binding.tvFileName.text = getString(R.string.xpatch_select_target)
            1 -> binding.tvFileName.text = selectedTargets[0].displayName
            else -> {
                val names = selectedTargets.take(2).joinToString(", ") { it.displayName } +
                    if (selectedTargets.size > 2) getString(R.string.xpatch_more_suffix_format, selectedTargets.size - 2) else ""
                binding.tvFileName.text = getString(R.string.xpatch_targets_selected_format, selectedTargets.size, names)
            }
        }

        val layout = binding.layoutTargetIconContainer
        val single = binding.ivTargetIcon
        val iconViews = listOf(binding.ivTargetIcon1, binding.ivTargetIcon2, binding.ivTargetIcon3)

        if (size == 0) {
            single.visibility = View.VISIBLE
            single.setImageResource(R.drawable.ic_apk)
            single.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.colorPrimary)
            )
            iconViews.forEach { it.visibility = View.GONE }
        } else if (size == 1) {
            single.visibility = View.VISIBLE
            val target = selectedTargets[0]
            if (target.icon != null) {
                single.setImageDrawable(target.icon)
                single.imageTintList = null
            } else {
                single.setImageResource(R.drawable.ic_apk)
                single.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                )
            }
            iconViews.forEach { it.visibility = View.GONE }
        } else {
            single.visibility = View.GONE
            iconViews.forEachIndexed { index, view ->
                if (index < size) {
                    view.visibility = View.VISIBLE
                    val target = selectedTargets[index]
                    if (target.icon != null) {
                        view.setImageDrawable(target.icon)
                        view.imageTintList = null
                    } else {
                        view.setImageResource(R.drawable.ic_apk)
                        view.imageTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                        )
                    }
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshModuleSummary() {
        if (_binding == null) return
        val size = selectedModules.size

        when (size) {
            0 -> {
                binding.tvModuleName.text = getString(R.string.xpatch_select_module)
                binding.tvModeHint.text = getString(R.string.xpatch_mode_hint_none)
            }
            1 -> {
                binding.tvModuleName.text = selectedModules[0].displayName
                binding.tvModeHint.text = getString(R.string.xpatch_mode_hint_single)
            }
            else -> {
                val names = selectedModules.take(2).joinToString(", ") { it.displayName } +
                    if (selectedModules.size > 2) getString(R.string.xpatch_more_suffix_format, selectedModules.size - 2) else ""
                binding.tvModuleName.text = getString(R.string.xpatch_modules_selected_format, selectedModules.size, names)
                binding.tvModeHint.text = getString(R.string.xpatch_mode_hint_multi_format, selectedModules.size)
            }
        }

        val single = binding.ivModuleIcon
        val iconViews = listOf(binding.ivModuleIcon1, binding.ivModuleIcon2, binding.ivModuleIcon3)

        if (size == 0) {
            single.visibility = View.VISIBLE
            single.setImageResource(R.drawable.ic_code_convert)
            single.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.colorPrimary)
            )
            iconViews.forEach { it.visibility = View.GONE }
        } else if (size == 1) {
            single.visibility = View.VISIBLE
            val module = selectedModules[0]
            if (module.icon != null) {
                single.setImageDrawable(module.icon)
                single.imageTintList = null
            } else {
                single.setImageResource(R.drawable.ic_code_convert)
                single.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                )
            }
            iconViews.forEach { it.visibility = View.GONE }
        } else {
            single.visibility = View.GONE
            iconViews.forEachIndexed { index, view ->
                if (index < size) {
                    view.visibility = View.VISIBLE
                    val module = selectedModules[index]
                    if (module.icon != null) {
                        view.setImageDrawable(module.icon)
                        view.imageTintList = null
                    } else {
                        view.setImageResource(R.drawable.ic_code_convert)
                        view.imageTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                        )
                    }
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun startBatchPatchingProcess(
        bypassLevel: Int,
        overrideCode: Int?,
        overrideName: String?,
        injectProvider: Boolean,
        useMicroG: Boolean
    ) {
        if (_binding == null) return
        ensureNotificationPermission()
        setPatchButtonBusy(true, R.string.xpatch_processing_button)
        showPatchConsole()

        thread {
            val tempModuleFiles = mutableListOf<File>()
            for ((index, module) in selectedModules.withIndex()) {
                val temp = File(requireContext().cacheDir, "module_$index.apk")
                
                if (module.packageName != null) {
                    val appInfo = requireContext().packageManager.getApplicationInfo(module.packageName, 0)
                    File(appInfo.sourceDir).inputStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                } else {
                    requireContext().contentResolver.openInputStream(module.uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                tempModuleFiles.add(temp)
            }

            val outputDirectory = File(Environment.getExternalStorageDirectory(), "MonToolKit/Xpatch")
            val results = mutableListOf<File>()
            val failures = mutableListOf<Pair<String, String>>()
            val totalTargets = selectedTargets.size

            for ((targetIndex, target) in selectedTargets.withIndex()) {
                try {
                    consoleLog(
                        getString(
                            R.string.patch_console_log_start_format,
                            targetIndex + 1, totalTargets, target.displayName
                        )
                    )

                    val tempTargetFile: File
                    val isInstalledApp = target.packageName != null
                    
                    if (isInstalledApp) {
                        // BUNDLE SPLIT APKs (SOLUSI BUG HILANGNYA NATIVE LIBS)
                        val pm = requireContext().packageManager
                        val appInfo = pm.getApplicationInfo(target.packageName!!, 0)
                        val splits = appInfo.splitSourceDirs
                        
                        if (!splits.isNullOrEmpty()) {
                            tempTargetFile = File(requireContext().cacheDir, "target_$targetIndex.apks")
                            ZipOutputStream(tempTargetFile.outputStream()).use { zout ->
                                zout.putNextEntry(ZipEntry("base.apk"))
                                File(appInfo.sourceDir).inputStream().use { it.copyTo(zout) }
                                zout.closeEntry()
                                
                                for (split in splits) {
                                    val splitFile = File(split)
                                    zout.putNextEntry(ZipEntry(splitFile.name))
                                    splitFile.inputStream().use { it.copyTo(zout) }
                                    zout.closeEntry()
                                }
                            }
                        } else {
                            tempTargetFile = File(requireContext().cacheDir, "target_$targetIndex.apk")
                            File(appInfo.sourceDir).inputStream().use { input ->
                                tempTargetFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    } else {
                        val originalExt = target.displayName.substringAfterLast('.', "apk")
                        tempTargetFile = File(requireContext().cacheDir, "target_$targetIndex.$originalExt")
                        requireContext().contentResolver.openInputStream(target.uri)?.use { input ->
                            tempTargetFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }

                    val sanitizedAppName = target.displayName
    .substringBeforeLast('.')
    .replace("+", "_")
    .replace(" ", "_")
    .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                    val resultApk = XpatchEngine.patchApk(
                        context = requireContext(),
                        srcApk = tempTargetFile,
                        moduleApks = tempModuleFiles,
                        outputDir = outputDirectory,
                        signatureBypassLevel = bypassLevel,
                        overrideVersionCode = overrideCode,
                        overrideVersionName = overrideName,
                        outputApkName = sanitizedAppName,
                        injectProvider = injectProvider,
                        useMicroG = useMicroG,
                        antiDebug = binding.switchAntiDebug.isChecked,
                        hideDlIteratePhdr = binding.switchHideDlIterate.isChecked,
                        trapPrctl = binding.switchTrapPrctl.isChecked,
                        normalizeTiming = binding.switchNormalizeTiming.isChecked,
                        deviceSpoofProfile = selectedDeviceProfile,
                        onProgress = { progress ->
                            val overallLabel = if (totalTargets > 1) {
                                getString(R.string.xpatch_batch_progress_format, targetIndex + 1, totalTargets, target.displayName, progress)
                            } else {
                                "$progress%"
                            }
                            setConsoleProgress(progress, overallLabel)
                        },
                        onLog = { line -> consoleLog(line) }
                    )

                    consoleLog(getString(R.string.patch_console_log_done_format, resultApk.name))
                    consoleLog(getString(R.string.patch_console_log_saved_location_format, resultApk.absolutePath))
                    results.add(resultApk)
                    tempTargetFile.delete()
                } catch (e: Exception) {
                    android.util.Log.e("XpatchEngine", "patchApk failed for ${target.displayName}", e)
                    val chain = generateSequence(e as Throwable) { it.cause }
                        .joinToString(" <- caused by: ") { "${it.javaClass.simpleName}: ${it.message}" }
                    consoleLog(getString(R.string.patch_console_log_failed_format, chain))
                    failures.add(target.displayName to chain)
                }
            }

            tempModuleFiles.forEach { it.delete() }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                setPatchButtonBusy(false)
                consoleLog(getString(R.string.patch_console_log_all_done_format, results.size, failures.size))
                finishPatchConsole(results, failures)
            }
        }
    }

    private var consoleBehavior: com.google.android.material.bottomsheet.BottomSheetBehavior<View>? = null
    private var consoleIsProcessing = true

    private fun showPatchConsole() {
        consoleIsProcessing = true
        val dialog = BottomSheetDialog(requireContext())
        val cb = DialogPatchConsoleBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(cb.root)
        dialog.setCancelable(false)

        (cb.root.parent as? View)?.let { sheet ->
            sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
            behavior.isHideable = false 
            behavior.isDraggable = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    val expanded = newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    cb.btnConsoleToggle.text = if (expanded) {
                        getString(R.string.patch_console_chevron_collapse)
                    } else {
                        getString(R.string.patch_console_chevron_expand)
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
            consoleBehavior = behavior

            cb.peekSection.post {
                behavior.peekHeight = cb.peekSection.height + cb.root.paddingTop + 24
            }
        }

        cb.btnConsoleToggle.setOnClickListener {
            val behavior = consoleBehavior ?: return@setOnClickListener
            behavior.state = if (behavior.state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
            } else {
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }

        cb.tvConsoleLog.text = ""
        cb.consoleProgressBar.progress = 0
        cb.tvConsoleProgressLabel.text = "0%"
        cb.tvConsoleStatusChip.text = getString(R.string.patch_console_status_running)
        cb.tvConsoleStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip)
        cb.tvConsoleStatusChip.setTextColor(resources.getColor(R.color.console_accent_cyan, null))
        cb.btnConsoleInstall.visibility = View.GONE
        cb.btnConsoleBack.setOnClickListener {
            if (consoleIsProcessing) {
                consoleBehavior?.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
            } else {
                dialog.dismiss()
            }
        }

        consoleBinding = cb
        consoleDialog = dialog
        dialog.setOnDismissListener {
            consoleBehavior = null
            consoleBinding = null
            consoleDialog = null
        }
        dialog.show()
    }

    private fun consoleLog(line: String) {
        activity?.runOnUiThread {
            val cb = consoleBinding ?: return@runOnUiThread
            val color = when {
                line.contains("✔") -> resources.getColor(R.color.console_text_success, null)
                line.contains("✘") -> resources.getColor(R.color.console_text_error, null)
                line.contains("⚠") -> resources.getColor(R.color.console_text_warning, null)
                else -> resources.getColor(R.color.console_text_primary, null)
            }
            val spannable = android.text.SpannableString(line)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, line.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cb.tvConsoleLog.append(if (cb.tvConsoleLog.text.isEmpty()) spannable else "\n".plus(spannable as CharSequence))
            cb.consoleScrollView.post { cb.consoleScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setConsoleProgress(progress: Int, label: String) {
        activity?.runOnUiThread {
            val cb = consoleBinding ?: return@runOnUiThread
            cb.consoleProgressBar.progress = progress
            cb.tvConsoleProgressLabel.text = label
        }
    }

    private fun finishPatchConsole(results: List<File>, failures: List<Pair<String, String>>) {
        val cb = consoleBinding ?: return
        consoleIsProcessing = false
        consoleBehavior?.isHideable = true
        consoleDialog?.setCancelable(true)

        val allOk = failures.isEmpty() && results.isNotEmpty()
        val allFailed = results.isEmpty()
        when {
            allOk -> {
                cb.tvConsoleStatusChip.text = getString(R.string.patch_console_status_done)
                cb.tvConsoleStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip_done)
                cb.tvConsoleStatusChip.setTextColor(resources.getColor(R.color.console_text_success, null))
            }
            allFailed -> {
                cb.tvConsoleStatusChip.text = getString(R.string.patch_console_status_failed)
                cb.tvConsoleStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip_failed)
                cb.tvConsoleStatusChip.setTextColor(resources.getColor(R.color.console_text_error, null))
            }
            else -> {
                cb.tvConsoleStatusChip.text = getString(R.string.patch_console_status_done)
                cb.tvConsoleStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip_done)
                cb.tvConsoleStatusChip.setTextColor(resources.getColor(R.color.console_text_success, null))
            }
        }

        if (results.isNotEmpty()) {
            val installTarget = results.first()
            cb.btnConsoleInstall.visibility = View.VISIBLE
            cb.btnConsoleInstall.setOnClickListener {
                installPatchedApk(installTarget)
            }
        }

        notifyPatchResult(results.size, failures.size)
    }

    private fun installPatchedApk(apkFile: File) {
        consoleLog(getString(R.string.patch_console_log_conflict_check))
        val ctx = context ?: return
        thread {
            val conflictPackage = ApkInstaller.findSignatureConflict(ctx, apkFile)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (conflictPackage != null) {
                    consoleLog(getString(R.string.patch_console_log_conflict_found_format, conflictPackage))
                    ApkInstaller.showConflictDialog(
                        requireContext(),
                        conflictPackage,
                        onInstallDirectly = {
                            consoleLog(getString(R.string.patch_console_log_installing))
                            launchApkInstaller(apkFile)
                        },
                        onUninstallThenInstall = {
                            consoleLog("Switch to App Info. Please Uninstall, then press Back.")
                            pendingInstallApk = apkFile
                            conflictPackageName = conflictPackage
                            
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$conflictPackage")
                            }
                            consoleUninstallLauncher.launch(intent)
                        }
                    )
                } else {
                    consoleLog(getString(R.string.patch_console_log_installing))
                    launchApkInstaller(apkFile)
                }
            }
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            startActivity(ApkInstaller.installSingleApkIntent(requireContext(), apkFile))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.fm_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createXpatchNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = requireContext().getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                XPATCH_NOTIF_CHANNEL_ID,
                getString(R.string.xpatch_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.xpatch_notif_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun notifyPatchResult(successCount: Int, failCount: Int) {
        val context = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        createXpatchNotificationChannel()

        val (title, text) = when {
            failCount == 0 && successCount > 0 ->
                getString(R.string.xpatch_notif_title_success) to getString(R.string.xpatch_notif_text_success_format, successCount)
            successCount > 0 ->
                getString(R.string.xpatch_notif_title_partial) to getString(R.string.xpatch_notif_text_partial_format, successCount, failCount)
            else ->
                getString(R.string.xpatch_notif_title_failed) to getString(R.string.xpatch_notif_text_failed)
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, XPATCH_NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tab_console)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(XPATCH_NOTIF_ID, notification)
    }

    private fun showBatchResultDialog(results: List<File>, failures: List<Pair<String, String>>) {
        val message = StringBuilder()
        if (results.isNotEmpty()) {
            message.append(getString(R.string.xpatch_result_success_header_format, results.size))
            results.forEach { message.append("  ${it.name}\n") }
        }
        if (failures.isNotEmpty()) {
            message.append(getString(R.string.xpatch_result_failure_header_format, failures.size))
            failures.forEach { (name, reason) -> message.append("  $name - $reason\n") }
        }

        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (results.size > 1) getString(R.string.xpatch_batch_complete_title) else getString(R.string.xpatch_result_title))
            .setMessage(message.toString().trim())
            .setPositiveButton(getString(R.string.xpatch_ok), null)

        if (results.size == 1 && ShizukuManager.isReady()) {
            dialogBuilder.setNeutralButton(getString(R.string.xpatch_auto_install)) { _, _ ->
                installWithShizuku(results[0])
            }
        }
        dialogBuilder.show()
    }

    private fun installWithShizuku(apkFile: File) {
        thread {
            try {
                val service = ShizukuManager.service ?: return@thread
                val isSuccess = service.copy(apkFile.absolutePath, "/data/local/tmp/patched.apk")
                if (isSuccess) {
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        Toast.makeText(requireContext(), getString(R.string.xpatch_installing_via_shizuku), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    Toast.makeText(requireContext(), getString(R.string.xpatch_shizuku_install_failed_format, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), getString(R.string.xpatch_error_storage_permission), Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
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

    inner class AppSelectionAdapter(
        private val list: List<AppItem>,
        private val selectedList: List<AppItem>,
        private val onToggle: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemAppBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val isSelected = item in selectedList
            holder.itemBinding.tvAppName.text = item.name
            holder.itemBinding.tvAppPackage.text = item.packageName
            holder.itemBinding.ivAppIcon.setImageDrawable(item.icon)
            holder.itemBinding.btnExtract.text = if (isSelected) getString(R.string.xpatch_added) else getString(R.string.xpatch_select_item)
            holder.itemBinding.ivCheckOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

            holder.itemBinding.root.setStrokeColor(
                resources.getColorStateList(
                    if (isSelected) android.R.color.holo_blue_dark else android.R.color.darker_gray,
                    null
                )
            )
            holder.itemBinding.root.strokeWidth = if (isSelected) 4 else 1
            holder.itemBinding.root.setCardBackgroundColor(
                resources.getColor(
                    if (isSelected) android.R.color.holo_blue_light else android.R.color.transparent,
                    null
                )
            )

            holder.itemBinding.btnExtract.isClickable = false
            holder.itemBinding.btnExtract.isFocusable = false
            holder.itemBinding.root.setOnClickListener {
                onToggle(item)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        consoleDialog?.dismiss()
        consoleDialog = null
        consoleBinding = null
        _binding = null
    }
}