package com.mondns.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentHtmlRunnerBinding
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class HtmlRunnerFragment : Fragment() {

    private var _binding: FragmentHtmlRunnerBinding? = null
    private val binding get() = _binding!!

    private enum class SourceType { MANUAL, FILE, FOLDER }
    private var currentSourceType: SourceType = SourceType.MANUAL
    private var currentFileName: String = ""
    private var currentFolderName: String = ""

    // Map relPath -> DocumentFile kalau lagi mode FOLDER. Null = mode MANUAL/FILE.
    private var projectFiles: Map<String, DocumentFile>? = null
    private var entryRelPath: String = ""
    @Volatile private var liveEntryCode: String = ""

    private var assetLoader: WebViewAssetLoader? = null
    private var errorCount = 0

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSingleFile(uri)
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) loadFolder(uri)
    }

    private var pendingExportCode: String = ""
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) exportToUri(uri, pendingExportCode)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHtmlRunnerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupPreviewWebView()
        setupNativeEditor()

        binding.toggleTabs.check(R.id.tabCode)
        binding.toggleTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showTab(checkedId)
        }

        binding.btnOpenFile.setOnClickListener { openFileLauncher.launch(arrayOf("*/*")) }
        binding.btnOpenFolder.setOnClickListener { openFolderLauncher.launch(null) }
        binding.btnRun.setOnClickListener { runCode() }

        binding.btnRecentFiles.setOnClickListener { showRecentFiles() }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnSaveExport.setOnClickListener {
            val code = getCurrentCode()
            pendingExportCode = code
            exportLauncher.launch(suggestedFileName(code))
        }
        
        binding.btnClearConsole.setOnClickListener { clearConsole() }
        binding.btnCopyConsole.setOnClickListener { copyConsoleLog() }
    }

    // ============================================================
    //  EDITOR NATIVE (Simpel, Ringan, Cepat)
    // ============================================================

    private fun setupNativeEditor() {
        binding.etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                updateEditorLineNumbers(s.toString())
                HtmlSyntaxHighlighter.highlight(s)
            }
        })
        binding.etCode.onScrollChangedListener = { scrollY -> binding.tvLineNumbers.scrollTo(0, scrollY) }
        updateEditorLineNumbers("")
    }

    private fun updateEditorLineNumbers(text: String) {
        if (_binding == null) return
        val lineCount = text.count { it == '\n' } + 1
        binding.tvLineNumbers.text = (1..lineCount).joinToString("\n")
    }

    private fun getCurrentCode(): String {
        return binding.etCode.text?.toString() ?: ""
    }

    private fun setEditorText(text: String) {
        binding.etCode.setText(text)
        binding.etCode.setSelection(0)
    }

    // ============================================================
    //  Label sumber kode aktif
    // ============================================================

    private fun updateActiveSourceLabel() {
        if (_binding == null) return
        when (currentSourceType) {
            SourceType.FILE -> {
                binding.tvActiveSource.text = getString(R.string.active_source_file_format, currentFileName)
                binding.tvActiveSource.visibility = View.VISIBLE
            }
            SourceType.FOLDER -> {
                binding.tvActiveSource.text = getString(R.string.active_source_folder_format, currentFolderName, entryRelPath)
                binding.tvActiveSource.visibility = View.VISIBLE
            }
            SourceType.MANUAL -> {
                binding.tvActiveSource.text = ""
                binding.tvActiveSource.visibility = View.GONE
            }
        }
    }

    // ============================================================
    //  RIWAYAT JALANKAN (History) - Otomatis simpan tiap kali RUN ditekan
    // ============================================================

    private fun showHistory() {
        val dialog = HtmlHistoryDialogFragment()
        dialog.onSelect = { entry ->
            projectFiles = null
            entryRelPath = ""
            currentSourceType = SourceType.MANUAL
            setEditorText(entry.code)
            binding.tvActiveSource.text = getString(R.string.active_source_history_format, entry.title)
            binding.tvActiveSource.visibility = View.VISIBLE
            binding.toggleTabs.check(R.id.tabCode)
        }
        dialog.show(childFragmentManager, HtmlHistoryDialogFragment.TAG)
    }

    private fun saveToHistory(code: String) {
        if (code.isBlank()) return
        val appCtx = requireContext().applicationContext
        
        // Sesuaikan label sumber history dengan tipe file saat ini
        val label = when (currentSourceType) {
            SourceType.FILE -> "File: $currentFileName"
            SourceType.FOLDER -> "Folder: $currentFolderName ($entryRelPath)"
            else -> getString(R.string.source_label_manual)
        }
        
        val untitled = getString(R.string.title_untitled_html)
        
        thread {
            val dao = AppDatabase.getInstance(appCtx).htmlHistoryDao()
            val latest = dao.getLatest()
            // Jangan simpan kalau kodenya sama persis dengan history terakhir
            if (latest != null && latest.code == code) return@thread

            dao.insert(
                HtmlHistoryEntry(
                    title = deriveTitle(code, untitled),
                    code = code,
                    sourceLabel = label,
                    createdAt = System.currentTimeMillis()
                )
            )
            val total = dao.count()
            if (total > MAX_HISTORY_ITEMS) dao.deleteOldest(total - MAX_HISTORY_ITEMS)
        }
    }

    private fun deriveTitle(code: String, fallback: String): String {
        val titleTag = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(code)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!titleTag.isNullOrBlank()) return titleTag.take(60)

        val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (!firstLine.isNullOrBlank()) return firstLine.take(60)

        return fallback
    }

    // ============================================================
    //  FILE TERAKHIR (Recent Files)
    // ============================================================

    private fun showRecentFiles() {
        val dialog = RecentFilesDialogFragment()
        dialog.onSelect = { entry ->
            val uri = Uri.parse(entry.uri)
            if (entry.type == "folder") {
                loadFolder(uri, entry.entryRelPath)
            } else {
                loadSingleFile(uri)
            }
        }
        dialog.show(childFragmentManager, RecentFilesDialogFragment.TAG)
    }

    private fun saveRecentFile(entry: RecentFileEntry) {
        val appCtx = requireContext().applicationContext
        thread {
            val dao = AppDatabase.getInstance(appCtx).recentFileDao()
            dao.upsert(entry)
            val total = dao.count()
            if (total > MAX_RECENT_FILES) dao.deleteOldest(total - MAX_RECENT_FILES)
        }
    }

    // ============================================================
    //  SIMPAN / EXPORT
    // ============================================================

    private fun suggestedFileName(code: String): String {
        val raw = deriveTitle(code, "untitled")
        val safe = raw.replace(Regex("[^a-zA-Z0-9-_ ]"), "").trim().replace(Regex("\\s+"), "_")
        return (if (safe.isBlank()) "untitled" else safe.take(40)) + ".html"
    }

    private fun exportToUri(uri: Uri, code: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(code.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            appendConsole("ERROR", getString(R.string.error_save_file_failed, e.message ?: ""), "#FF6B6B")
        }
    }

    private fun clearConsole() {
        errorCount = 0
        binding.tabConsole.text = "Console"
        binding.tvConsole.text = getString(R.string.status_console_cleared)
    }

    private fun copyConsoleLog() {
        val text = binding.tvConsole.text.toString()
        if (text.isBlank()) return
        
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Console Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Log berhasil disalin", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    //  WEBVIEW / PREVIEW
    // ============================================================

    private fun setupPreviewWebView() {
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                    ConsoleMessage.MessageLevel.WARNING -> "WARN"
                    ConsoleMessage.MessageLevel.TIP -> "TIP"
                    else -> "LOG"
                }
                val color = when (level) {
                    "ERROR" -> "#FF6B6B"
                    "WARN" -> "#F0C674"
                    else -> "#8B949E"
                }
                val loc = "${consoleMessage.sourceId().substringAfterLast('/')}:${consoleMessage.lineNumber()}"
                appendConsole(level, "${consoleMessage.message()}  ($loc)", color)
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                if (_binding == null) { result?.cancel(); return true }
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .setCancelable(true)
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                if (_binding == null) { result?.cancel(); return true }
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                if (_binding == null) { result?.cancel(); return true }
                val input = android.widget.EditText(requireContext()).apply { setText(defaultValue) }
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader?.shouldInterceptRequest(request.url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    appendConsole("ERROR", getString(R.string.error_load_page_failed, error?.description ?: ""), "#FF6B6B")
                }
            }
        }
    }

    private fun showTab(checkedId: Int) {
        binding.codeEditorContainer.visibility = if (checkedId == R.id.tabCode) View.VISIBLE else View.GONE
        binding.webView.visibility = if (checkedId == R.id.tabPreview) View.VISIBLE else View.GONE
        binding.consoleContainer.visibility = if (checkedId == R.id.tabConsole) View.VISIBLE else View.GONE

        if (checkedId != R.id.tabCode) {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    private fun runCode() {
        val code = getCurrentCode()
        if (_binding == null) return
        
        errorCount = 0
        binding.tabConsole.text = "Console"
        binding.tvConsole.text = getString(R.string.status_running_console)

        liveEntryCode = code
        
        // SELALU simpan ke History setiap kali menekan RUN (Sesuai request user)
        saveToHistory(code)

        val files = projectFiles
        if (files != null && entryRelPath.isNotEmpty()) {
            assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/project/", ProjectPathHandler(files))
                .build()
            binding.webView.loadUrl("https://appassets.androidplatform.net/project/$entryRelPath")
        } else {
            binding.webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/", code, "text/html", "UTF-8", null
            )
        }

        binding.toggleTabs.check(R.id.tabPreview)
    }

    private inner class ProjectPathHandler(
        private val files: Map<String, DocumentFile>
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val normalized = path.trimStart('/')
            if (normalized == entryRelPath) {
                val bytes = liveEntryCode.toByteArray(Charsets.UTF_8)
                return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(bytes))
            }
            val doc = files[normalized] ?: return null
            return try {
                val stream = requireContext().contentResolver.openInputStream(doc.uri) ?: return null
                WebResourceResponse(guessMime(normalized), null, stream)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun guessMime(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    private fun loadSingleFile(uri: Uri) {
        try {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }

            val name = DocumentFile.fromSingleUri(requireContext(), uri)?.name ?: uri.lastPathSegment ?: "file.html"
            val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            projectFiles = null
            entryRelPath = ""
            currentSourceType = SourceType.FILE
            currentFileName = name
            setEditorText(text)
            updateActiveSourceLabel()
            binding.toggleTabs.check(R.id.tabCode)

            saveRecentFile(
                RecentFileEntry(
                    uri = uri.toString(),
                    displayName = name,
                    type = "file",
                    entryRelPath = "",
                    lastOpenedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            appendConsole("ERROR", getString(R.string.error_open_file_failed, e.message ?: ""), "#FF6B6B")
        }
    }

    private fun loadFolder(treeUri: Uri, preferredRelPath: String = "") {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { }

        val root = DocumentFile.fromTreeUri(requireContext(), treeUri)
        if (root == null) {
            appendConsole("ERROR", getString(R.string.error_open_folder_failed), "#FF6B6B")
            return
        }

        binding.tvActiveSource.text = getString(R.string.status_scanning_folder)
        binding.tvActiveSource.visibility = View.VISIBLE
        binding.btnOpenFolder.isEnabled = false

        thread {
            val map = HashMap<String, DocumentFile>()
            walkFolder(root, "", map, maxFiles = 3000)
            val htmlCandidates = map.keys.filter {
                it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
            }.sorted()

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.btnOpenFolder.isEnabled = true

                if (htmlCandidates.isEmpty()) {
                    binding.tvActiveSource.text = ""
                    binding.tvActiveSource.visibility = View.GONE
                    appendConsole("ERROR", getString(R.string.error_no_html_in_folder), "#FF6B6B")
                    return@runOnUiThread
                }

                projectFiles = map
                val rootName = root.name ?: "project"

                when {
                    preferredRelPath.isNotEmpty() && map.containsKey(preferredRelPath) -> {
                        openEntryFromProject(preferredRelPath, map, rootName, treeUri)
                    }
                    htmlCandidates.size == 1 -> {
                        openEntryFromProject(htmlCandidates[0], map, rootName, treeUri)
                    }
                    else -> {
                        val preselect = htmlCandidates.indexOfFirst { it.equals("index.html", ignoreCase = true) }
                            .let { if (it == -1) 0 else it }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dialog_pick_html_file_title)
                            .setSingleChoiceItems(htmlCandidates.toTypedArray(), preselect) { dialog, which ->
                                openEntryFromProject(htmlCandidates[which], map, rootName, treeUri)
                                dialog.dismiss()
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun openEntryFromProject(relPath: String, map: Map<String, DocumentFile>, rootName: String, treeUri: Uri) {
        val doc = map[relPath] ?: return
        val text = try {
            requireContext().contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            appendConsole("ERROR", getString(R.string.error_read_file_failed, relPath, e.message ?: ""), "#FF6B6B")
            return
        }
        entryRelPath = relPath
        currentSourceType = SourceType.FOLDER
        currentFolderName = rootName
        setEditorText(text)
        updateActiveSourceLabel()
        binding.toggleTabs.check(R.id.tabCode)

        saveRecentFile(
            RecentFileEntry(
                uri = treeUri.toString(),
                displayName = rootName,
                type = "folder",
                entryRelPath = relPath,
                lastOpenedAt = System.currentTimeMillis()
            )
        )
    }

    private fun walkFolder(dir: DocumentFile, base: String, out: MutableMap<String, DocumentFile>, maxFiles: Int) {
        if (out.size >= maxFiles) return
        for (child in dir.listFiles()) {
            if (out.size >= maxFiles) return
            val name = child.name ?: continue
            val relPath = if (base.isEmpty()) name else "$base/$name"
            if (child.isDirectory) {
                walkFolder(child, relPath, out, maxFiles)
            } else {
                out[relPath] = child
            }
        }
    }

    private fun appendConsole(level: String, message: String, colorHex: String) {
        if (_binding == null) return
        val line = SpannableString("[$level] $message\n")
        line.setSpan(ForegroundColorSpan(Color.parseColor(colorHex)), 0, line.length, 0)
        binding.tvConsole.append(line)
        binding.scrollConsole.post {
            if (_binding != null) binding.scrollConsole.fullScroll(View.FOCUS_DOWN)
        }
        if (level == "ERROR") {
            errorCount++
            binding.tabConsole.text = "Console ($errorCount)"
        }
    }

    override fun onDestroyView() {
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MAX_HISTORY_ITEMS = 50
        private const val MAX_RECENT_FILES = 30
    }
}