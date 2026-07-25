package com.mondns.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.DialogHtmlHistoryBinding
import kotlin.concurrent.thread

/**
 * Daftar riwayat kode HTML yang pernah dijalankan lewat mode ketik/tempel
 * manual (tersimpan otomatis di Room DB tiap kali tombol RUN ditekan --
 * TIDAK termasuk file/folder yang dibuka, lihat [RecentFilesDialogFragment]
 * untuk itu). Tap salah satu -> kodenya dimuat lagi ke editor.
 */
class HtmlHistoryDialogFragment : BottomSheetDialogFragment() {

    var onSelect: ((HtmlHistoryEntry) -> Unit)? = null

    private var _binding: DialogHtmlHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: HtmlHistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogHtmlHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HtmlHistoryAdapter(
            items = emptyList(),
            onClick = { entry ->
                onSelect?.invoke(entry)
                dismiss()
            },
            onDelete = { entry -> confirmDelete(entry) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        binding.btnCloseHistory.setOnClickListener { dismiss() }
        binding.btnClearAllHistory.setOnClickListener { confirmClearAll() }

        loadHistory()
    }

    private fun loadHistory() {
        val ctx = requireContext().applicationContext
        thread {
            val list = AppDatabase.getInstance(ctx).htmlHistoryDao().getAll()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                adapter.submitList(list)
                binding.tvEmptyHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.btnClearAllHistory.isEnabled = list.isNotEmpty()
            }
        }
    }

    private fun confirmDelete(entry: HtmlHistoryEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_history_title)
            .setMessage(entry.title)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ctx = requireContext().applicationContext
                thread {
                    AppDatabase.getInstance(ctx).htmlHistoryDao().delete(entry)
                    activity?.runOnUiThread { loadHistory() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_all_history_title)
            .setMessage(R.string.confirm_delete_all_history_message)
            .setPositiveButton(R.string.action_delete_all) { _, _ ->
                val ctx = requireContext().applicationContext
                thread {
                    AppDatabase.getInstance(ctx).htmlHistoryDao().deleteAll()
                    activity?.runOnUiThread { loadHistory() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HtmlHistoryDialogFragment"
    }
}
