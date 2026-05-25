package com.kodrix.zohaib.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

class BinaryManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("binary_manager", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir
    private val versionsDir = File(filesDir, "versions")

    data class RemoteVersion(
        val tool: String,
        val version: String,
        val tag: String,
        val downloadUrl: String,
        val isInstalled: Boolean = false,
        val isActive: Boolean = false
    )

    private val _availableVersions = MutableStateFlow<List<RemoteVersion>>(emptyList())
    val availableVersions = _availableVersions.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    init {
        versionsDir.mkdirs()
        val activeNode = getActiveVersion("node")
        syncActiveVersionToFile(activeNode)
    }

    suspend fun syncVersions() {
        _isSyncing.value = true
        try {
            // In a real app, we fetch from a URL. For now, we simulate or use a hardcoded fallback.
            val registryUrl = "https://raw.githubusercontent.com/Zohaib8090/KodrixIDE/main/versions.json"
            val response = try {
                URL(registryUrl).readText()
            } catch (e: Exception) {
                // Fallback registry for testing
                """
                {
                  "node": [
                    {
                      "version": "22.0.0",
                      "tag": "v22 (Current)",
                      "arm64-v8a": "https://github.com/Zohaib8090/KodrixIDE/releases/download/v1.1.1/node-v22-arm64.zip"
                    },
                    {
                      "version": "20.12.2",
                      "tag": "v20 (LTS)",
                      "arm64-v8a": "https://github.com/Zohaib8090/KodrixIDE/releases/download/v1.1.1/node-v20-arm64.zip"
                    },
                    {
                      "version": "18.16.0",
                      "tag": "v18 (Legacy)",
                      "arm64-v8a": "https://github.com/Zohaib8090/KodrixIDE/releases/download/v1.1.1/node-v18-arm64.zip"
                    }
                  ]
                }
                """.trimIndent()
            }

            val root = JSONObject(response)
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            
            val list = mutableListOf<RemoteVersion>()
            val activeNode = getActiveVersion("node")

            // Always add default bundled version first
            list.add(
                RemoteVersion(
                    tool = "node",
                    version = "25.8.2",
                    tag = "v25 (Bundled)",
                    downloadUrl = "",
                    isInstalled = true,
                    isActive = (activeNode == null || activeNode == "25.8.2")
                )
            )

            val nodeArray = root.optJSONArray("node") ?: JSONArray()
            
            for (i in 0 until nodeArray.length()) {
                val obj = nodeArray.getJSONObject(i)
                val ver = obj.getString("version")
                if (ver == "25.8.2") continue // skip duplicate of bundled
                val tag = obj.getString("tag")
                val url = obj.optString(abi, "")
                
                if (url.isNotEmpty()) {
                    val isInstalled = File(versionsDir, "node/$ver/bin/node").exists()
                    val isActive = activeNode == ver
                    
                    list.add(RemoteVersion("node", ver, tag, url, isInstalled, isActive))
                }
            }
            _availableVersions.value = list
        } catch (e: Exception) {
            Log.e("BinaryManager", "Failed to sync versions", e)
        } finally {
            _isSyncing.value = false
        }
    }

    fun getActiveVersion(tool: String): String? {
        val active = prefs.getString("active_$tool", null)
        return if (active == "25.8.2") null else active
    }

    fun setActiveVersion(tool: String, version: String) {
        if (version == "25.8.2") {
            prefs.edit().remove("active_$tool").apply()
            if (tool == "node") {
                syncActiveVersionToFile(null)
            }
        } else {
            prefs.edit().putString("active_$tool", version).apply()
            if (tool == "node") {
                syncActiveVersionToFile(version)
            }
        }
        // Trigger UI refresh
        val updated = _availableVersions.value.map {
            if (it.tool == tool) {
                if (version == "25.8.2") {
                    it.copy(isActive = it.version == "25.8.2")
                } else {
                    it.copy(isActive = it.version == version)
                }
            } else it
        }
        _availableVersions.value = updated
    }

    private fun syncActiveVersionToFile(version: String?) {
        try {
            val file = File(context.filesDir, "active_node_version")
            if (version != null && version != "25.8.2") {
                file.writeText(version)
                Log.d("BinaryManager", "Synced active node version to file: $version")
            } else {
                if (file.exists()) {
                    file.delete()
                    Log.d("BinaryManager", "Deleted active node version file")
                }
            }
        } catch (e: Exception) {
            Log.e("BinaryManager", "Failed to sync active version to file", e)
        }
    }

    fun getBinaryPath(tool: String): String? {
        val active = getActiveVersion(tool) ?: return null
        val binFile = File(versionsDir, "$tool/$active/bin/$tool")
        return if (binFile.exists()) binFile.absolutePath else null
    }

    fun getLibPath(tool: String): String? {
        val active = getActiveVersion(tool) ?: return null
        val libFile = File(versionsDir, "$tool/$active/lib")
        return if (libFile.exists()) libFile.absolutePath else null
    }

    suspend fun downloadVersion(tool: String, version: String, url: String) {
        val toolDir = File(versionsDir, "$tool/$version")
        toolDir.mkdirs()
        
        val zipFile = File(toolDir, "bundle.zip")
        
        try {
            _downloadProgress.value = _downloadProgress.value + (version to 0f)
            
            val conn = URL(url).openConnection()
            val totalSize = conn.contentLength.toLong()
            var downloaded = 0L
            
            conn.getInputStream().use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = input.read(buffer)
                    while (bytes != -1) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        _downloadProgress.value = _downloadProgress.value + (version to (downloaded.toFloat() / totalSize))
                        bytes = input.read(buffer)
                    }
                }
            }
            
            // Extract
            ZipUtils.unzip(zipFile, toolDir)
            zipFile.delete()
            
            // Set permissions
            File(toolDir, "bin/$tool").setExecutable(true)
            val npmBin = File(toolDir, "bin/npm")
            if (npmBin.exists()) npmBin.setExecutable(true)
            val npxBin = File(toolDir, "bin/npx")
            if (npxBin.exists()) npxBin.setExecutable(true)
            
            // Update state
            val updated = _availableVersions.value.map {
                if (it.tool == tool && it.version == version) it.copy(isInstalled = true) else it
            }
            _availableVersions.value = updated
            
            if (getActiveVersion(tool) == null) {
                setActiveVersion(tool, version)
            }
            
        } catch (e: Exception) {
            Log.e("BinaryManager", "Failed to download $tool $version", e)
        } finally {
            _downloadProgress.value = _downloadProgress.value - version
        }
    }
}
