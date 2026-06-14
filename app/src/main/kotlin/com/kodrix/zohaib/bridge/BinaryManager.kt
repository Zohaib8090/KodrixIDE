package com.kodrix.zohaib.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BinaryManager(private val context: Context) {

    data class InstallState(
        val version: String,
        val stage: String,
        val progress: Float = 0f,
        val error: String? = null
    )

    /** UI metadata fetched from the registry root for each tool. */
    data class ToolMeta(
        val id: String,
        val displayName: String,
        val category: String,
        val iconUrl: String
    )

    data class RemoteVersion(
        val tool: String,
        val version: String,
        val tag: String,
        val downloadUrl: String,
        val sha256: String = "",
        val isInstalled: Boolean = false,
        val isActive: Boolean = false,
        val isUnavailable: Boolean = false,
        val note: String = "",
        /** Parsed wrapper specs from registry rules.wrappers */
        val wrappers: List<WrapperManager.WrapperSpec> = emptyList(),
        /** Env vars from registry rules.env */
        val env: Map<String, String> = emptyMap()
    )

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates = _installStates.asStateFlow()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = try {
        EncryptedSharedPreferences.create(
            context,
            "binary_manager_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for extreme cases where Keystore is corrupted, though this is rare.
        // It's better to clear and recreate than to crash.
        context.getSharedPreferences("binary_manager_secure", Context.MODE_PRIVATE).edit().clear().apply()
        EncryptedSharedPreferences.create(
            context,
            "binary_manager_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private val filesDir = context.filesDir
    private val versionsDir = File(filesDir, "versions")

    companion object {
        private const val DOWNLOAD_IN_PROGRESS_MARKER = "download.tmp"
        private const val REGISTRY_URL =
            "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/versions.json"
        private const val TAG = "BinaryManager"

        // Bundled default versions — treated as "active" when no user selection exists
        private val BUNDLED_DEFAULTS = mapOf(
            "node" to Triple("25.8.2", "v25 (Bundled)", "libnode_bin.so"),
            "git"  to Triple("2.34.0", "v2.34.0 (Bundled)", "libgit_bin.so")
        )
    }

    private val _availableVersions = MutableStateFlow<List<RemoteVersion>>(emptyList())
    val availableVersions = _availableVersions.asStateFlow()

    private val _toolMetas = MutableStateFlow<List<ToolMeta>>(emptyList())
    val toolMetas = _toolMetas.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    val verifiedVersions = VersionChecker.verifiedVersions

    init {
        versionsDir.mkdirs()
        cleanUpStaleDownloads()
        // Populate the safe-mode fallback directory (bundled-only wrappers, never modified again)
        WrapperManager.writeSafeModeWrappers(context)
        // Recreate dynamic wrappers using current active versions on startup
        rebuildWrappers()
    }

    // ── Startup helpers ───────────────────────────────────────────────────────

    private fun cleanUpStaleDownloads() {
        try {
            versionsDir.listFiles()?.forEach { toolDir ->
                if (!toolDir.isDirectory) return@forEach
                toolDir.listFiles()?.forEach { versionDir ->
                    val marker = File(versionDir, DOWNLOAD_IN_PROGRESS_MARKER)
                    if (marker.exists()) {
                        Log.w(TAG, "Stale download: ${toolDir.name}/${versionDir.name} — cleaning up")
                        versionDir.deleteRecursively()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stale-download cleanup failed", e)
        }
    }

    /** Rebuilds WrapperManager configs from whatever is currently active on disk. */
    fun rebuildWrappers() {
        val nativeLibPath = context.applicationInfo.nativeLibraryDir
        val libLinksDir   = File(filesDir, "lib").absolutePath
        val configs = mutableListOf<WrapperManager.ToolWrapperConfig>()

        BUNDLED_DEFAULTS.forEach { (toolName, bundledTriple) ->
            val (bundledVer, _, fallbackSo) = bundledTriple
            val activeVer  = getActiveVersion(toolName) ?: bundledVer
            val installDir = File(versionsDir, "$toolName/$activeVer")

            // Build wrapper specs: first check if registry-parsed specs exist (from last sync),
            // otherwise use safe hardcoded defaults for the bundled runtime.
            val wrappers = buildDefaultWrappers(toolName)
            val env      = buildDefaultEnv(toolName, installDir)

            configs.add(WrapperManager.ToolWrapperConfig(
                toolName      = toolName,
                installDir    = installDir,
                fallbackSoName = fallbackSo,
                nativeLibPath = nativeLibPath,
                libLinksDir   = libLinksDir,
                env           = env,
                wrappers      = wrappers
            ))
        }

        // Also scan for any additional tools installed via marketplace that aren't in BUNDLED_DEFAULTS
        versionsDir.listFiles()?.forEach { toolDir ->
            if (!toolDir.isDirectory) return@forEach
            val toolName = toolDir.name
            if (toolName in BUNDLED_DEFAULTS) return@forEach
            val activeVer  = getActiveVersion(toolName) ?: return@forEach
            val installDir = File(versionsDir, "$toolName/$activeVer")
            configs.add(WrapperManager.ToolWrapperConfig(
                toolName       = toolName,
                installDir     = installDir,
                fallbackSoName = "lib${toolName}_bin.so",
                nativeLibPath  = nativeLibPath,
                libLinksDir    = libLinksDir,
                env            = buildDefaultEnv(toolName, installDir),
                wrappers       = buildDefaultWrappers(toolName)
            ))
        }

        WrapperManager.recreateWrappers(context, configs)
    }

    private fun buildDefaultWrappers(tool: String): List<WrapperManager.WrapperSpec> = when (tool) {
        "node" -> listOf(
            WrapperManager.WrapperSpec("node", "symlink", "bin/node"),
            WrapperManager.WrapperSpec("npm",  "script",  "lib/node_modules/npm/bin/npm-cli.js", "node"),
            WrapperManager.WrapperSpec("npx",  "script",  "lib/node_modules/npm/bin/npx-cli.js", "node")
        )
        "git"  -> listOf(
            WrapperManager.WrapperSpec("git",              "symlink", "bin/git"),
            WrapperManager.WrapperSpec("git-remote-http",  "symlink", "libexec/git-core/git-remote-http"),
            WrapperManager.WrapperSpec("git-remote-https", "symlink", "libexec/git-core/git-remote-http")
        )
        "python" -> listOf(
            WrapperManager.WrapperSpec("python",  "symlink", "bin/python"),
            WrapperManager.WrapperSpec("python3", "symlink", "bin/python3"),
            WrapperManager.WrapperSpec("pip",     "script",  "bin/pip", "python"),
            WrapperManager.WrapperSpec("pip3",    "script",  "bin/pip3", "python")
        )
        else   -> listOf(WrapperManager.WrapperSpec(tool, "symlink", "bin/$tool"))
    }

    private fun buildDefaultEnv(tool: String, installDir: File): Map<String, String> = when (tool) {
        "node" -> mapOf("NODE_PATH" to "${installDir.absolutePath}/lib/node_modules")
        "python" -> mapOf("PYTHONHOME" to installDir.absolutePath)
        else   -> emptyMap()
    }

    // ── Registry sync ─────────────────────────────────────────────────────────

    suspend fun syncVersions() {
        _isSyncing.value = true
        try {
            val json = withContext(Dispatchers.IO) {
                try { fetchWithRedirects(REGISTRY_URL) } catch (e: Exception) {
                    Log.w(TAG, "Registry fetch failed — using fallback: ${e.message}")
                    FALLBACK_REGISTRY
                }
            }

            val root = JSONObject(json)
            val abi  = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val list = mutableListOf<RemoteVersion>()
            val metas = mutableListOf<ToolMeta>()

            val keys = root.keys()
            while (keys.hasNext()) {
                val toolName  = keys.next()
                val toolObj   = root.optJSONObject(toolName) ?: continue
                val activeVer = getActiveVersion(toolName)
                val bundled   = BUNDLED_DEFAULTS[toolName]

                // Registry-driven UI metadata
                metas.add(ToolMeta(
                    id          = toolName,
                    displayName = toolObj.optString("displayName", toolName),
                    category    = toolObj.optString("category", "Runtime"),
                    iconUrl     = toolObj.optString("iconUrl", "")
                ))

                // Always prepend the bundled version first
                bundled?.let { (bundledVer, bundledTag) ->
                    list.add(RemoteVersion(
                        tool        = toolName,
                        version     = bundledVer,
                        tag         = bundledTag,
                        downloadUrl = "",
                        isInstalled = true,
                        isActive    = (activeVer == null || activeVer == bundledVer)
                    ))
                }

                val versionsArray = toolObj.optJSONArray("versions") ?: JSONArray()
                for (i in 0 until versionsArray.length()) {
                    val obj    = versionsArray.getJSONObject(i)
                    val ver    = obj.getString("version")
                    val tag    = obj.getString("tag")
                    val status = obj.optString("status", "available")
                    val note   = obj.optString("note", "")

                    // Skip if this is the bundled version already added above
                    if (bundled != null && ver == bundled.first) continue

                    if (status == "unavailable") {
                        list.add(RemoteVersion(tool = toolName, version = ver, tag = tag,
                            downloadUrl = "", isUnavailable = true, note = note))
                        continue
                    }

                    // Resolve download URL: prefer ABI-specific, fall back to universal
                    val url = obj.optString(abi, "").ifEmpty { obj.optString("universal", "") }
                    if (url.isEmpty()) continue

                    val sha256     = obj.optString("sha256", "")
                    val rulesObj   = obj.optJSONObject("rules")
                    val wrappers   = parseWrappers(rulesObj?.optJSONObject("wrappers"))
                    val env        = parseEnvBlock(rulesObj?.optJSONObject("env"))
                    val versionDir = File(versionsDir, "$toolName/$ver")
                    val marker     = File(versionDir, DOWNLOAD_IN_PROGRESS_MARKER)
                    val binName    = if (toolName == "node") "node" else toolName
                    val isInstalled = File(versionDir, "bin/$binName").exists() && !marker.exists()
                    val isActive   = activeVer == ver

                    list.add(RemoteVersion(toolName, ver, tag, url, sha256, isInstalled, isActive,
                        wrappers = wrappers, env = env))
                }
            }

            _availableVersions.value = list
            _toolMetas.value = metas
        } catch (e: Exception) {
            Log.e(TAG, "syncVersions failed", e)
        } finally {
            _isSyncing.value = false
        }
    }

    // ── Registry parsing helpers ───────────────────────────────────────────────

    private fun parseWrappers(obj: JSONObject?): List<WrapperManager.WrapperSpec> {
        obj ?: return emptyList()
        val result = mutableListOf<WrapperManager.WrapperSpec>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name   = keys.next()
            val entry  = obj.getJSONObject(name)
            result.add(WrapperManager.WrapperSpec(
                name        = name,
                type        = entry.optString("type", "symlink"),
                path        = entry.optString("path", "bin/$name"),
                interpreter = entry.optString("interpreter", "")
            ))
        }
        return result
    }

    private fun parseEnvBlock(obj: JSONObject?): Map<String, String> {
        obj ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = obj.getString(k)
        }
        return result
    }

    // ── Active version management ─────────────────────────────────────────────

    fun getActiveVersion(tool: String): String? {
        val stored = prefs.getString("active_$tool", null) ?: return null
        val bundledVer = BUNDLED_DEFAULTS[tool]?.first
        return if (stored == bundledVer) null else stored
    }

    suspend fun setActiveVersion(tool: String, version: String): Boolean {
        val bundledVer   = BUNDLED_DEFAULTS[tool]?.first ?: "unknown"
        val previousActive = getActiveVersion(tool) ?: bundledVer
        if (previousActive == version) return true

        // Persist
        if (version == bundledVer) {
            prefs.edit().remove("active_$tool").apply()
            syncActiveVersionToFile(tool, null)
        } else {
            prefs.edit().putString("active_$tool", version).apply()
            syncActiveVersionToFile(tool, version)
        }
        updateActiveUI(tool, version)

        // Atomically regenerate wrappers for the new active version
        rebuildWrappers()

        // Verify
        VersionChecker.clearVerified(tool)
        val binaryPath = getBinaryPath(tool) ?: "${filesDir}/usr/bin/$tool"
        val result = VersionChecker.check(tool, expectedVersion = version, binaryPath = binaryPath, context = context)

        if (result.isVerified) {
            Log.i(TAG, "[$tool] Switched to $version — verified")
            return true
        }

        // Revert on failure
        Log.e(TAG, "[$tool] $version failed verification — reverting to $previousActive")
        if (previousActive == bundledVer) {
            prefs.edit().remove("active_$tool").apply()
            syncActiveVersionToFile(tool, null)
        } else {
            prefs.edit().putString("active_$tool", previousActive).apply()
            syncActiveVersionToFile(tool, previousActive)
        }
        updateActiveUI(tool, previousActive)
        rebuildWrappers()

        val prevPath   = getBinaryPath(tool) ?: "${filesDir}/usr/bin/$tool"
        val prevResult = VersionChecker.check(tool, expectedVersion = previousActive, binaryPath = prevPath, context = context)
        if (!prevResult.isVerified) {
            throw RuntimeException("Both $version and backup $previousActive failed to run. Your environment may be corrupted.")
        }
        throw RuntimeException("Failed to run $tool $version — reverted to $previousActive. Binary may be incompatible with your device architecture.")
    }

    private fun updateActiveUI(tool: String, activeVersion: String) {
        val bundledVer = BUNDLED_DEFAULTS[tool]?.first
        _availableVersions.value = _availableVersions.value.map {
            if (it.tool == tool) {
                it.copy(isActive = if (activeVersion == bundledVer) it.version == bundledVer
                                   else it.version == activeVersion)
            } else it
        }
    }

    private fun syncActiveVersionToFile(tool: String, version: String?) {
        try {
            val file = File(filesDir, "active_${tool}_version")
            if (version != null) {
                file.writeText(version)
                Log.d(TAG, "[$tool] active version file → $version")
            } else {
                file.takeIf { it.exists() }?.delete()
                Log.d(TAG, "[$tool] active version file removed (using bundled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync active version file for $tool", e)
        }
    }

    // ── Binary path helpers ───────────────────────────────────────────────────

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

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads, SHA-256 verifies, extracts, and activates a binary version.
     *
     * Uses redirect-following HTTP so GitHub Release assets (which redirect to S3)
     * are handled correctly. Aborts immediately if checksum doesn't match.
     */
    suspend fun downloadVersion(tool: String, version: String, url: String, sha256: String = "") {
        val toolDir = File(versionsDir, "$tool/$version")
        toolDir.mkdirs()
        val marker  = File(toolDir, DOWNLOAD_IN_PROGRESS_MARKER)
        val zipFile = File(toolDir, "bundle.zip")

        fun updateState(stage: String, progress: Float = 0f) {
            _installStates.value = _installStates.value + (version to InstallState(version, stage, progress))
            showProgressNotification(tool, version, stage, progress)
        }

        try {
            updateState("downloading", 0f)
            marker.writeText("started at ${System.currentTimeMillis()}")
            _downloadProgress.value = _downloadProgress.value + (version to 0f)

            // Download with redirect support
            withContext(Dispatchers.IO) {
                var downloaded = 0L
                val conn = openConnectionWithRedirects(url)
                val total = conn.contentLength.toLong()

                conn.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            val progress = if (total > 0) downloaded.toFloat() / total else 0f
                            _downloadProgress.value = _downloadProgress.value + (version to progress)
                            updateState("downloading", progress)
                            n = input.read(buf)
                        }
                    }
                }

                // SHA-256 verification
                if (sha256.isNotEmpty()) {
                    updateState("verifying", 1f)
                    val actual = sha256Hex(zipFile)
                    if (!actual.equals(sha256, ignoreCase = true)) {
                        throw SecurityException(
                            "Checksum mismatch for $tool $version!\nExpected: $sha256\nGot:      $actual"
                        )
                    }
                    Log.i(TAG, "[$tool $version] SHA-256 verified ✓")
                }

                updateState("extracting", 1f)
                ZipUtils.unzip(zipFile, toolDir)
                zipFile.delete()
            }

            // Make all binaries in bin/ and libexec/ executable
            File(toolDir, "bin").listFiles()?.forEach { it.setExecutable(true) }
            File(toolDir, "libexec").walkTopDown().forEach { if (it.isFile) it.setExecutable(true) }

            marker.delete()
            Log.i(TAG, "[$tool $version] Download & extraction complete")

            updateState("verifying", 1f)

            val binPath   = File(toolDir, "bin/$tool")
            val verResult = VersionChecker.check(tool, expectedVersion = version, binaryPath = binPath.absolutePath, context = context)
            if (!verResult.isVerified) throw RuntimeException("Binary failed --version check: ${verResult.errorReason ?: "unknown"}")

            // Only mark installed after a successful verification
            _availableVersions.value = _availableVersions.value.map {
                if (it.tool == tool && it.version == version) it.copy(isInstalled = true) else it
            }

            updateState("completed", 1f)
            cancelProgressNotification()
            showCompletionNotification(tool, version, true)

            if (getActiveVersion(tool) == null) setActiveVersion(tool, version)

        } catch (e: Exception) {
            Log.e(TAG, "[$tool $version] Download failed", e)
            val msg = e.message ?: "Unknown error"
            _installStates.value = _installStates.value + (version to InstallState(version, "failed", 0f, msg))
            // Ensure UI reverts to "Available" (Get button) — not stuck on "Installed" (Use button)
            _availableVersions.value = _availableVersions.value.map {
                if (it.tool == tool && it.version == version) it.copy(isInstalled = false) else it
            }
            cancelProgressNotification()
            showCompletionNotification(tool, version, false, msg)
            zipFile.takeIf { it.exists() }?.delete()
            toolDir.deleteRecursively()
        } finally {
            _downloadProgress.value = _downloadProgress.value - version
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun fetchWithRedirects(url: String): String {
        val conn = openConnectionWithRedirects(url)
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun openConnectionWithRedirects(url: String): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10_000
        conn.readTimeout    = 30_000
        // Follow up to 5 manual redirects (GitHub → S3 may chain)
        var redirects = 0
        while (redirects < 5) {
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(location).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                redirects++
            } else break
        }
        return conn
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var n = stream.read(buf)
            while (n != -1) { digest.update(buf, 0, n); n = stream.read(buf) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("kodrix_runtime_download", "Runtime Downloads",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showProgressNotification(tool: String, version: String, stage: String, progress: Float) {
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pct = (progress * 100).toInt()
        val text = when (stage) {
            "downloading" -> "Downloading $tool $version: $pct%"
            "extracting"  -> "Extracting $tool $version…"
            "verifying"   -> "Verifying $tool $version…"
            else -> stage
        }
        val builder = NotificationCompat.Builder(context, "kodrix_runtime_download")
            .setContentTitle("Kodrix Runtime Installer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (stage == "downloading") builder.setProgress(100, pct, false)
        else builder.setProgress(100, 0, true)
        nm.notify(2002, builder.build())
    }

    private fun cancelProgressNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2002)
    }

    private fun showCompletionNotification(tool: String, version: String, success: Boolean, error: String? = null) {
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, "kodrix_runtime_download")
            .setContentTitle(if (success) "$tool Installed" else "Installation Failed")
            .setContentText(if (success) "$tool $version is ready!" else "Failed: ${error ?: "Unknown error"}")
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        nm.notify(2003, builder.build())
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        java.lang.System.exit(0)
    }

    // ── Fallback registry (offline) ───────────────────────────────────────────

    private val FALLBACK_REGISTRY = """
    {
      "node": {
        "displayName": "Node.js Runtime",
        "category": "Runtime",
        "iconUrl": "",
        "versions": [
          {
            "version": "26.2.0",
            "tag": "v26.2.0 (Current)",
            "status": "available",
            "arm64-v8a": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-current-arm64.zip",
            "armeabi-v7a": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-current-arm32.zip",
            "x86_64": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-current-x86_64.zip",
            "x86": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-current-x86.zip"
          },
          {
            "version": "24.15.0",
            "tag": "v24.15.0 (LTS)",
            "status": "available",
            "arm64-v8a": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-lts-arm64.zip",
            "armeabi-v7a": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-lts-arm32.zip",
            "x86_64": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-lts-x86_64.zip",
            "x86": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/node-lts-x86.zip"
          },
          { "version": "22.x (Jod)", "tag": "v22 LTS — Jod", "status": "unavailable", "note": "No Android/Bionic binary available." },
          { "version": "20.x (Iron)", "tag": "v20 LTS — Iron", "status": "unavailable", "note": "No Android/Bionic binary available." },
          { "version": "18.x (Hydrogen)", "tag": "v18 LTS — Hydrogen", "status": "unavailable", "note": "No Android/Bionic binary available." }
        ]
      },
      "git": {
        "displayName": "Git Version Control",
        "category": "Tools",
        "iconUrl": "",
        "versions": [
          { "version": "2.34.0", "tag": "v2.34.0 (Bundled)", "status": "available", "universal": "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/git-universal.zip" }
        ]
      },
      "python": {
        "displayName": "Python Runtime",
        "category": "Runtime",
        "iconUrl": "",
        "versions": [
          {
            "version": "3.13.13",
            "tag": "v3.13.13 (Latest)",
            "status": "available",
            "arm64-v8a": "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-arm64-v8a.zip",
            "armeabi-v7a": "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-armeabi-v7a.zip",
            "x86_64": "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-x86_64.zip",
            "x86": "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-x86.zip"
          }
        ]
      }
    }
    """.trimIndent()
}
