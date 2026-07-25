package com.mondns.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.mondns.app.databinding.FragmentCrashAnalyzerBinding
import kotlin.concurrent.thread

class CrashAnalyzerFragment : Fragment() {
    private var _binding: FragmentCrashAnalyzerBinding? = null
    private val binding get() = _binding!!

    // Baris backtrace native Android, contoh:
    //   #01 pc 0001a2b3  libmain.so
    //   #04 pc 00012345  /data/.../libmain.so (Java_com_x_y+120)
    //   #00 pc 0000000000f69ce4  /data/data/x/app_libs/liblogic.so (BuildId: c31d24ca53836cd2)
    // Group 3 = SISA baris setelah nama lib, diproses lebih lanjut supaya "(BuildId: ...)"
    // TIDAK ketuker dianggap nama fungsi yang sudah di-resolve (ini bug yang sebelumnya ada).
    private val frameRegex = Regex("""#\d+\s+pc\s+([0-9a-fA-F]+)\s+(\S+\.so)(.*)""")
    private val buildIdRegex = Regex("""BuildId:\s*([0-9a-fA-F]+)""")
    private val parenGroupRegex = Regex("""\(([^)]+)\)""")

    private var pickedSoName: String? = null
    private var pickedElfInfo: ElfParser.ElfInfo? = null

