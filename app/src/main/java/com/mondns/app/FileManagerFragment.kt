package com.mondns.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentFileManagerBinding
import com.mondns.app.databinding.ItemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileManagerFragment : Fragment() {
    private var _binding: FragmentFileManagerBinding? = null
    private val binding get() = _binding!!

    private val rootDir = Environment.getExternalStorageDirectory()
    private var currentDir = rootDir

    // State Filter & Selection
    private var currentFilter = ""
    private var currentFilterLabel = "Semua File"
    private val selectedFiles = mutableSetOf<File>()

    // State Mode Pencarian Global
    private var isGlobalMode = false
    private var globalResultsCache = listOf<File>()

    // State Sort & Search-by-name
    private enum class SortMode { NAME_ASC, NAME_DESC, DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST }
    private var sortMode = SortMode.NAME_ASC
    private var searchQuery = ""

    private val skipDirNames = setOf("Android", "obb", ".thumbnails", ".trashed", ".trash", ".cache")

    // State Paste
    private val clipboard = mutableListOf<File>()
    private var isCopyMode = false 

    // Icon APK Cache (tanpa Executor lama, sekarang pakai Coroutine)
    private val apkIconCache = LruCache<String, Drawable>(60)
    private lateinit var fileAdapter: FileAdapter

    // State Fix Bug Install APK
    private var conflictPackageName: String? = null
    private var pendingInstallApk: File? = null

    // Launcher untuk fix Uninstall-then-Install
    private val uninstallThenInstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstallApk
            val pkg = conflictPackageName
            pendingInstallApk = null
            conflictPackageName = null
            
            if (apk != null && pkg != null && _binding != null) {
                // Cek ulang apakah user beneran sudah uninstall app tersebut di setting
                if (!isPackageInstalled(requireContext(), pkg)) {
                    launchApkInstaller(apk)
                } else {
                    Toast.makeText(requireContext(), "Instalasi dibatalkan: Aplikasi versi lama belum dihapus.", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != ShizukuManager.REQUEST_CODE || _binding == null) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            ShizukuManager.bindService()
        }
        Toast.makeText(
            requireContext(),
            if (granted) getString(R.string.fm_shizuku_granted) else getString(R.string.fm_shizuku_denied),
            Toast.LENGTH_SHORT
        ).show()
        updateShizukuBadge()
    }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp")
    private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "m4a")
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")
    private val scriptExtensions = setOf("lua", "js", "ts", "py", "java", "kt", "html", "css", "c", "cpp", "sh", "json", "xml")
    private val docExtensions = setOf("txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val libExtensions = setOf("so", "a")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFileManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        handleBackPress()

        ShizukuManager.addPermissionListener(shizukuPermissionListener)
        if (ShizukuManager.isAvailable() && ShizukuManager.isPermissionGranted()) {
            ShizukuManager.bindService()
        }
        updateShizukuBadge()

        if (checkStoragePermission()) {
            loadFiles()
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuBadge()
        if (checkStoragePermission() && !isGlobalMode) {
            loadFiles()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Glide.with(this).pauseRequests()
        ShizukuManager.removePermissionListener(shizukuPermissionListener)
        _binding = null
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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

    private fun requestAllFilesAccess() {
        Toast.makeText(requireContext(), getString(R.string.fm_grant_access), Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${requireContext().packageName}")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onItemClick = { file ->
                if (isGlobalMode) {
                    toggleSelection(file)
                } else if (selectedFiles.isNotEmpty()) {
                    toggleSelection(file)
                } else {
                    if (file.isDirectory) {
                        currentDir = file
                        loadFiles()
                    } else {
                        handleFileOpen(file)
                    }
                }
            },
            onItemLongClick = { file -> toggleSelection(file) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFiles.adapter = fileAdapter
    }

    private fun setupListeners() {
        binding.btnUp.setOnClickListener {
            if (isGlobalMode || currentFilter.isNotEmpty()) {
                resetFilterToAll()
            } else {
                navigateUp()
            }
        }

        binding.btnNewFolder.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                requestAllFilesAccess()
            } else {
                showCreateFolderDialog()
            }
        }

        binding.btnCloseSelection.setOnClickListener {
            selectedFiles.clear()
            fileAdapter.notifyDataSetChanged()
            refreshSelectionUI()
        }

        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnMoreActions.setOnClickListener { showMoreActionsMenu(it) }
        binding.btnRename.setOnClickListener { showRenameDialog() }

        binding.btnSearchToggle.setOnClickListener { toggleSearchInput() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshDisplay()
            }
        })

        binding.btnSort.setOnClickListener { showSortMenu(it) }
        binding.btnShizuku.setOnClickListener { showShizukuStatusDialog() }

        binding.btnFilterOptions.setOnClickListener { showFilterMenu(it) }

        binding.btnSearch.setOnClickListener { performGlobalSearch() }

        binding.btnMove.setOnClickListener { startPasteFlow(isCopy = false) }
        binding.btnCopy.setOnClickListener { startPasteFlow(isCopy = true) }
        binding.btnDelete.setOnClickListener { deleteSelectedFiles() }

        binding.btnCancel.setOnClickListener { cancelPasteFlow() }
        binding.btnPaste.setOnClickListener { executePaste() }
    }

    private fun navigateUp() {
        if (currentDir.absolutePath != rootDir.absolutePath) {
            currentDir = currentDir.parentFile ?: rootDir
            loadFiles()
        }
    }

    private fun handleBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchInputRow.visibility == View.VISIBLE) {
                    toggleSearchInput()
                } else if (selectedFiles.isNotEmpty()) {
                    selectedFiles.clear()
                    fileAdapter.notifyDataSetChanged()
                    refreshSelectionUI()
                } else if (isGlobalMode || currentFilter.isNotEmpty()) {
                    resetFilterToAll()
                } else if (currentDir.absolutePath != rootDir.absolutePath) {
                    navigateUp()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun toggleSearchInput() {
        val show = binding.searchInputRow.visibility != View.VISIBLE
        if (show) {
            binding.searchInputRow.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.searchInputRow.visibility = View.GONE
            binding.etSearch.text?.clear()
            searchQuery = ""
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            refreshDisplay()
        }
    }

    private data class FilterOption(
        val iconRes: Int,
        val title: String,
        val subtitle: String,
        val value: String
    )

    private fun showFilterMenu(anchor: View) {
        val options = listOf(
            FilterOption(R.drawable.ic_filter_all, "Semua File", "", ""),
            FilterOption(R.drawable.ic_type_archive, "Arsip", ".zip, .rar, .7z", "zip,rar,7z,tar,gz"),
            FilterOption(R.drawable.ic_apk, "Aplikasi", ".apk", "apk"),
            FilterOption(R.drawable.ic_filter_lib, "Native Libs", ".so", "so"),
            FilterOption(R.drawable.ic_filter_script, "Scripts", ".lua, .js, .json, .sh", "lua,js,json,sh"),
            FilterOption(R.drawable.ic_filter_doc, "Dokumen", ".txt, .pdf, .md, .xml", "txt,md,xml,pdf,doc,docx"),
            FilterOption(R.drawable.ic_type_image, "Media", "Gambar / Video / Audio", "jpg,jpeg,png,webp,gif,mp4,mkv,avi,mp3,wav,m4a")
        )

        val inflater = LayoutInflater.from(requireContext())
        val popupView = inflater.inflate(R.layout.popup_filter_menu, null)
        val container = popupView.findViewById<LinearLayout>(R.id.filterOptionsContainer)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = dp(8).toFloat()

        options.forEach { option ->
            val row = inflater.inflate(R.layout.item_filter_option, container, false)
            row.findViewById<ImageView>(R.id.ivFilterIcon).setImageResource(option.iconRes)
            row.findViewById<TextView>(R.id.tvFilterLabel).text = option.title
            val tvSub = row.findViewById<TextView>(R.id.tvFilterSub)
            if (option.subtitle.isEmpty()) {
                tvSub.visibility = View.GONE
            } else {
                tvSub.text = option.subtitle
            }
            row.setOnClickListener {
                applyFilterSelection(option.value, option.title)
                popupWindow.dismiss()
            }
            container.addView(row)
        }

        popupWindow.showAsDropDown(anchor, 0, dp(4))
    }

    private fun applyFilterSelection(filterValue: String, label: String) {
        currentFilter = filterValue
        currentFilterLabel = if (filterValue.isEmpty()) "Semua File" else label
        binding.btnFilterOptions.text = "Tampilkan: $currentFilterLabel"

        if (currentFilter.isEmpty()) {
            if (isGlobalMode) {
                exitGlobalMode()
            } else {
                binding.searchActionBar.visibility = View.GONE
            }
        } else {
            enterPendingSearchState()
        }
    }

    private fun resetFilterToAll() {
        currentFilter = ""
        currentFilterLabel = "All File"
        binding.btnFilterOptions.text = "Show: isPackageInstalled File"
        if (isGlobalMode) {
            exitGlobalMode()
        } else {
            binding.searchActionBar.visibility = View.GONE
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.fm_sort_name_asc))
        popup.menu.add(0, 2, 1, getString(R.string.fm_sort_name_desc))
        popup.menu.add(0, 3, 2, getString(R.string.fm_sort_newest))
        popup.menu.add(0, 4, 3, getString(R.string.fm_sort_oldest))
        popup.menu.add(0, 5, 4, getString(R.string.fm_sort_largest))
        popup.menu.add(0, 6, 5, getString(R.string.fm_sort_smallest))
        popup.setOnMenuItemClickListener { item ->
            sortMode = when (item.itemId) {
                1 -> SortMode.NAME_ASC
                2 -> SortMode.NAME_DESC
                3 -> SortMode.DATE_NEWEST
                4 -> SortMode.DATE_OLDEST
                5 -> SortMode.SIZE_LARGEST
                6 -> SortMode.SIZE_SMALLEST
                else -> SortMode.NAME_ASC
            }
            refreshDisplay()
            true
        }
        popup.show()
    }

    private fun updateShizukuBadge() {
        if (_binding == null) return
        val color = when {
            !ShizukuManager.isAvailable() -> 0xFF9E9E9E.toInt()
            !ShizukuManager.isPermissionGranted() -> 0xFFFFA000.toInt()
            else -> 0xFF43A047.toInt()
        }
        val bg = binding.shizukuStatusDot.background
        if (bg is android.graphics.drawable.GradientDrawable) {
            bg.setColor(color)
        } else {
            binding.shizukuStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    private fun showShizukuStatusDialog(contextMessage: String? = null) {
        val prefix = if (contextMessage != null) "$contextMessage\n\n" else ""

        if (!ShizukuManager.isAvailable()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.fm_shizuku_title))
                .setMessage(prefix + getString(R.string.fm_shizuku_not_installed))
                .setPositiveButton(getString(R.string.fm_shizuku_open_page)) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.fm_shizuku_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.fm_cancel, null)
                .show()
            return
        }

        if (!ShizukuManager.isPermissionGranted()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.fm_shizuku_title))
                .setMessage(prefix + getString(R.string.fm_shizuku_need_permission))
                .setPositiveButton(getString(R.string.fm_shizuku_request)) { _, _ ->
                    ShizukuManager.requestPermission()
                }
                .setNegativeButton(R.string.fm_cancel, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_shizuku_title))
            .setMessage(prefix + getString(R.string.fm_shizuku_active))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun handleFileOpFailure(genericMessage: String) {
        if (_binding == null) return
        if (FileOps.needsShizukuSetup()) {
            showShizukuStatusDialog(getString(R.string.fm_shizuku_op_blocked))
        } else {
            Toast.makeText(requireContext(), genericMessage, Toast.LENGTH_SHORT).show()
        }
        updateShizukuBadge()
    }

    private fun showMoreActionsMenu(anchor: View) {
        if (selectedFiles.isEmpty()) return
        val popup = PopupMenu(requireContext(), anchor)
        
        if (selectedFiles.size == 1) {
            popup.menu.add(0, 1, 0, getString(R.string.fm_file_info))
            val file = selectedFiles.first()
            if (!file.isDirectory) {
                popup.menu.add(0, 3, 1, "Convert for C++") 
            }
        }
        
        val noFolders = selectedFiles.none { it.isDirectory }
        if (noFolders) {
            popup.menu.add(0, 2, 2, getString(R.string.fm_share))
        }
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showFileInfoDialog(selectedFiles.first())
                2 -> shareSelectedFiles()
                3 -> {
                    val bundle = Bundle().apply {
                        putString("filePath", selectedFiles.first().absolutePath)
                    }
                    findNavController().navigate(R.id.action_fileManager_to_converter, bundle)
                }
            }
            true
        }
        popup.show()
    }

    private fun sortFiles(list: List<File>): List<File> {
        val comparator: Comparator<File> = when (sortMode) {
            SortMode.NAME_ASC -> compareBy { it.name.lowercase() }
            SortMode.NAME_DESC -> compareByDescending { it.name.lowercase() }
            SortMode.DATE_NEWEST -> compareByDescending { it.lastModified() }
            SortMode.DATE_OLDEST -> compareBy { it.lastModified() }
            SortMode.SIZE_LARGEST -> compareByDescending { if (it.isDirectory) Long.MAX_VALUE else it.length() }
            SortMode.SIZE_SMALLEST -> compareBy { if (it.isDirectory) -1L else it.length() }
        }
        return if (isGlobalMode) {
            list.sortedWith(comparator)
        } else {
            list.sortedWith(compareBy<File> { !it.isDirectory }.then(comparator))
        }
    }

    private fun applySearchFilter(list: List<File>): List<File> {
        if (searchQuery.isBlank()) return list
        return list.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    private fun refreshDisplay() {
        if (_binding == null) return
        if (isGlobalMode) {
            val filtered = applySearchFilter(globalResultsCache)
            val sorted = sortFiles(filtered)
            fileAdapter.submitList(sorted)
            binding.tvEmpty.text = if (searchQuery.isNotBlank()) getString(R.string.fm_no_results_for, searchQuery) else getString(R.string.fm_no_search_results)
            binding.tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        } else {
            loadFiles()
        }
    }

    private fun loadFiles() {
        if (_binding == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestAllFilesAccess()
            return
        }

        updateBreadcrumb()
        updateStorageBar()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allFiles = currentDir.listFiles() ?: arrayOf()
            var filteredList: List<File> = allFiles.filterNot { it.isHidden }
            filteredList = applySearchFilter(filteredList)
            val sortedList = sortFiles(filteredList)

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    binding.tvEmpty.text = if (searchQuery.isNotBlank()) getString(R.string.fm_no_results_for, searchQuery) else getString(R.string.fm_folder_empty)
                    binding.tvEmpty.visibility = if (sortedList.isEmpty()) View.VISIBLE else View.GONE
                    fileAdapter.submitList(sortedList)
                }
            }
        }
    }

    private fun updateStorageBar() {
        try {
            val stat = StatFs(rootDir.path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes
            val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val percent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

            binding.storageBar.visibility = View.VISIBLE
            binding.tvStorageInfo.text = getString(R.string.fm_storage_info, usedGb, totalGb)
            binding.progressStorage.setProgressCompat(percent.coerceIn(0, 100), true)
        } catch (e: Exception) {
            binding.storageBar.visibility = View.GONE
        }
    }

    private fun updateBreadcrumb() {
        binding.breadcrumbContainer.removeAllViews()
        val chain = pathChain()
        chain.forEachIndexed { index, dir ->
            val label = if (dir.absolutePath == rootDir.absolutePath) getString(R.string.fm_internal) else dir.name
            val isLast = index == chain.lastIndex
            val tv = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setTextColor(themeColor(if (isLast) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary))
                if (isLast) setTypeface(typeface, Typeface.BOLD)
                if (!isLast) {
                    val outValue = TypedValue()
                    requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        currentDir = dir
                        loadFiles()
                    }
                }
            }
            binding.breadcrumbContainer.addView(tv)
            if (!isLast) {
                val sep = TextView(requireContext()).apply {
                    text = "\u203A"
                    textSize = 13f
                    setPadding(dp(2), 0, dp(2), 0)
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                }
                binding.breadcrumbContainer.addView(sep)
            }
        }
        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun pathChain(): List<File> {
        val chain = mutableListOf<File>()
        var d: File? = currentDir
        while (d != null) {
            chain.add(0, d)
            if (d.absolutePath == rootDir.absolutePath) break
            d = d.parentFile
        }
        return chain
    }

    private fun setStaticBreadcrumbLabel(text: String) {
        if (_binding == null) return
        binding.breadcrumbContainer.removeAllViews()
        val tv = TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        binding.breadcrumbContainer.addView(tv)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attrId: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attrId, tv, true)
        return tv.data
    }

    private fun enterPendingSearchState() {
        isGlobalMode = false 
        selectedFiles.clear()
        binding.searchActionBar.visibility = View.VISIBLE
        binding.tvSearchHint.text = getString(R.string.fm_search_hint, currentFilterLabel)
        setStaticBreadcrumbLabel(getString(R.string.fm_waiting_search))
        binding.btnNewFolder.visibility = View.GONE
        fileAdapter.submitList(emptyList())
        binding.tvEmpty.text = getString(R.string.fm_press_search_button)
        binding.tvEmpty.visibility = View.VISIBLE
        refreshSelectionUI()
    }

    private fun exitGlobalMode() {
        isGlobalMode = false
        currentFilter = ""
        globalResultsCache = emptyList()
        selectedFiles.clear()
        binding.searchActionBar.visibility = View.GONE
        binding.btnNewFolder.visibility = View.VISIBLE
        refreshSelectionUI()
        loadFiles()
    }

    private fun performGlobalSearch() {
        if (currentFilter.isEmpty()) return
        val filterSnapshot = currentFilter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_searching_title))
            .setMessage(getString(R.string.fm_searching_message))
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                scanGlobalFiles(filterSnapshot)
            }

            dialog.dismiss()
            if (_binding == null) return@launch
            if (currentFilter != filterSnapshot) return@launch

            isGlobalMode = true
            globalResultsCache = results
            selectedFiles.clear()
            binding.searchActionBar.visibility = View.GONE
            binding.btnNewFolder.visibility = View.GONE
            setStaticBreadcrumbLabel(getString(R.string.fm_files_found, results.size))

            refreshDisplay()
            refreshSelectionUI()
        }
    }

    private fun scanGlobalFiles(extensionsCsv: String): List<File> {
        val wanted = extensionsCsv.split(",").map { it.trim().lowercase() }.toSet()
        val results = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(rootDir)

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue

            for (child in children) {
                try {
                    if (child.isHidden || child.name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (child.name in skipDirNames) continue
                        stack.addLast(child)
                    } else {
                        val ext = child.extension.lowercase()
                        if (ext.isNotEmpty() && wanted.contains(ext)) {
                            results.add(child)
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        return results
    }

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) selectedFiles.remove(file)
        else selectedFiles.add(file)

        fileAdapter.notifyDataSetChanged()
        refreshSelectionUI()
    }

    private fun toggleSelectAll() {
        val visible = fileAdapter.getCurrentList()
        if (visible.isEmpty()) return
        val allSelected = visible.all { selectedFiles.contains(it) }
        if (allSelected) {
            selectedFiles.removeAll(visible.toSet())
        } else {
            selectedFiles.addAll(visible)
        }
        fileAdapter.notifyDataSetChanged()
        refreshSelectionUI()
    }

    private fun refreshSelectionUI() {
        if (_binding == null) return
        val showBottom = clipboard.isNotEmpty() || selectedFiles.isNotEmpty()

        if (showBottom) {
            if (binding.bottomActionPanel.visibility != View.VISIBLE) {
                binding.bottomActionPanel.visibility = View.VISIBLE
                binding.bottomActionPanel.alpha = 0f
                binding.bottomActionPanel.translationY = dp(40).toFloat()
                binding.bottomActionPanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
            binding.layoutSelectMode.visibility = if (clipboard.isEmpty()) View.VISIBLE else View.GONE
            binding.layoutPasteMode.visibility = if (clipboard.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnPaste.text = if (isCopyMode) getString(R.string.fm_paste_copy_here) else getString(R.string.fm_paste_move_here)
            binding.btnRename.visibility = if (clipboard.isEmpty() && selectedFiles.size == 1) View.VISIBLE else View.GONE
        } else if (binding.bottomActionPanel.visibility == View.VISIBLE) {
            binding.bottomActionPanel.animate()
                .alpha(0f).translationY(dp(40).toFloat()).setDuration(200)
                .withEndAction { if (_binding != null) binding.bottomActionPanel.visibility = View.GONE }
                .start()
        }

        binding.btnMoreActions.visibility = if (selectedFiles.isNotEmpty()) View.VISIBLE else View.GONE

        if (selectedFiles.isNotEmpty()) {
            binding.tvSelectionCount.text = getString(R.string.fm_selected_count, selectedFiles.size)
            if (binding.selectionHeaderRow.visibility != View.VISIBLE) {
                binding.selectionHeaderRow.alpha = 0f
                binding.selectionHeaderRow.visibility = View.VISIBLE
                binding.selectionHeaderRow.animate().alpha(1f).setDuration(180).start()
                binding.normalHeaderRow.animate().alpha(0f).setDuration(180)
                    .withEndAction { if (_binding != null) binding.normalHeaderRow.visibility = View.GONE }
                    .start()
            }
        } else if (binding.selectionHeaderRow.visibility == View.VISIBLE) {
            binding.normalHeaderRow.alpha = 0f
            binding.normalHeaderRow.visibility = View.VISIBLE
            binding.normalHeaderRow.animate().alpha(1f).setDuration(180).start()
            binding.selectionHeaderRow.animate().alpha(0f).setDuration(180)
                .withEndAction { if (_binding != null) binding.selectionHeaderRow.visibility = View.GONE }
                .start()
        }
    }

    private fun startPasteFlow(isCopy: Boolean) {
        isCopyMode = isCopy
        clipboard.clear()
        clipboard.addAll(selectedFiles)
        selectedFiles.clear()

        if (isGlobalMode) {
            resetFilterToAll()
        } else {
            fileAdapter.notifyDataSetChanged()
            refreshSelectionUI()
        }
    }

    private fun cancelPasteFlow() {
        clipboard.clear()
        if (_binding != null) refreshSelectionUI()
    }

    private fun executePaste() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_processing_title))
            .setMessage(getString(R.string.fm_please_wait))
            .setCancelable(false)
            .create()
        dialog.show()

        val targetDir = currentDir
        val itemsToPaste = clipboard.toList()
        val wasCopyMode = isCopyMode

        viewLifecycleOwner.lifecycleScope.launch {
            var successCount = 0
            var shizukuBlocked = false

            withContext(Dispatchers.IO) {
                for (file in itemsToPaste) {
                    try {
                        val dest = uniqueDestFile(targetDir, file.name)
                        val opOk = if (wasCopyMode) FileOps.copy(file, dest, overwrite = false) else FileOps.move(file, dest)
                        if (opOk) successCount++ else if (FileOps.needsShizukuSetup()) shizukuBlocked = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            dialog.dismiss()
            if (_binding != null) {
                val msg = if (wasCopyMode) getString(R.string.fm_items_copied, successCount) else getString(R.string.fm_items_moved, successCount)
                clipboard.clear()
                refreshSelectionUI()
                loadFiles()
                if (shizukuBlocked) {
                    showShizukuStatusDialog(getString(R.string.fm_shizuku_op_blocked))
                    updateShizukuBadge()
                } else {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun uniqueDestFile(dir: File, name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest

        val hasExt = name.contains(".") && !name.startsWith(".")
        val baseName = if (hasExt) name.substringBeforeLast(".") else name
        val ext = if (hasExt) name.substringAfterLast(".") else ""

        var counter = 1
        while (dest.exists()) {
            val newName = if (hasExt) "$baseName ($counter).$ext" else "$name ($counter)"
            dest = File(dir, newName)
            counter++
        }
        return dest
    }

    private fun deleteSelectedFiles() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_delete_title))
            .setMessage(getString(R.string.fm_delete_confirm, selectedFiles.size))
            .setPositiveButton(R.string.fm_delete) { _, _ ->
                val filesToDelete = selectedFiles.toList()
                val progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.fm_processing_title))
                    .setMessage(getString(R.string.fm_please_wait))
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                viewLifecycleOwner.lifecycleScope.launch {
                    var shizukuBlocked = false
                    withContext(Dispatchers.IO) {
                        for (file in filesToDelete) {
                            if (!FileOps.delete(file) && FileOps.needsShizukuSetup()) shizukuBlocked = true
                        }
                    }

                    progressDialog.dismiss()
                    if (_binding == null) return@launch
                    selectedFiles.clear()
                    refreshSelectionUI()
                    
                    if (isGlobalMode) {
                        globalResultsCache = globalResultsCache.filter { it.exists() }
                        refreshDisplay()
                        setStaticBreadcrumbLabel(getString(R.string.fm_files_found, globalResultsCache.size))
                    } else {
                        loadFiles()
                    }
                    
                    if (shizukuBlocked) {
                        showShizukuStatusDialog(getString(R.string.fm_shizuku_op_blocked))
                        updateShizukuBadge()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.fm_deleted_success), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.fm_folder_name_hint)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_new_folder_title))
            .setView(input)
            .setPositiveButton(R.string.fm_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newFolder = File(currentDir, name)
                    if (FileOps.mkdirs(newFolder)) {
                        loadFiles()
                    } else {
                        handleFileOpFailure(getString(R.string.fm_folder_create_failed))
                    }
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun showRenameDialog() {
        val file = selectedFiles.firstOrNull() ?: return
        val input = EditText(requireContext())
        input.setText(file.name)
        input.setSelection(0, file.nameWithoutExtensionSafe().length)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_rename_title))
            .setView(input)
            .setPositiveButton(R.string.fm_create) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val dest = File(file.parentFile, newName)
                    if (!dest.exists() && FileOps.rename(file, dest)) {
                        selectedFiles.clear()
                        refreshSelectionUI()
                        if (isGlobalMode) {
                            globalResultsCache = globalResultsCache.map { if (it == file) dest else it }
                            refreshDisplay()
                        } else {
                            loadFiles()
                        }
                    } else {
                        handleFileOpFailure(getString(R.string.fm_rename_failed))
                    }
                }
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }

    private fun File.nameWithoutExtensionSafe(): String {
        return if (isDirectory) name else nameWithoutExtension
    }

    private fun showFileInfoDialog(file: File) {
        val type = if (file.isDirectory) "Folder" else (file.extension.uppercase().ifEmpty { "File" })
        val sizeText = if (file.isDirectory) {
            getString(R.string.fm_items_count, file.list()?.size ?: 0)
        } else {
            formatBytes(file.length())
        }
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val message = buildString {
            append("${file.name}\n\n")
            append("${file.absolutePath}\n\n")
            append("$type  •  $sizeText\n")
            append(dateFormat.format(Date(file.lastModified())))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.fm_file_info_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    private fun handleFileOpen(file: File) {
        val ext = file.extension.lowercase()
        when {
            ext == "apk" -> showApkSmartMenu(file)
            ApkInstaller.isSplitApkSet(file) -> openSplitApkFile(file)
            else -> openWithExternalApp(file)
        }
    }

    private fun showApkSmartMenu(file: File) {
        val options = arrayOf(
            "📥 Install APK",
            "🛠️ Patch with LSPatch",
            "🔍 Inspect .so (Native Libs)"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openApkFile(file)
                    1 -> {
                        val bundle = Bundle().apply { putString("targetApkPath", file.absolutePath) }
                        findNavController().navigate(R.id.xpatchFragment, bundle)
                    }
                    2 -> showApkSoList(file) // Masuk ke fitur baru!
                }
            }
            .show()
    }

    // Fungsi untuk membaca daftar .so di dalam APK
    private fun showApkSoList(apkFile: File) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reading APK...")
            .setMessage("Searching for .so file inside APK, please wait.")
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val soEntries = mutableListOf<java.util.zip.ZipEntry>()
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        // Ambil semua file berakhiran .so
                        if (!entry.isDirectory && entry.name.endsWith(".so", ignoreCase = true)) {
                            soEntries.add(entry)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (_binding == null) return@withContext

                    if (soEntries.isEmpty()) {
                        Toast.makeText(requireContext(), "There are no .so files in this APK.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    // Urutkan berdasarkan folder arsitekturnya (biar rapi)
                    soEntries.sortBy { it.name }
                    val names = soEntries.map { it.name }.toTypedArray()

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Select .so to inspect")
                        .setItems(names) { _, index ->
                            extractAndInspectSo(apkFile, soEntries[index])
                        }
                        .setNegativeButton(R.string.fm_cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Failed to read APK:${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Fungsi mengekstrak .so yang dipilih lalu dilempar ke SoInspector
    private fun extractAndInspectSo(apkFile: File, entry: java.util.zip.ZipEntry) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Extracting...")
            .setMessage("Prepare ${entry.name.substringAfterLast('/')}")
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ekstrak ke folder cache
                val extractedFile = File(requireContext().cacheDir, entry.name.substringAfterLast('/'))
                java.util.zip.ZipFile(apkFile).use { zip ->
                    zip.getInputStream(entry).use { input ->
                        extractedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (_binding == null) return@withContext

                    // Buka halaman SoInspectorFragment dan bawa path file-nya
                    val bundle = Bundle().apply {
                        putString("soFilePath", extractedFile.absolutePath)
                    }
                    findNavController().navigate(R.id.soInspectorFragment, bundle)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Failed to extract: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openApkFile(file: File) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val conflictPackage = withContext(Dispatchers.IO) {
                ApkInstaller.findSignatureConflict(ctx, file)
            }
            if (_binding == null) return@launch
            
            if (conflictPackage != null) {
                ApkInstaller.showConflictDialog(
                    requireContext(),
                    conflictPackage,
                    onInstallDirectly = { launchApkInstaller(file) },
                    onUninstallThenInstall = {
                        pendingInstallApk = file
                        conflictPackageName = conflictPackage // Simpan nama buat diverifikasi nanti
                        Toast.makeText(requireContext(), "Please uninstall the old application, then press the Back button..", Toast.LENGTH_LONG).show()
                        
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$conflictPackage")
                        }
                        uninstallThenInstallLauncher.launch(intent)
                    }
                )
            } else {
                launchApkInstaller(file)
            }
        }
    }

    private fun launchApkInstaller(file: File) {
        try {
            startActivity(ApkInstaller.installSingleApkIntent(requireContext(), file))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.fm_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSplitApkFile(file: File) {
        Toast.makeText(requireContext(), getString(R.string.fm_installing_split), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ApkInstaller.installSplitApkSet(requireContext(), file) { success, error ->
                    launch(Dispatchers.Main) {
                        if (_binding == null) return@launch
                        if (!success) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.fm_install_failed_format, error ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun openWithExternalApp(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), ApkInstaller.authorityFor(requireContext()), file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMimeType(file.extension))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.fm_open_with)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.fm_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessMimeType(extension: String): String {
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
    }

    private fun shareSelectedFiles() {
        val files = selectedFiles.filterNot { it.isDirectory }
        if (files.isEmpty()) return

        val authority = "${requireContext().packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(requireContext(), authority, it) as Uri }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.fm_share)))
    }

    private fun colorForFile(file: File): Int {
        val colorRes = if (file.isDirectory) {
            R.color.file_color_folder
        } else {
            val ext = file.extension.lowercase()
            when {
                ext in archiveExtensions -> R.color.file_color_archive
                ext in imageExtensions || ext == "svg" -> R.color.file_color_image
                ext in videoExtensions -> R.color.file_color_video
                ext in audioExtensions -> R.color.file_color_audio
                ext == "pdf" -> R.color.file_color_pdf
                ext in docExtensions -> R.color.file_color_doc
                ext in scriptExtensions -> R.color.file_color_code
                ext in libExtensions -> R.color.accent_native_start
                ext == "apk" -> R.color.file_color_apk
                else -> R.color.file_color_default
            }
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun loadApkIcon(iconView: ImageView, file: File) {
        val key = file.absolutePath + "_" + file.lastModified()
        val cached = apkIconCache.get(key)
        if (cached != null) {
            iconView.setImageDrawable(cached)
            return
        }
        iconView.setImageResource(R.drawable.ic_file)
        iconView.tag = key

        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val drawable: Drawable? = try {
                val pm = ctx.packageManager
                val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                info?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = file.absolutePath
                    appInfo.publicSourceDir = file.absolutePath
                    appInfo.loadIcon(pm)
                }
            } catch (e: Exception) {
                null
            }

            if (drawable != null) apkIconCache.put(key, drawable)

            withContext(Dispatchers.Main) {
                if (_binding != null && iconView.tag == key && drawable != null) {
                    iconView.setImageDrawable(drawable)
                }
            }
        }
    }

    inner class FileAdapter(
        private val onItemClick: (File) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private var list = listOf<File>()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun submitList(newList: List<File>) {
            val oldList = list
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldList.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    oldList[oldPos].absolutePath == newList[newPos].absolutePath
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val o = oldList[oldPos]; val n = newList[newPos]
                    return o.name == n.name && o.length() == n.length() && o.lastModified() == n.lastModified()
                }
            })
            list = newList
            diffResult.dispatchUpdatesTo(this)
        }

        fun getCurrentList(): List<File> = list

        inner class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = list[position]
            val iv = holder.binding.ivIcon
            val badge = holder.binding.tvExtBadge
            val ext = file.extension.lowercase()
            val isImage = !file.isDirectory && ext in imageExtensions
            val isApk = !file.isDirectory && ext == "apk"
            val isVideo = !file.isDirectory && ext in videoExtensions
            val isAudio = !file.isDirectory && ext in audioExtensions
            val isArchive = !file.isDirectory && ext in archiveExtensions
            val isScript = !file.isDirectory && ext in scriptExtensions
            val isDoc = !file.isDirectory && (ext in docExtensions || ext == "pdf")
            val isLib = !file.isDirectory && ext in libExtensions

            holder.binding.tvName.text = file.name

            Glide.with(this@FileManagerFragment).clear(iv)
            iv.tag = null
            holder.binding.iconContainer.backgroundTintList = ColorStateList.valueOf(colorForFile(file))

            when {
                isImage || isApk -> {
                    badge.visibility = View.GONE
                    iv.visibility = View.VISIBLE
                    val sizePx = dp(42)
                    iv.layoutParams = iv.layoutParams.apply { width = sizePx; height = sizePx }
                    iv.imageTintList = null
                    iv.setPadding(0, 0, 0, 0)

                    if (isImage) {
                        iv.scaleType = ImageView.ScaleType.CENTER_CROP
                        Glide.with(this@FileManagerFragment)
                            .load(file)
                            .signature(ObjectKey(file.lastModified().toString() + file.length()))
                            .transform(CenterCrop(), RoundedCorners(dp(10)))
                            .placeholder(R.drawable.ic_type_image)
                            .error(R.drawable.ic_type_image)
                            .into(iv)
                    } else {
                        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        iv.setPadding(dp(4), dp(4), dp(4), dp(4))
                        loadApkIcon(iv, file)
                    }
                }
                else -> {
                    badge.visibility = View.GONE
                    iv.visibility = View.VISIBLE
                    val sizePx = dp(22)
                    iv.layoutParams = iv.layoutParams.apply { width = sizePx; height = sizePx }
                    iv.setPadding(0, 0, 0, 0)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.white))
                    val iconRes = when {
                        file.isDirectory -> R.drawable.ic_folder
                        isVideo -> R.drawable.ic_type_video
                        isAudio -> R.drawable.ic_type_audio
                        isArchive -> R.drawable.ic_type_archive
                        isScript -> R.drawable.ic_filter_script
                        isDoc -> R.drawable.ic_filter_doc
                        isLib -> R.drawable.ic_filter_lib
                        else -> R.drawable.ic_file
                    }
                    iv.setImageResource(iconRes)
                }
            }

            if (isGlobalMode) {
                val kb = file.length() / 1024
                val parentPath = file.parentFile?.absolutePath
                    ?.replace(rootDir.absolutePath, getString(R.string.fm_internal)) ?: ""
                holder.binding.tvDetails.text = "$parentPath  •  $kb KB"
            } else if (file.isDirectory) {
                val items = file.list()?.size ?: 0
                holder.binding.tvDetails.text = "${getString(R.string.fm_items_count, items)} | ${dateFormat.format(Date(file.lastModified()))}"
            } else {
                val kb = file.length() / 1024
                holder.binding.tvDetails.text = "$kb KB | ${dateFormat.format(Date(file.lastModified()))}"
            }

            val isSelected = selectedFiles.contains(file)
            if (selectedFiles.isNotEmpty()) {
                holder.binding.cbSelect.visibility = View.VISIBLE
                holder.binding.cbSelect.isChecked = isSelected
            } else {
                holder.binding.cbSelect.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
            holder.binding.cbSelect.setOnClickListener { onItemLongClick(file) }
        }

        override fun getItemCount() = list.size
    }
}