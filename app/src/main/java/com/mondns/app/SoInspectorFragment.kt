package com.mondns.app

import android.app.Activity
import android.content.BroadcastReceiver
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mondns.app.databinding.FragmentSoInspectorBinding
import com.mondns.app.databinding.ItemSymbolBinding
import kotlin.concurrent.thread

class SoInspectorFragment : Fragment() {
    private var _binding: FragmentSoInspectorBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val STATE_REQUEST_ID = "so_inspector_request_id"
    }

    // Di atas ukuran ini kasih peringatan dulu (lib IL2CPP/Unity kadang ratusan MB).
    private val LARGE_FILE_WARN_BYTES = 80L * 1024 * 1024

    private var currentRequestId = 0L
    private var receiverRegistered = false
    private var currentInfo: ElfParser.ElfInfo? = null
    private var currentDisplayName: String = ""

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reqId = intent?.getLongExtra(InspectionService.EXTRA_REQUEST_ID, -1L) ?: -1L
            if (reqId != currentRequestId) return // notifikasi/hasil dari request lama, abaikan

            when (intent?.action) {
                InspectionService.ACTION_COMPLETE -> {
                    val result = InspectionService.lastResult
                    setLoading(false)
                    if (result != null) {
                        renderResult(result, InspectionService.lastDisplayName, InspectionService.lastSizeBytes)
                    } else {
                        showError(getString(R.string.inspector_error_invalid))
                    }
                }
                InspectionService.ACTION_ERROR -> {
                    setLoading(false)
                    showError(intent.getStringExtra(InspectionService.EXTRA_MESSAGE) ?: getString(R.string.inspector_error_read))
                }
            }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> inspectUri(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSoInspectorBinding.inflate(inflater, container, false)
        currentRequestId = savedInstanceState?.getLong(STATE_REQUEST_ID) ?: 0L
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_REQUEST_ID, currentRequestId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvExported.layoutManager = LinearLayoutManager(requireContext())
        binding.rvImported.layoutManager = LinearLayoutManager(requireContext())

        // TANGKAP FILE DARI FILE MANAGER JIKA ADA
        arguments?.getString("soFilePath")?.let { path ->
            val file = java.io.File(path) // <-- Pakai java.io.File agar tidak perlu import
            if (file.exists()) {
                val sizeBytes = file.length()
                val displayName = file.name
                startInspect(Uri.fromFile(file), displayName, sizeBytes)
            }
        }

        binding.cardFileSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(intent)
        }

        binding.btnExportSymbols.setOnClickListener {
            if (checkStoragePermission()) showExportOptionsDialog()
        }

        // TAMPILAN BARU: Tombol Extract Strings
        binding.btnExtractStrings.setOnClickListener {
            if (checkStoragePermission()) runExtractStringsInBackground()
        }

        // TAMPILAN BARU: Tombol Strip Symbols
        binding.btnStripSymbols.setOnClickListener {
            if (checkStoragePermission()) runStripInBackground()
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Please grant 'All Files Access' to save the export.", Toast.LENGTH_LONG).show()
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

    private fun showExportOptionsDialog() {
        val info = currentInfo
        if (info == null) {
            Toast.makeText(requireContext(), getString(R.string.inspector_error_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.inspector_export_button)
            .setMessage(R.string.inspector_export_options_message)
            .setPositiveButton(R.string.inspector_export_include_hex) { _, _ ->
                runExportInBackground(true)
            }
            .setNegativeButton(R.string.inspector_export_no_hex) { _, _ ->
                runExportInBackground(false)
            }
            .setNeutralButton(R.string.fm_cancel, null)
            .show()
    }

    private fun runExportInBackground(includeHex: Boolean) {
        val info = currentInfo ?: return
        val displayName = currentDisplayName

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.inspector_exporting_title)
            .setMessage(R.string.inspector_exporting_message)
            .setCancelable(false)
            .create()
        dialog.show()

        thread {
            try {
                val file = ExportUtils.exportElfInfoToFile(
                    requireContext(),
                    info,
                    displayName,
                    sourceFilePath = InspectionService.lastCachedFilePath,
                    includeHexDump = includeHex
                )
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.inspector_export_done_title)
                        .setMessage(getString(R.string.inspector_export_done_message, file.absolutePath))
                        .setPositiveButton(R.string.inspector_export_share) { _, _ -> ExportUtils.shareTextFile(requireContext(), file) }
                        .setNegativeButton(R.string.conv_done_ok, null)
                        .show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.inspector_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    

    private fun runExtractStringsInBackground() {
        val cachedPath = InspectionService.lastCachedFilePath
        if (cachedPath == null || !java.io.File(cachedPath).exists()) {
            Toast.makeText(requireContext(), getString(R.string.inspector_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.inspector_extracting_title)
            .setMessage(R.string.inspector_extracting_message)
            .setCancelable(false)
            .create()
        dialog.show()

        thread {
            try {
                // ... di dalam thread { try { ...
val displayName = currentDisplayName // Ambil nama file aslinya!
val file = StringsExtractor.extract(java.io.File(cachedPath), displayName)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.inspector_export_done_title)
                        .setMessage(getString(R.string.inspector_export_done_message, file.absolutePath))
                        .setPositiveButton(R.string.inspector_export_share) { _, _ -> ExportUtils.shareTextFile(requireContext(), file) }
                        .setNegativeButton(R.string.conv_done_ok, null)
                        .show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.inspector_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.2f MB", mb)
    }

    private fun runStripInBackground() {
        val cachedPath = InspectionService.lastCachedFilePath
        if (cachedPath == null || !java.io.File(cachedPath).exists()) {
            Toast.makeText(requireContext(), getString(R.string.inspector_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.inspector_stripping_title)
            .setMessage(R.string.inspector_stripping_message)
            .setCancelable(false)
            .create()
        dialog.show()

        thread {
            try {
                val sourceFile = java.io.File(cachedPath)
                val displayName = currentDisplayName
                val safeName = displayName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
                val outDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "MonToolKit/Stripped")
                if (!outDir.exists()) outDir.mkdirs()
                val outFile = java.io.File(outDir, "${safeName}_stripped.so")

                val result = ElfStripper.strip(sourceFile, outFile)

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    if (result.success && result.outputFile != null) {
                        val removedList = if (result.removedSections.isEmpty()) "-" else result.removedSections.joinToString("\n") { "  • $it" }
                        val saved = result.originalSize - result.strippedSize
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.inspector_strip_done_title)
                            .setMessage(
                                getString(
                                    R.string.inspector_strip_done_message,
                                    result.outputFile.absolutePath,
                                    formatBytes(result.originalSize),
                                    formatBytes(result.strippedSize),
                                    formatBytes(saved),
                                    removedList
                                )
                            )
                            .setPositiveButton(R.string.inspector_export_share) { _, _ -> ExportUtils.shareFile(requireContext(), result.outputFile, "application/octet-stream") }
                            .setNegativeButton(R.string.conv_done_ok, null)
                            .show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.inspector_strip_failed, result.error ?: "unknown"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.inspector_strip_failed, e.message ?: "unknown"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun showFunctionDetail(sym: ElfParser.ElfSymbol) {
        val cachedPath = InspectionService.lastCachedFilePath
        if (cachedPath == null || sym.fileOffset < 0) {
            Toast.makeText(requireContext(), getString(R.string.inspector_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(cachedPath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), getString(R.string.inspector_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val bytes = ElfParser.readBytesAt(file, sym.fileOffset, sym.size.coerceAtLeast(16))
        if (bytes == null) {
            Toast.makeText(requireContext(), getString(R.string.inspector_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val hexDump = HexDumpUtil.format(bytes, sym.address)
        val messageView = android.widget.TextView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = hexDump
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(messageView) }

        // TIDAK ADA LAGI setNeutralButton untuk Disassemble
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(sym.displayName)
            .setView(scroll)
            .setPositiveButton(R.string.conv_done_ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(InspectionService.ACTION_COMPLETE)
            addAction(InspectionService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(resultReceiver, filter)
        }
        receiverRegistered = true

        // Catch-up: sinkronkan UI ke state terbaru
        if (currentRequestId != 0L) {
            when {
                InspectionService.isRunning && InspectionService.runningRequestId == currentRequestId -> setLoading(true)
                InspectionService.lastRequestId == currentRequestId && InspectionService.lastResult != null ->
                    renderResult(InspectionService.lastResult!!, InspectionService.lastDisplayName, InspectionService.lastSizeBytes)
                InspectionService.lastRequestId == currentRequestId && InspectionService.lastErrorMessage != null -> {
                    setLoading(false)
                    showError(InspectionService.lastErrorMessage!!)
                }
                else -> {
                    setLoading(false)
                    showError(getString(R.string.inspector_state_lost))
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            requireContext().unregisterReceiver(resultReceiver)
            receiverRegistered = false
        }
    }

    private fun inspectUri(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: "selected_file.so"
        val sizeBytes = querySize(uri)

        if (sizeBytes > LARGE_FILE_WARN_BYTES) {
            val sizeMb = sizeBytes / (1024 * 1024)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.inspector_large_file_title)
                .setMessage(getString(R.string.inspector_large_file_message, sizeMb))
                .setPositiveButton(R.string.conv_large_file_continue) { _, _ -> startInspect(uri, displayName, sizeBytes) }
                .setNegativeButton(R.string.conv_large_file_cancel, null)
                .show()
        } else {
            startInspect(uri, displayName, sizeBytes)
        }
    }

    private fun startInspect(uri: Uri, displayName: String, sizeBytes: Long) {
        currentRequestId = System.currentTimeMillis()

        binding.tvFileName.text = displayName
        binding.layoutResult.visibility = View.GONE
        binding.tvError.visibility = View.GONE
        setLoading(true)

        val intent = Intent(requireContext(), InspectionService::class.java).apply {
            putExtra(InspectionService.EXTRA_URI, uri)
            putExtra(InspectionService.EXTRA_DISPLAY_NAME, displayName)
            putExtra(InspectionService.EXTRA_SIZE_BYTES, sizeBytes)
            putExtra(InspectionService.EXTRA_REQUEST_ID, currentRequestId)
        }
        ContextCompat.startForegroundService(requireContext(), intent)

        Toast.makeText(requireContext(), getString(R.string.inspector_background_toast), Toast.LENGTH_LONG).show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressInspector.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvInspectorProgressLabel.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvInspectorProgressLabel.text = getString(R.string.inspector_background_hint)
        binding.cardFileSelect.isEnabled = !loading
    }

    private fun renderResult(info: ElfParser.ElfInfo, displayName: String, sizeBytes: Long) {
        currentInfo = info
        currentDisplayName = displayName

        binding.tvFileName.text = displayName
        binding.tvError.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE

        binding.tvSoName.text = info.soName ?: displayName
        binding.tvArch.text = getString(
            R.string.inspector_arch_label,
            info.architecture,
            if (info.is64Bit) "64-bit" else "32-bit"
        )
        binding.tvStripped.text = if (info.isStripped) {
            getString(R.string.inspector_stripped_yes)
        } else {
            getString(R.string.inspector_stripped_no)
        }
        binding.tvEntryPoint.text = getString(
            R.string.inspector_entry_point,
            "0x" + info.entryPoint.toString(16).uppercase()
        )
        binding.tvBuildId.text = getString(
            R.string.inspector_build_id,
            info.buildId ?: getString(R.string.inspector_no_build_id)
        )
        val sizeForDisplay = if (sizeBytes > 0) sizeBytes else info.fileSize
        binding.tvFileSize.text = getString(R.string.inspector_file_size, sizeForDisplay / 1024)

        binding.tvSha256.text = getString(R.string.inspector_sha256, info.sha256 ?: "-")
        val nxText = when (info.hasNxStack) {
            true -> getString(R.string.inspector_yes)
            false -> getString(R.string.inspector_no)
            null -> getString(R.string.inspector_unknown)
        }
        val canaryText = if (info.hasStackCanary) getString(R.string.inspector_yes) else getString(R.string.inspector_no)
        val fortifyText = if (info.hasFortify) getString(R.string.inspector_yes) else getString(R.string.inspector_no)
        binding.tvHardening.text = getString(R.string.inspector_hardening, nxText, info.relro, canaryText, fortifyText)

        binding.tvNeededLibs.text = if (info.neededLibraries.isNotEmpty()) {
            info.neededLibraries.joinToString("\n")
        } else {
            getString(R.string.inspector_no_deps)
        }

        binding.tvExportedHeader.text = getString(R.string.inspector_exported_header, info.definedSymbols.size)
        binding.tvImportedHeader.text = getString(R.string.inspector_imported_header, info.undefinedSymbols.size)

        val displayCap = 20_000
        val exportedShown = if (info.definedSymbols.size > displayCap) info.definedSymbols.subList(0, displayCap) else info.definedSymbols
        val importedShown = if (info.undefinedSymbols.size > displayCap) info.undefinedSymbols.subList(0, displayCap) else info.undefinedSymbols

        if (info.definedSymbols.size > displayCap) {
            binding.tvExportedHeader.text = getString(R.string.inspector_exported_header_capped, info.definedSymbols.size, displayCap)
        }
        if (info.undefinedSymbols.size > displayCap) {
            binding.tvImportedHeader.text = getString(R.string.inspector_imported_header_capped, info.undefinedSymbols.size, displayCap)
        }

        binding.rvExported.adapter = SymbolAdapter(
            exportedShown,
            titleOf = { it.displayName },
            metaOf = { sym ->
                val rva = "0x${sym.address.toString(16).uppercase()}"
                val offsetPart = if (sym.fileOffset >= 0) " · Offset: 0x${sym.fileOffset.toString(16).uppercase()}" else ""
                val sectionPart = sym.sectionName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                "RVA: $rva$offsetPart · Size: 0x${sym.size.toString(16).uppercase()}$sectionPart"
            },
            onClick = { sym -> showFunctionDetail(sym) }
        )
        binding.rvImported.adapter = SymbolAdapter(
            importedShown,
            titleOf = { it.displayName },
            metaOf = { getString(R.string.inspector_imported_meta) }
        )
    }

    private fun showError(message: String) {
        currentInfo = null
        binding.layoutResult.visibility = View.GONE
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class SymbolAdapter<T>(
    private val items: List<T>,
    private val titleOf: (T) -> String,
    private val metaOf: (T) -> String,
    private val onClick: ((T) -> Unit)? = null
) : RecyclerView.Adapter<SymbolAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSymbolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSymbolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvSymbolName.text = titleOf(item)
        holder.binding.tvSymbolMeta.text = metaOf(item)
        if (onClick != null) {
            holder.binding.root.setOnClickListener { onClick.invoke(item) }
            holder.binding.root.isClickable = true
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        fun fromPairs(pairs: List<Pair<String, String>>): SymbolAdapter<Pair<String, String>> {
            return SymbolAdapter(pairs, { it.first }, { it.second })
        }
    }
}