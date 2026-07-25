package com.mondns.app

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
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.FragmentApkExtractorBinding
import com.mondns.app.databinding.ItemAppBinding
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class ApkExtractorFragment : Fragment() {
    private var _binding: FragmentApkExtractorBinding? = null
    private val binding get() = _binding!!

    data class AppModel(val name: String, val packageName: String, val icon: Drawable, val sourceDir: String, val splitDirs: Array<String>?)
    
    private val appList = mutableListOf<AppModel>()
    private val filteredList = mutableListOf<AppModel>()
    private lateinit var appAdapter: AppAdapter

    private fun updateFilteredList(newList: List<AppModel>) {
        val oldList = filteredList.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos].packageName == newList[newPos].packageName
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos] == newList[newPos]
        })
        filteredList.clear()
        filteredList.addAll(newList)
        diffResult.dispatchUpdatesTo(appAdapter)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApkExtractorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        appAdapter = AppAdapter(filteredList)
        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = appAdapter
        
        setupSearch()
        loadApps()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                val newFiltered = if (query.isEmpty()) {
                    appList.toList()
                } else {
                    appList.filter { it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
                }
                updateFilteredList(newFiltered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.rvApps.visibility = View.GONE

        thread {
            val pm = requireContext().packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val tempList = mutableListOf<AppModel>()

            for (appInfo in packages) {
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    tempList.add(AppModel(name, appInfo.packageName, icon, appInfo.sourceDir, appInfo.splitSourceDirs))
                }
            }
            
            tempList.sortBy { it.name.lowercase() }

            activity?.runOnUiThread {
                if (_binding != null) {
                    appList.clear()
                    appList.addAll(tempList)

                    updateFilteredList(tempList)

                    binding.progressBar.visibility = View.GONE
                    binding.rvApps.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Please grant 'All Files Access' to save APKs.", Toast.LENGTH_LONG).show()
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

    private fun extractApk(app: AppModel) {
        if (!checkStoragePermission()) return

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Extracting App")
            .setMessage("Please wait, copying ${app.name}...")
            .setCancelable(false)
            .create()
            
        dialog.show()
        
        thread {
            try {
                val extractFolder = File(Environment.getExternalStorageDirectory(), "MonToolKit/Apks")
                
                if (!extractFolder.exists() && !extractFolder.mkdirs()) {
                    throw Exception("Failed to create folder MonToolkit_APKs. Check storage permissions.")
                }

                val safeAppName = app.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val hasSplits = !app.splitDirs.isNullOrEmpty()
                val ext = if (hasSplits) "apks" else "apk"
                val destFile = File(extractFolder, "${safeAppName}_${app.packageName}.$ext")
                
                if (hasSplits) {
                    // Gabungkan base.apk dan split_*.apk menjadi .apks
                    ZipOutputStream(destFile.outputStream()).use { zout ->
                        zout.putNextEntry(ZipEntry("base.apk"))
                        File(app.sourceDir).inputStream().use { it.copyTo(zout) }
                        zout.closeEntry()
                        
                        for (split in app.splitDirs!!) {
                            val splitFile = File(split)
                            zout.putNextEntry(ZipEntry(splitFile.name))
                            splitFile.inputStream().use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                } else {
                    val sourceFile = File(app.sourceDir)
                    sourceFile.copyTo(destFile, overwrite = true)
                }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "✔ Success! Saved to MonToolKit/Apks", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Error")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class AppAdapter(private val list: List<AppModel>) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(val itemBinding: ItemAppBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = list[position]
            holder.itemBinding.tvAppName.text = app.name
            holder.itemBinding.tvAppPackage.text = app.packageName
            holder.itemBinding.ivAppIcon.setImageDrawable(app.icon)

            holder.itemBinding.btnExtract.setOnClickListener {
                extractApk(app)
            }
        }

        override fun getItemCount() = list.size
    }
}