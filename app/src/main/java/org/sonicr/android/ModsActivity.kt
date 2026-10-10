package org.sonicr.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sonicr.android.databinding.ActivityModsBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ModsActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_IMPORT_FOLDER = 2001
        private const val REQUEST_CODE_IMPORT_ZIP = 2002
    }

    data class ModInfo(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val description: String,
        val includeDir: String,
        val disableBlink: Boolean,
        var isEnabled: Boolean
    )

    private lateinit var binding: ActivityModsBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private val installedMods = mutableListOf<ModInfo>()
    private var modsMasterEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val modsDir = File(filesDir, "mods")
        if (!modsDir.exists()) {
            modsDir.mkdirs()
        }
        binding.tvModsPath.text = "Mods storage: ${modsDir.absolutePath}"

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            loadMods()
            Toast.makeText(this, "Mods reloaded", Toast.LENGTH_SHORT).show()
        }

        binding.cbMasterEnable.setOnCheckedChangeListener { _, isChecked ->
            modsMasterEnabled = isChecked
            saveConfig()
        }

        binding.btnImportMod.setOnClickListener {
            showImportDialog()
        }

        loadMods()
    }

    private fun showImportDialog() {
        val options = arrayOf("Import Mod Folder (SAF)", "Import Mod Archive (.zip)")
        AlertDialog.Builder(this)
            .setTitle("Import Community Mod")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchFolderPicker()
                    1 -> launchZipPicker()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT_FOLDER)
    }

    private fun launchZipPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT_ZIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            REQUEST_CODE_IMPORT_FOLDER -> {
                val uri = data.data ?: return
                importFolder(uri)
            }
            REQUEST_CODE_IMPORT_ZIP -> {
                val uri = data.data ?: return
                importZip(uri)
            }
        }
    }

    private fun importFolder(treeUri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Importing Mod")
            .setMessage("Copying mod files...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(this@ModsActivity, treeUri)
                        ?: return@withContext "Failed to open selected directory"
                    val modFolderName = rootDoc.name ?: "ImportedMod_${System.currentTimeMillis()}"
                    val targetModDir = File(File(filesDir, "mods"), modFolderName)
                    targetModDir.mkdirs()

                    copyDocumentTree(rootDoc, targetModDir)
                    "Imported '$modFolderName' successfully"
                } catch (e: Exception) {
                    "Error importing folder: ${e.message}"
                }
            }

            progressDialog.dismiss()
            Toast.makeText(this@ModsActivity, result, Toast.LENGTH_LONG).show()
            loadMods()
        }
    }

    private fun copyDocumentTree(src: DocumentFile, dst: File) {
        val children = src.listFiles()
        val buffer = ByteArray(64 * 1024)
        for (child in children) {
            val name = child.name ?: continue
            val destChild = File(dst, name)
            if (child.isDirectory) {
                destChild.mkdirs()
                copyDocumentTree(child, destChild)
            } else if (child.isFile) {
                val input: InputStream? = contentResolver.openInputStream(child.uri)
                if (input != null) {
                    input.use { inStream ->
                        FileOutputStream(destChild).use { outStream ->
                            var read: Int
                            while (inStream.read(buffer).also { read = it } != -1) {
                                outStream.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun importZip(zipUri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Importing Mod")
            .setMessage("Extracting ZIP archive...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(zipUri)
                        ?: return@withContext "Failed to open ZIP stream"

                    val modsDir = File(filesDir, "mods")
                    var modFolderName = "Mod_${System.currentTimeMillis()}"

                    // Query display name
                    contentResolver.query(zipUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0) {
                                val rawName = cursor.getString(nameIdx)
                                if (!rawName.isNullOrBlank()) {
                                    modFolderName = rawName.removeSuffix(".zip").removeSuffix(".ZIP")
                                }
                            }
                        }
                    }

                    val targetDir = File(modsDir, modFolderName)
                    targetDir.mkdirs()

                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        val buffer = ByteArray(64 * 1024)
                        while (entry != null) {
                            val entryName = entry.name.replace('\\', '/')
                            // Skip zip-slip paths
                            if (!entryName.contains("..")) {
                                val outFile = File(targetDir, entryName)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        var len: Int
                                        while (zis.read(buffer).also { len = it } != -1) {
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    "Extracted '$modFolderName' successfully"
                } catch (e: Exception) {
                    "Error extracting zip: ${e.message}"
                }
            }

            progressDialog.dismiss()
            Toast.makeText(this@ModsActivity, result, Toast.LENGTH_LONG).show()
            loadMods()
        }
    }

    private fun loadMods() {
        installedMods.clear()
        binding.modsListContainer.removeAllViews()

        val modsDir = File(filesDir, "mods")
        if (!modsDir.exists()) {
            modsDir.mkdirs()
        }

        // Read mods_config.json
        val configFile = File(modsDir, "mods_config.json")
        val activeIds = mutableSetOf<String>()
        var configExists = false

        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                modsMasterEnabled = json.optBoolean("modsEnabled", true)
                configExists = true
                val arr = json.optJSONArray("activeMods")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        activeIds.add(arr.getString(i))
                    }
                }
            } catch (_: Exception) {}
        }

        binding.cbMasterEnable.isChecked = modsMasterEnabled

        // Scan directories
        val subdirs = modsDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (dir in subdirs) {
            val modIni = findModIni(dir) ?: continue
            val info = parseModIni(dir.name, modIni)
            info.isEnabled = if (configExists) activeIds.contains(info.id) else true
            installedMods.add(info)
        }

        if (installedMods.isEmpty()) {
            binding.scrollMods.visibility = View.GONE
            binding.tvEmptyMods.visibility = View.VISIBLE
        } else {
            binding.scrollMods.visibility = View.VISIBLE
            binding.tvEmptyMods.visibility = View.GONE

            for (mod in installedMods) {
                val cardView = layoutInflater.inflate(R.layout.item_mod_card, binding.modsListContainer, false)
                val cb = cardView.findViewById<CheckBox>(R.id.cb_mod_enabled)
                val tvName = cardView.findViewById<TextView>(R.id.tv_mod_name)
                val tvMeta = cardView.findViewById<TextView>(R.id.tv_mod_meta)
                val tvDesc = cardView.findViewById<TextView>(R.id.tv_mod_desc)
                val badgeBlink = cardView.findViewById<TextView>(R.id.tv_badge_noblink)
                val badgeDir = cardView.findViewById<TextView>(R.id.tv_badge_dir)

                tvName.text = mod.name
                tvMeta.text = "v${mod.version} • by ${mod.author}"
                tvDesc.text = if (mod.description.isNotBlank()) mod.description else "No description provided."
                badgeBlink.visibility = if (mod.disableBlink) View.VISIBLE else View.GONE
                badgeDir.text = "DIR: ${if (mod.includeDir.isNotBlank()) mod.includeDir else "."}"

                cb.isChecked = mod.isEnabled
                cb.setOnCheckedChangeListener { _, isChecked ->
                    mod.isEnabled = isChecked
                    saveConfig()
                }

                binding.modsListContainer.addView(cardView)
            }
        }

        // If config didn't exist initially, save defaults
        if (!configExists && installedMods.isNotEmpty()) {
            saveConfig()
        }
    }

    private fun findModIni(dir: File): File? {
        val exact = File(dir, "mod.ini")
        if (exact.exists()) return exact
        val upper = File(dir, "MOD.INI")
        if (upper.exists()) return upper
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.name.equals("mod.ini", ignoreCase = true)) {
                return f
            }
        }
        return null
    }

    private fun parseModIni(folderName: String, file: File): ModInfo {
        var name = folderName
        var author = "Community"
        var version = "1.0"
        var description = ""
        var includeDir = ""
        var disableBlink = false

        try {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";") && !trimmed.startsWith("[")) {
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        when {
                            key.equals("Name", ignoreCase = true) -> name = value
                            key.equals("Author", ignoreCase = true) -> author = value
                            key.equals("Version", ignoreCase = true) -> version = value
                            key.equals("Description", ignoreCase = true) -> description = value
                            key.equals("IncludeDir0", ignoreCase = true) -> includeDir = value
                            key.equals("DisableBlink", ignoreCase = true) -> {
                                disableBlink = value == "1" || value.equals("true", ignoreCase = true)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (includeDir.isBlank()) {
            val filesSubdir = File(file.parentFile, "files")
            if (filesSubdir.exists() && filesSubdir.isDirectory) {
                includeDir = "files"
            }
        }

        return ModInfo(
            id = folderName,
            name = name,
            author = author,
            version = version,
            description = description,
            includeDir = includeDir,
            disableBlink = disableBlink,
            isEnabled = true
        )
    }

    private fun saveConfig() {
        try {
            val modsDir = File(filesDir, "mods")
            if (!modsDir.exists()) modsDir.mkdirs()
            val configFile = File(modsDir, "mods_config.json")

            val json = JSONObject()
            json.put("modsEnabled", modsMasterEnabled)

            val activeArr = JSONArray()
            for (mod in installedMods) {
                if (mod.isEnabled) {
                    activeArr.put(mod.id)
                }
            }
            json.put("activeMods", activeArr)

            configFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save mods config: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
