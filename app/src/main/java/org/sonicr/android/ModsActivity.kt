package org.sonicr.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    data class DisplayConfig(
        var aspectRatio: String = "auto",      // "auto", "16:9", "4:3"
        var renderScale: String = "native",    // "native", "720p", "480p"
        var hudSafeZone43: Boolean = true,
        var fovMultiplier: Float = 1.0f
    )

    private lateinit var binding: ActivityModsBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private val installedMods = mutableListOf<ModInfo>()
    private var modsMasterEnabled = true
    private var currentDisplayConfig = DisplayConfig()
    private var isUpdatingDisplayUI = false

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

        setupTabs()
        setupDisplaySettings()
        loadMods()
    }

    private fun setupTabs() {
        binding.tabMods.setOnClickListener {
            switchTab(showMods = true)
        }
        binding.tabDisplay.setOnClickListener {
            switchTab(showMods = false)
        }
    }

    private fun switchTab(showMods: Boolean) {
        if (showMods) {
            binding.tabMods.setBackgroundResource(R.drawable.btn_retro_play)
            binding.tabMods.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tabDisplay.setBackgroundResource(R.drawable.btn_retro_sega)
            binding.tabDisplay.setTextColor(Color.parseColor("#E0F2FE"))
            binding.viewModsContainer.visibility = View.VISIBLE
            binding.viewDisplayContainer.visibility = View.GONE
        } else {
            binding.tabMods.setBackgroundResource(R.drawable.btn_retro_sega)
            binding.tabMods.setTextColor(Color.parseColor("#E0F2FE"))
            binding.tabDisplay.setBackgroundResource(R.drawable.btn_retro_play)
            binding.tabDisplay.setTextColor(Color.parseColor("#FFFFFF"))
            binding.viewModsContainer.visibility = View.GONE
            binding.viewDisplayContainer.visibility = View.VISIBLE
        }
    }

    private fun setupDisplaySettings() {
        // 1. Aspect Ratio Spinner
        val aspectEntries = arrayOf(
            "Auto (Fullscreen / Hor+)",
            "16:9 (Standard Widescreen)",
            "4:3 (Classic Retro Pillarbox)"
        )
        val aspectValues = arrayOf("auto", "16:9", "4:3")
        val aspectAdapter = ArrayAdapter(this, R.layout.item_spinner, aspectEntries)
        aspectAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spinnerAspectRatio.adapter = aspectAdapter

        binding.spinnerAspectRatio.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingDisplayUI) {
                    currentDisplayConfig.aspectRatio = aspectValues[position]
                    saveDisplayConfig()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. Render Scale Spinner
        val scaleEntries = arrayOf(
            "Native (Full Resolution)",
            "720p (HD Balance)",
            "480p (Retro Saturn)"
        )
        val scaleValues = arrayOf("native", "720p", "480p")
        val scaleAdapter = ArrayAdapter(this, R.layout.item_spinner, scaleEntries)
        scaleAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spinnerRenderScale.adapter = scaleAdapter

        binding.spinnerRenderScale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingDisplayUI) {
                    currentDisplayConfig.renderScale = scaleValues[position]
                    saveDisplayConfig()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. HUD Safe Zone
        binding.cbHudSafeZone.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingDisplayUI) {
                currentDisplayConfig.hudSafeZone43 = isChecked
                saveDisplayConfig()
            }
        }

        // 4. FOV Multiplier Spinner
        val fovEntries = arrayOf(
            "1.0x (Original FOV)",
            "1.1x (+10% Wider)",
            "1.2x (+20% Wider)",
            "1.3x (+30% Wider)",
            "1.4x (+40% Wider)",
            "1.5x (+50% Wider)"
        )
        val fovValues = floatArrayOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
        val fovAdapter = ArrayAdapter(this, R.layout.item_spinner, fovEntries)
        fovAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spinnerFov.adapter = fovAdapter

        binding.spinnerFov.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingDisplayUI) {
                    currentDisplayConfig.fovMultiplier = fovValues[position]
                    saveDisplayConfig()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 5. Reset button
        binding.btnResetDisplay.setOnClickListener {
            currentDisplayConfig = DisplayConfig()
            saveDisplayConfig()
            updateDisplayUIFromConfig()
            Toast.makeText(this, "Display settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        loadDisplayConfig()
    }

    private fun loadDisplayConfig() {
        val configFile = File(filesDir, "video_config.json")
        currentDisplayConfig = DisplayConfig()
        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                currentDisplayConfig.aspectRatio = json.optString("aspectRatio", "auto")
                currentDisplayConfig.renderScale = json.optString("renderScale", "native")
                currentDisplayConfig.hudSafeZone43 = json.optBoolean("hudSafeZone43", true)
                currentDisplayConfig.fovMultiplier = json.optDouble("fovMultiplier", 1.0).toFloat()
            } catch (e: Exception) {
                android.util.Log.e("ModsActivity", "Failed to parse video_config.json", e)
            }
        }
        updateDisplayUIFromConfig()
    }

    private fun updateDisplayUIFromConfig() {
        isUpdatingDisplayUI = true

        val aspectValues = arrayOf("auto", "16:9", "4:3")
        val aspectIdx = aspectValues.indexOf(currentDisplayConfig.aspectRatio).let { if (it >= 0) it else 0 }
        binding.spinnerAspectRatio.setSelection(aspectIdx)

        val scaleValues = arrayOf("native", "720p", "480p")
        val scaleIdx = scaleValues.indexOf(currentDisplayConfig.renderScale).let { if (it >= 0) it else 0 }
        binding.spinnerRenderScale.setSelection(scaleIdx)

        binding.cbHudSafeZone.isChecked = currentDisplayConfig.hudSafeZone43

        val fovValues = floatArrayOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
        var fovIdx = 0
        for (i in fovValues.indices) {
            if (kotlin.math.abs(fovValues[i] - currentDisplayConfig.fovMultiplier) < 0.05f) {
                fovIdx = i
                break
            }
        }
        binding.spinnerFov.setSelection(fovIdx)

        isUpdatingDisplayUI = false
    }

    private fun saveDisplayConfig() {
        try {
            val json = JSONObject()
            json.put("aspectRatio", currentDisplayConfig.aspectRatio)
            json.put("renderScale", currentDisplayConfig.renderScale)
            json.put("hudSafeZone43", currentDisplayConfig.hudSafeZone43)
            json.put("fovMultiplier", currentDisplayConfig.fovMultiplier.toDouble())

            val configFile = File(filesDir, "video_config.json")
            configFile.writeText(json.toString(2))
            android.util.Log.i("ModsActivity", "Saved video_config.json: $json")
        } catch (e: Exception) {
            android.util.Log.e("ModsActivity", "Failed to write video_config.json", e)
        }
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