    private val soPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadSoFile(uri) }
        }
    }

    private val logFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadLogFile(uri) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCrashAnalyzerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvFrames.layoutManager = LinearLayoutManager(requireContext())

        binding.cardSoSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            soPicker.launch(intent)
        }

        binding.cardUploadLog.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            logFilePicker.launch(intent)
        }

        binding.btnAnalyze.setOnClickListener {
            analyze()
        }
    }

    private fun loadSoFile(uri: Uri) {
        val name = queryDisplayName(uri) ?: "library.so"
        binding.tvSoPicked.text = getString(R.string.inspector_parsing)
        thread {
            // Sama seperti fix di InspectionService: streaming copy ke cache file dulu,
            // JANGAN readBytes() penuh ke RAM — lib besar (IL2CPP/Unity) bisa OOM.
            var cacheFile: java.io.File? = null
            val info = try {
                val tmp = java.io.File(requireContext().cacheDir, "crash_so_${System.currentTimeMillis()}.so")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                cacheFile = tmp
                ElfParser.parse(tmp)
            } catch (e: Exception) {
                null
            } finally {
                cacheFile?.delete()
            }
            if (!isAdded) return@thread
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (info == null || !info.isValid) {
                    binding.tvSoPicked.text = getString(R.string.crash_pick_so_optional)
                    return@runOnUiThread
                }
                pickedSoName = name
                pickedElfInfo = info
                val buildIdShown = info.buildId?.take(16) ?: getString(R.string.inspector_no_build_id)
                binding.tvSoPicked.text = "$name  ·  BuildId: $buildIdShown"
            }
        }
    }

    private fun loadLogFile(uri: Uri) {
        thread {
            val text = try {
                requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            if (!isAdded) return@thread
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (text == null) {
                    showError(getString(R.string.crash_error_file_read))
                    return@runOnUiThread
                }
                binding.etCrashLog.setText(text)
                val lineCount = text.count { it == '\n' } + 1
                binding.tvLogLineCount.visibility = View.VISIBLE
                binding.tvLogLineCount.text = getString(R.string.crash_lines_loaded, lineCount)
                // Otomatis analisis begitu file selesai dimuat — file tombstone biasanya
                // ribuan baris, jadi user gak perlu tap ANALYZE manual lagi.
                analyze()
            }
        }
    }

    private fun analyze() {
        val logText = binding.etCrashLog.text?.toString().orEmpty()
        if (logText.isBlank()) {
            showError(getString(R.string.crash_error_empty))
            return
        }

        binding.tvCrashError.visibility = View.GONE
        binding.tvResultHeader.visibility = View.GONE
        binding.progressCrash.visibility = View.VISIBLE
        binding.btnAnalyze.isEnabled = false

        // Regex + resolusi simbol dikerjakan di background thread juga — tombstone bisa
        // ribuan baris, dan ini menghindari freeze UI kalau parsing-nya berat.
        val soInfoSnapshot = pickedElfInfo
        val soNameSnapshot = pickedSoName
        thread {
            val matches = frameRegex.findAll(logText).toList()
            val rows = matches.map { m ->
                buildRow(m, soInfoSnapshot, soNameSnapshot)
            }

            if (!isAdded) return@thread
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.progressCrash.visibility = View.GONE
                binding.btnAnalyze.isEnabled = true

                if (rows.isEmpty()) {
                    showError(getString(R.string.crash_error_no_frames))
                    return@runOnUiThread
                }
                binding.tvResultHeader.visibility = View.VISIBLE
                binding.tvResultHeader.text = getString(R.string.crash_result_header, rows.size)
                binding.rvFrames.adapter = SymbolAdapter.fromPairs(rows)
            }
        }
    }

    private fun buildRow(
        m: MatchResult,
        soInfo: ElfParser.ElfInfo?,
        soName: String?
    ): Pair<String, String> {
        val offsetHex = m.groupValues[1]
        val libPath = m.groupValues[2]
        val remainder = m.groupValues[3]
        val libBaseName = libPath.substringAfterLast('/')
        val offset = offsetHex.toLongOrNull(16) ?: 0L

        val logBuildId = buildIdRegex.find(remainder)?.groupValues?.get(1)
        // Simbol yang SUDAH di-resolve device sendiri = parenthetical yang BUKAN "(BuildId: ...)".
        // Ini fix dari bug sebelumnya, yang salah nganggap "(BuildId: ...)" sebagai nama fungsi.
        val alreadyResolved = parenGroupRegex.findAll(remainder)
            .map { it.groupValues[1] }
            .firstOrNull { !it.startsWith("BuildId", ignoreCase = true) }

        val buildIdSuffix = logBuildId?.let { " · BuildId $it" } ?: ""

        return when {
            alreadyResolved != null -> {
                alreadyResolved to "$libBaseName · pc 0x$offsetHex$buildIdSuffix (resolved by device)"
            }
            soInfo == null -> {
                getString(R.string.crash_no_so_uploaded) to "$libBaseName · pc 0x$offsetHex$buildIdSuffix"
            }
            else -> {
                val nameMatches = soName?.substringAfterLast('/') == libBaseName || soInfo.soName == libBaseName
                val buildIdMatches = logBuildId != null && soInfo.buildId != null &&
                    logBuildId.equals(soInfo.buildId, ignoreCase = true)
                val buildIdKnownMismatch = logBuildId != null && soInfo.buildId != null && !buildIdMatches

                when {
                    buildIdKnownMismatch -> {
                        getString(R.string.crash_build_id_mismatch) to
                            "$libBaseName · pc 0x$offsetHex · log=$logBuildId vs file=${soInfo.buildId?.take(16)}"
                    }
                    !nameMatches && !buildIdMatches -> {
                        getString(R.string.crash_wrong_library, libBaseName) to "pc 0x$offsetHex"
                    }
                    else -> {
                        val sym = ElfParser.resolveOffset(soInfo, offset)
                        val confidence = if (buildIdMatches) "exact match" else "name match only"
                        if (sym != null) {
                            val within = offset - sym.address
                            sym.displayName to "$libBaseName · pc 0x$offsetHex (+$within bytes, $confidence)"
                        } else if (soInfo.isStripped) {
                            getString(R.string.crash_stripped_no_symbols) to "$libBaseName · pc 0x$offsetHex"
                        } else {
                            getString(R.string.crash_no_match) to "$libBaseName · pc 0x$offsetHex"
                        }
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.tvResultHeader.visibility = View.GONE
        binding.tvCrashError.visibility = View.VISIBLE
        binding.tvCrashError.text = message
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
