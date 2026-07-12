package com.kodrix.zohaib.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object BinaryUpdater {
    private const val TAG = "BinaryUpdater"
    
    data class BinaryUpdateInfo(
        val nodeVersion: String,
        val nodeUrl: String,
        val gitVersion: String,
        val gitUrl: String,
        val releaseNotes: String
    )

    suspend fun checkUpdates(registryUrl: String): BinaryUpdateInfo? = withContext(Dispatchers.IO) {
        if (!registryUrl.startsWith("https://github.com/") && !registryUrl.startsWith("https://raw.githubusercontent.com/")) {
            Log.e(TAG, "Blocked update check from untrusted domain: $registryUrl")
            return@withContext null
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(registryUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Update check failed: HTTP ${connection.responseCode}")
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            BinaryUpdateInfo(
                nodeVersion = json.getString("node_version"),
                nodeUrl = json.getString("node_url"),
                gitVersion = json.getString("git_version"),
                gitUrl = json.getString("git_url"),
                releaseNotes = json.optString("notes", "Minor binary updates")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        urlStr: String,
        targetDirName: String, // e.g. "binaries"
        onProgress: (Float, Long, Long) -> Unit // progress, downloaded, total
    ): Boolean = withContext(Dispatchers.IO) {
        if (!urlStr.startsWith("https://github.com/") && !urlStr.startsWith("https://raw.githubusercontent.com/")) {
            Log.e(TAG, "Blocked download from untrusted domain: $urlStr")
            return@withContext false
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode}")
                return@withContext false
            }
            
            val fileLength = connection.contentLength.toLong()
            val input = BufferedInputStream(connection.inputStream)
            val tempZip = File(context.cacheDir, "bin_update_${System.currentTimeMillis()}.zip")
            val output = FileOutputStream(tempZip)
            
            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                onProgress(
                    if (fileLength > 0) total.toFloat() / fileLength else 0f,
                    total,
                    fileLength
                )
                output.write(data, 0, count)
            }
            output.flush()
            output.close()
            input.close()
            
            // Extract to target
            val targetDir = File(context.filesDir, targetDirName)
            targetDir.mkdirs()
            ZipUtils.unzip(tempZip, targetDir)
            tempZip.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/Install failed: ${e.message}", e)
            false
        } finally {
            connection?.disconnect()
        }
    }
}
