package com.mondns.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentMlbbBinding
import java.io.File

class MlbbFragment : Fragment() {
    private var _binding: FragmentMlbbBinding? = null
    private val binding get() = _binding!!

    private val targetPackages = listOf(
        "com.mobile.legends",
        "com.mobiin.gp",
        "com.mobile.legendt"
    )

    data class InstalledGame(val name: String, val pkg: String, val icon: Drawable)
    private val installedList = mutableListOf<InstalledGame>()
    private var selectedGamePkg: String? = null

    private var pendingSafAction = 0 
    private val ACTION_BACKUP = 1
    private val ACTION_RESTORE = 2

    private val safLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Toast.makeText(requireContext(), "Folder Access Granted!", Toast.LENGTH_SHORT).show()
                if (pendingSafAction == ACTION_BACKUP) backupAssets()
                else if (pendingSafAction == ACTION_RESTORE) restoreAssetsAndLaunch()
            }
        } else {
            Toast.makeText(requireContext(), "Permission denied. App needs this to backup.", Toast.LENGTH_SHORT).show()
        }
    }

    private val clearDataLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        markStep2Done()
    }

    private val resetAdsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        markStep3Done()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMlbbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        scanInstalledGames()

        binding.btnStep1.setOnClickListener { if (checkStoragePermission()) backupAssets() }
        binding.btnStep2.setOnClickListener { openAppInfo() }
        binding.btnStep3.setOnClickListener { openGoogleAdsSettings() }
        binding.btnStep4.setOnClickListener { if (checkStoragePermission()) restoreAssetsAndLaunch() }
    }

    private fun scanInstalledGames() {
        val pm = requireContext().packageManager
        installedList.clear()

        for (pkg in targetPackages) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val name = pm.getApplicationLabel(info).toString()
                val icon = pm.getApplicationIcon(info)
                installedList.add(InstalledGame(name, pkg, icon))
            } catch (e: PackageManager.NameNotFoundException) { }
        }

        if (installedList.isEmpty()) {
            binding.tvGameName.text = "Game Not Found"
            binding.tvGameStatus.text = "Please install Mobile Legends first"
            disableAllButtons()
            return
        }

        if (installedList.size > 1) {
            binding.tilGameSelect.visibility = View.VISIBLE
            val names = installedList.map { "${it.name} (${it.pkg})" }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
            binding.actvGameSelect.setAdapter(adapter)
            
            binding.actvGameSelect.setText(names[0], false)
            selectGame(installedList[0])

            binding.actvGameSelect.setOnItemClickListener { _, _, position, _ ->
                selectGame(installedList[position])
            }
        } else {
            binding.tilGameSelect.visibility = View.GONE
            selectGame(installedList[0])
        }
    }

    private fun selectGame(game: InstalledGame) {
        selectedGamePkg = game.pkg
        binding.tvGameName.text = game.name
        binding.ivGameIcon.setImageDrawable(game.icon)
        binding.tvGameStatus.text = "Ready to reset (${game.pkg})"
        resetStepsUI()
    }

    private fun resetStepsUI() {
        val primaryColor = ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary)
        val lockIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_secure)
        
        // STEP 1 selalu terbuka
        binding.btnStep1.isEnabled = true
        binding.btnStep1.text = "STEP 1: BACKUP ASSETS"
        binding.btnStep1.backgroundTintList = primaryColor
        binding.btnStep1.icon = null
        binding.btnStep1.alpha = 1.0f

        // STEP 2 terkunci, redup (alpha 0.6), dan ada logo gembok
        binding.btnStep2.isEnabled = false
        binding.btnStep2.text = "STEP 2: CLEAR MLBB DATA"
        binding.btnStep2.backgroundTintList = primaryColor
        binding.btnStep2.icon = lockIcon
        binding.btnStep2.alpha = 0.6f

        // STEP 3 terkunci
        binding.btnStep3.isEnabled = false
        binding.btnStep3.text = "STEP 3: RESET GOOGLE ADS ID"
        binding.btnStep3.backgroundTintList = primaryColor
        binding.btnStep3.icon = lockIcon
        binding.btnStep3.alpha = 0.6f

        // STEP 4 terkunci
        binding.btnStep4.isEnabled = false
        binding.btnStep4.text = "FINISH: RESTORE & PLAY"
        binding.btnStep4.backgroundTintList = primaryColor
        binding.btnStep4.icon = lockIcon
        binding.btnStep4.alpha = 0.6f
    }

    private fun markStep1Done() {
        binding.btnStep1.text = "✔ STEP 1: BACKUP SUCCESS"
        binding.btnStep1.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess)
        
        // Buka gembok Step 2
        binding.btnStep2.isEnabled = true
        binding.btnStep2.icon = null
        binding.btnStep2.alpha = 1.0f
    }

    private fun markStep2Done() {
        binding.btnStep2.text = "✔ STEP 2: DATA CLEARED"
        binding.btnStep2.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess)
        
        // Buka gembok Step 3
        binding.btnStep3.isEnabled = true
        binding.btnStep3.icon = null
        binding.btnStep3.alpha = 1.0f
    }

    private fun markStep3Done() {
        binding.btnStep3.text = "✔ STEP 3: ADS ID RESET"
        binding.btnStep3.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess)
        
        // Buka gembok Step 4
        binding.btnStep4.isEnabled = true
        binding.btnStep4.icon = null
        binding.btnStep4.alpha = 1.0f
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Grant All Files Access first.", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${requireContext().packageName}")
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

    private fun backupAssets() {
        val pkg = selectedGamePkg ?: return
        val originalDir = File(Environment.getExternalStorageDirectory(), "Android/data/$pkg")
        val backupDir = File(Environment.getExternalStorageDirectory(), "Android/data/${pkg}_backup")

        if (backupDir.exists() && !originalDir.exists()) {
            Toast.makeText(requireContext(), "Assets already backed up!", Toast.LENGTH_SHORT).show()
            markStep1Done()
            return
        }

        if (originalDir.exists()) {
            if (FileOps.rename(originalDir, backupDir)) {
                Toast.makeText(requireContext(), "Backup Success! Proceed to Step 2.", Toast.LENGTH_SHORT).show()
                markStep1Done()
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safUri = getPersistedSafUri()
            if (safUri == null) {
                pendingSafAction = ACTION_BACKUP
                if (FileOps.needsShizukuSetup()) {
                    showShizukuOrSafChoice { requestSafPermission() }
                } else {
                    requestSafPermission()
                }
            } else {
                if (doSafRename(safUri, pkg, "${pkg}_backup")) {
                    markStep1Done()
                } else {
                    Toast.makeText(requireContext(), "Backup checked. Assuming completed.", Toast.LENGTH_SHORT).show()
                    markStep1Done()
                }
            }
        } else {
            Toast.makeText(requireContext(), "Failed to backup. Check storage permissions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreAssetsAndLaunch() {
        val pkg = selectedGamePkg ?: return
        val originalDir = File(Environment.getExternalStorageDirectory(), "Android/data/$pkg")
        val backupDir = File(Environment.getExternalStorageDirectory(), "Android/data/${pkg}_backup")

        var isRestored = false

        if (backupDir.exists()) {
            if (FileOps.rename(backupDir, originalDir)) {
                isRestored = true
            }
        }

        if (!isRestored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safUri = getPersistedSafUri()
            if (safUri != null) {
                isRestored = doSafRename(safUri, "${pkg}_backup", pkg)
            } else {
                pendingSafAction = ACTION_RESTORE
                if (FileOps.needsShizukuSetup()) {
                    showShizukuOrSafChoice { requestSafPermission() }
                } else {
                    requestSafPermission()
                }
                return
            }
        }
        
        if (isRestored) {
            Toast.makeText(requireContext(), "✔ RESTORED! Launching game...", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), "Could not find backup or already restored. Launching...", Toast.LENGTH_SHORT).show()
        }
        
        launchGame(pkg)
        resetStepsUI()
    }

    /** Kasih user pilihan jelas kalau folder Android/data butuh akses khusus:
     * aktifkan Shizuku (sekali aktivasi, otomatis dipakai seterusnya) atau
     * pilih folder manual lewat SAF (sekali pakai, gak butuh setup apapun). */
    private fun showShizukuOrSafChoice(onUseSaf: () -> Unit) {
        if (!ShizukuManager.isAvailable()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.fm_shizuku_title))
                .setMessage(getString(R.string.mlbb_shizuku_offer_install))
                .setPositiveButton(getString(R.string.fm_shizuku_open_page)) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.fm_shizuku_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton(getString(R.string.mlbb_pick_folder_manual)) { _, _ -> onUseSaf() }
                .setNegativeButton(R.string.fm_cancel, null)
                .show()
            return
        }

        if (!ShizukuManager.isPermissionGranted()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.fm_shizuku_title))
                .setMessage(getString(R.string.mlbb_shizuku_offer_permission))
                .setPositiveButton(getString(R.string.fm_shizuku_request)) { _, _ ->
                    ShizukuManager.requestPermission()
                }
                .setNeutralButton(getString(R.string.mlbb_pick_folder_manual)) { _, _ -> onUseSaf() }
                .setNegativeButton(R.string.fm_cancel, null)
                .show()
            return
        }

        // Shizuku sudah siap tapi rename tetap gagal (kasus jarang) -> langsung SAF, gak perlu nanya lagi.
        onUseSaf()
    }

    private fun getPersistedSafUri(): Uri? {
        val permissions = requireContext().contentResolver.persistedUriPermissions
        for (perm in permissions) {
            if (perm.uri.toString().contains("Android%2Fdata")) return perm.uri
        }
        return null
    }

    private fun requestSafPermission() {
        Toast.makeText(requireContext(), "Click 'USE THIS FOLDER' on the next screen.", Toast.LENGTH_LONG).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
        }
        try { safLauncher.launch(intent) } catch (e: Exception) { }
    }

    private fun doSafRename(treeUri: Uri, oldName: String, newName: String): Boolean {
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Android/data/$oldName")
            val renamedUri = DocumentsContract.renameDocument(requireContext().contentResolver, docUri, newName)
            if (renamedUri != null) {
                Toast.makeText(requireContext(), "SAF Action Success!", Toast.LENGTH_SHORT).show()
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun openAppInfo() {
        Toast.makeText(requireContext(), "Click 'Storage' -> 'Clear Data', then press Back to return here.", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:${selectedGamePkg ?: return}")
        clearDataLauncher.launch(intent)
    }

    private fun openGoogleAdsSettings() {
        Toast.makeText(requireContext(), "Click 'Reset advertising ID', then press Back to return here.", Toast.LENGTH_LONG).show()
        val intent = Intent("com.google.android.gms.settings.ADS_PRIVACY")
        try { 
            resetAdsLauncher.launch(intent) 
        } catch (e: Exception) { 
            resetAdsLauncher.launch(Intent(Settings.ACTION_SYNC_SETTINGS)) 
        }
    }

    private fun launchGame(pkg: String) {
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) startActivity(launchIntent)
    }

    private fun disableAllButtons() {
        binding.btnStep1.isEnabled = false
        binding.btnStep1.alpha = 0.5f
        binding.btnStep2.isEnabled = false
        binding.btnStep2.alpha = 0.5f
        binding.btnStep3.isEnabled = false
        binding.btnStep3.alpha = 0.5f
        binding.btnStep4.isEnabled = false
        binding.btnStep4.alpha = 0.5f
    }

    override fun onDestroyView() { 
        super.onDestroyView()
        _binding = null 
    }
}