package com.kodrix.zohaib.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Extension(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val iconUrl: String?,
    val downloadUrl: String,
    val isInstalled: Boolean = false,
    val versions: List<String> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val type: String = "extension", // "extension" or "npm"
    val packageName: String? = null, // For npm type
    val repo: String = "Zohaib8090/KodrixMarketplace"
)

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    private const val MARKETPLACE_PATH = "marketplace"

    // Scan both the new repository and the old repository to prevent breaking existing content
    private val REPOSITORIES = listOf(
        "Zohaib8090/KodrixMarketplace",
        "Zohaib8090/KodrixIDE"
    )

    suspend fun scanMarketplace(context: Context, token: String? = null, activeProject: String? = null): List<Extension> = withContext(Dispatchers.IO) {
        val allExtensions = mutableListOf<Extension>()
        for (repo in REPOSITORIES) {
            val repoExtensions = scanSingleRepository(context, repo, token, activeProject)
            allExtensions.addAll(repoExtensions)
        }
        // De-duplicate by extension id, prioritizing the newer repo (KodrixMarketplace)
        allExtensions.distinctBy { it.id }
    }

    private suspend fun scanSingleRepository(context: Context, repo: String, token: String?, activeProject: String?): List<Extension> {
        val extensions = mutableListOf<Extension>()
        try {
            val url = URL("https://api.github.com/repos/$repo/contents/$MARKETPLACE_PATH")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Kodrix-Android-App")
            if (token != null) {
                connection.setRequestProperty("Authorization", "token $token")
            }
            Log.d(TAG, "Scanning Marketplace repo $repo: $url")
            if (connection.responseCode != 200) {
                Log.e(TAG, "Failed to scan repo $repo: ${connection.responseCode}")
                return emptyList()
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val items = JSONArray(response)
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.getString("type") == "dir") {
                    val name = item.getString("name")
                    val metadata = fetchMetadata(repo, name)
                    if (metadata != null) {
                        val id = metadata.getString("id")
                        val type = metadata.optString("type", "extension")
                        val packageName = metadata.optString("packageName", null)
                        
                        val isInstalled = if (type == "npm" && packageName != null) {
                            // Check global lsp dir
                            val lspDir = File(context.filesDir, "lsp")
                            val globalLsp = File(lspDir, "node_modules/$packageName")
                            val binaryName = metadata.optString("lspBinary", null)
                            val binaryFile = if (binaryName != null) File(lspDir, "node_modules/.bin/$binaryName") else null
                            
                            if (globalLsp.exists() && (binaryFile == null || binaryFile.exists())) {
                                true
                            } else if (activeProject != null) {
                                // Fall back to project-local
                                val projDir = File(File(context.filesDir, "projects"), activeProject)
                                File(projDir, "node_modules/$packageName").exists()
                            } else {
                                false
                            }
                        } else {
                            File(context.filesDir, "extensions/$id").exists()
                        }

                        val versionsList = fetchVersions(repo, name)
                        val screenshotsArray = metadata.optJSONArray("screenshots")
                        val screenshots = mutableListOf<String>()
                        if (screenshotsArray != null) {
                            for (j in 0 until screenshotsArray.length()) {
                                screenshots.add(screenshotsArray.getString(j))
                            }
                        }

                        extensions.add(Extension(
                            id = id,
                            name = metadata.getString("name"),
                            description = metadata.optString("description", "No description"),
                            version = metadata.optString("version", "1.0.0"),
                            author = metadata.optString("author", "Unknown"),
                            iconUrl = metadata.optString("icon", null),
                            downloadUrl = "https://github.com/$repo/archive/refs/heads/main.zip",
                            isInstalled = isInstalled,
                            versions = versionsList,
                            screenshots = screenshots,
                            type = type,
                            packageName = packageName,
                            repo = repo
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Marketplace scan failed for repo $repo", e)
        }
        return extensions
    }

    private suspend fun fetchMetadata(repo: String, dirName: String): JSONObject? {
        return try {
            val url = URL("https://raw.githubusercontent.com/$repo/main/$MARKETPLACE_PATH/$dirName/manifest.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Kodrix-Android-App")
            if (connection.responseCode != 200) {
                Log.e(TAG, "Metadata fetch failed for $dirName in $repo: ${connection.responseCode}")
                return null
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "Metadata error for $dirName in $repo", e)
            null
        }
    }

    suspend fun installExtension(context: Context, extension: Extension, version: String? = null, onProgress: (Float) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val downloadUrl = if (version != null && version != "Latest") {
                    "https://github.com/${extension.repo}/archive/refs/tags/$version.zip"
                } else {
                    extension.downloadUrl
                }

                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Kodrix-Android-App")
                connection.connect()
                
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
                
                val fileLength = connection.contentLength.toLong()
                val tempZip = File(context.cacheDir, "ext_${extension.id}.zip")
                val input = java.io.BufferedInputStream(connection.inputStream)
                val output = java.io.FileOutputStream(tempZip)
                
                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) onProgress(total.toFloat() / fileLength)
                    output.write(data, 0, count)
                }
                output.close()
                input.close()
                
                val targetDir = File(context.filesDir, "extensions/${extension.id}")
                targetDir.mkdirs()
                
                unzipExtension(tempZip, targetDir, "marketplace/${extension.id}")
                
                tempZip.delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                false
            }
        }
    }

    private suspend fun fetchVersions(repo: String, dirName: String): List<String> {
        return try {
            val url = URL("https://raw.githubusercontent.com/$repo/main/$MARKETPLACE_PATH/$dirName/versions.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Kodrix-Android-App")
            if (connection.responseCode != 200) return listOf("Latest")
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val array = json.getJSONArray("versions")
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            listOf("Latest")
        }
    }

    private fun unzipExtension(zipFile: File, targetDir: File, internalPath: String) {
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val pathAfterRoot = name.substringAfter('/')
                
                if (pathAfterRoot.startsWith(internalPath)) {
                    val relativeName = pathAfterRoot.removePrefix(internalPath).removePrefix("/")
                    if (relativeName.isNotEmpty()) {
                        val newFile = File(targetDir, relativeName)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            newFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
