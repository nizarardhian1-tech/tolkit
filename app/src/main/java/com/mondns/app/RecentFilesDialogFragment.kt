package com.mondns.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.DialogRecentFilesBinding
import kotlin.concurrent.thread

/**
 * Daftar file/folder yang pernah dibuka lewat tombol "File"/"Folder" --
 * TERPISAH dari [HtmlHistoryDialogFragment] (yang cuma menyimpan kode hasil
 * ketik/tempel manual). Tap salah satu -> file/foldernya dibuka lagi.
 */
class RecentFilesDialogFragment : BottomSheetDialogFragment() {

    var onSelect: ((RecentFileEntry) -> Unit)? = null

    private var _binding: DialogRecentFilesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RecentFileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogRecentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RecentFileAdapter(
            items = emptyList(),
            onClick = { entry ->
                onSelect?.invoke(entry)
                dismiss()
            },
            onDelete = { entry -> confirmDelete(entry) }
        )
        binding.rvRecentFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecentFiles.adapter = adapter

        binding.btnCloseRecent.setOnClickListener { dismiss() }
        binding.btnClearAllRecent.setOnClickListener { confirmClearAll() }

        loadRecent()
    }

    private fun loadRecent() {
        val ctx = requireContext().applicationContext
        thread {
            val list = AppDatabase.getInstance(ctx).recentFileDao().getAll()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                adapter.submitList(list)
                binding.tvEmptyRecent.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.btnClearAllRecent.isEnabled = list.isNotEmpty()
            }
        }
    }

    private fun confirmDelete(entry: RecentFileEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_recent_title)
            .setMessage(entry.displayName)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ctx = requireContext().applicationContext
                thread {
                    AppDatabase.getInstance(ctx).recentFileDao().delete(entry)
                    activity?.runOnUiThread { loadRecent() }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_all_recent_title)
            .setMessage(R.string.confirm_delete_all_recent_message)
            .setPositiveButton(R.string.action_delete_all) { _, _ ->
                val ctx = requireContext().applicationContext
                thread {
                    AppDatabase.getInstance(ctx).recentFileDao().deleteAll()
                    activity?.runOnUiThread { loadRecent() }
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
        const val TAG = "RecentFilesDialogFragment"
    }
}
