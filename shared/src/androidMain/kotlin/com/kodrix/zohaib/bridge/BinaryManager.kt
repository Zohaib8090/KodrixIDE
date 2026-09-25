package com.kodrix.zohaib.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kodrix.zohaib.runtime.RuntimeExec
import com.kodrix.zohaib.runtime.RuntimeManifest
import com.kodrix.zohaib.runtime.TermuxRepo
import com.kodrix.zohaib.runtime.toStringList
import com.kodrix.zohaib.runtime.toStringMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Installs and switches language runtimes described by the Kodrix registry
 * (KodrixMarketplace/versions.json). Adding a language or a new version is a registry
 * change only: each entry says where the runtime comes from ("zip" archive or "termux"
 * packages), which commands to expose, which file types it handles and how to start its
 * language server. Installed runtimes carry that description with them
 * ([RuntimeManifest]) so the rest of the app needs no per-language code.
 */
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
        val iconUrl: String,
        val description: String = "",
        /** File extensions this runtime understands, e.g. ["rs"]. */
        val extensions: List<String> = emptyList(),
        /** True when installing also gives autocomplete/diagnostics for those files. */
        val hasLanguageServer: Boolean = false,
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
        val env: Map<String, String> = emptyMap(),
        /** "bundled" (inside the APK), "zip" (prebuilt archive) or "termux" (packages). */
        val source: String = "zip",
        /** Every URL to try for a zip download, in order (mirrors). */
        val downloadUrls: List<String> = listOfNotNull(downloadUrl.ifEmpty { null }),
        /** Termux packages to install (dependencies are resolved automatically). */
        val packages: List<String> = emptyList(),
        /** Approximate download size in bytes, 0 when unknown. */
        val sizeBytes: Long = 0,
        /** Short human label: "Built-in", "Latest", "LTS" or the registry tag. */
        val label: String = "",
    )

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates = _installStates.asStateFlow()

    data class AppNotification(
        val id: String,
        val title: String,
        val text: String,
        val progress: Float?,
        val isOngoing: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _notificationsList = MutableStateFlow<List<AppNotification>>(emptyList())
    val notificationsList = _notificationsList.asStateFlow()

    fun updateAppNotification(id: String, title: String, text: String, progress: Float?, isOngoing: Boolean) {
        val list = _notificationsList.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val updated = AppNotification(id, title, text, progress, isOngoing)
        if (index != -1) {
            list[index] = updated
        } else {
            list.add(0, updated)
        }
        _notificationsList.value = list
    }

    fun clearNotifications() {
        _notificationsList.value = emptyList()
    }

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
    private val registryDir = File(filesDir, "registry")
    private val termuxArch = TermuxRepo.termuxArch(Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")

    companion object {
        private const val DOWNLOAD_IN_PROGRESS_MARKER = "download.tmp"
        private const val TAG = "BinaryManager"

        /** Tried in order; the last good response is cached on disk for offline use. */
        private val REGISTRY_URLS = listOf(
            "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/versions.json",
            "https://cdn.jsdelivr.net/gh/Zohaib8090/KodrixMarketplace@main/versions.json",
            "https://github.com/Zohaib8090/KodrixMarketplace/raw/main/versions.json",
        )

        private const val TERMUX_INDEX_MAX_AGE_MS = 6L * 60 * 60 * 1000

        // Bundled default versions — treated as "active" when no user selection exists
        private val BUNDLED_DEFAULTS = mapOf(
            "node" to Triple("25.8.2", "v25.8.2 (Built-in)", "libnode_bin.so"),
            "git"  to Triple("2.34.0", "v2.34.0 (Built-in)", "libgit_bin.so")
        )

        /** Registry tags use upstream jargon (Node calls its newest line "Current");
         *  show something that can't be mistaken for "the version you're using". */
        fun friendlyLabel(tag: String, source: String): String = when {
            source == "bundled" || tag.contains("Bundled", true) || tag.contains("Built-in", true) -> "Built-in"
            tag.contains("Current", true) || tag.contains("Latest", true) -> "Latest"
            tag.contains("LTS", true) -> "LTS"
            source == "termux" -> "Latest"
            else -> tag
        }

        /** Termux versions can carry an epoch ("3:1.27.1"); ':' would break PATH. */
        fun versionKey(raw: String) = raw.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        fun displayVersion(raw: String) = raw.substringAfter(':')
    }

    private val _availableVersions = MutableStateFlow<List<RemoteVersion>>(emptyList())
    val availableVersions = _availableVersions.asStateFlow()

    private val _toolMetas = MutableStateFlow<List<ToolMeta>>(emptyList())
    val toolMetas = _toolMetas.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    /** Per "tool_version" key: why the last install failed (shown in the Runtimes list). */
    private val _installErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val installErrors = _installErrors.asStateFlow()

    val verifiedVersions = VersionChecker.verifiedVersions

    /** Raw registry entries by tool id, from the last successful sync (or the cache). */
    @Volatile private var registryTools: Map<String, JSONObject> = emptyMap()
    @Volatile private var termuxMirrors: List<String> = TermuxRepo.DEFAULT_MIRRORS

    init {
        versionsDir.mkdirs()
        registryDir.mkdirs()
        cleanUpStaleDownloads()
        // Populate the safe-mode fallback directory (bundled-only wrappers, never modified again)
        WrapperManager.writeSafeModeWrappers(context)
        // Recreate dynamic wrappers using current active versions on startup
        rebuildWrappers()
        // Language lookups work offline from the last registry we saw.
        readCachedRegistry()?.let { applyRegistryConfig(it) }
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
            val manifest = RuntimeManifest.readFrom(installDir)

            configs.add(WrapperManager.ToolWrapperConfig(
                toolName      = toolName,
                installDir    = installDir,
                fallbackSoName = fallbackSo,
                nativeLibPath = nativeLibPath,
                libLinksDir   = libLinksDir,
                env           = manifest?.env?.takeIf { it.isNotEmpty() } ?: buildDefaultEnv(toolName, installDir),
                wrappers      = manifestWrappers(manifest) ?: buildDefaultWrappers(toolName)
            ))
        }

        // Every other tool installed from the registry
        versionsDir.listFiles()?.forEach { toolDir ->
            if (!toolDir.isDirectory) return@forEach
            val toolName = toolDir.name
            if (toolName in BUNDLED_DEFAULTS) return@forEach
            val activeVer  = getActiveVersion(toolName) ?: return@forEach
            val installDir = File(versionsDir, "$toolName/$activeVer")
            if (!installDir.isDirectory) return@forEach
            val manifest = RuntimeManifest.readFrom(installDir)
            configs.add(WrapperManager.ToolWrapperConfig(
                toolName       = toolName,
                installDir     = installDir,
                fallbackSoName = "lib${toolName}_bin.so",
                nativeLibPath  = nativeLibPath,
                libLinksDir    = libLinksDir,
                env            = manifest?.env ?: buildDefaultEnv(toolName, installDir),
                wrappers       = manifestWrappers(manifest) ?: buildDefaultWrappers(toolName)
            ))
        }

        WrapperManager.recreateWrappers(context, configs)
    }

    private fun manifestWrappers(manifest: RuntimeManifest?): List<WrapperManager.WrapperSpec>? =
        manifest?.binaries?.takeIf { it.isNotEmpty() }?.map { entry ->
            // "lua=lua5.4" exposes bin/lua5.4 as `lua`; a plain name maps to bin/<name>.
            val name = entry.substringBefore('=')
            WrapperManager.WrapperSpec(name, "symlink", "bin/" + entry.substringAfter('=', name))
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
        // clang-21 is the real binary; clang/clang++ are symlinks to it in Termux packages.
        // clangd is the Language Server Protocol binary for C/C++.
        "clang" -> listOf(
            WrapperManager.WrapperSpec("clang",   "symlink", "bin/clang-21"),
            WrapperManager.WrapperSpec("clang++", "symlink", "bin/clang-21"),
            WrapperManager.WrapperSpec("clangd",  "symlink", "bin/clangd")
        )
        else   -> listOf(WrapperManager.WrapperSpec(tool, "symlink", "bin/$tool"))
    }

    private fun buildDefaultEnv(tool: String, installDir: File): Map<String, String> = when (tool) {
        "node" -> mapOf("NODE_PATH" to "${installDir.absolutePath}/lib/node_modules")
        "python" -> mapOf("PYTHONHOME" to installDir.absolutePath)
        // clang needs its sysroot so the compiler can find <stdio.h> etc.
        "clang" -> mapOf(
            "CPATH" to "${installDir.absolutePath}/sysroot/usr/include",
            "LIBRARY_PATH" to "${installDir.absolutePath}/sysroot/usr/lib"
        )
        else   -> emptyMap()
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    private val registryCache get() = File(registryDir, "versions.json")

    private fun readCachedRegistry(): JSONObject? = try {
        registryCache.takeIf { it.isFile }?.readText()?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    /** Every mirror in order, then the on-disk cache, then the copy compiled into the app. */
    private fun fetchRegistry(): JSONObject {
        val extra = readCachedRegistry()?.optJSONObject("_config")?.optJSONArray("registryMirrors").toStringList()
        for (url in (REGISTRY_URLS + extra).distinct()) {
            try {
                val text = TermuxRepo.fetchText(url)
                val json = JSONObject(text)
                registryCache.writeText(text)
                return json
            } catch (e: Exception) {
                Log.w(TAG, "Registry mirror failed ($url): ${e.message}")
            }
        }
        readCachedRegistry()?.let { Log.w(TAG, "All registry mirrors failed — using cached copy"); return it }
        Log.w(TAG, "All registry mirrors failed and no cache — using built-in fallback")
        return JSONObject(FALLBACK_REGISTRY)
    }

    private fun applyRegistryConfig(root: JSONObject) {
        val tools = LinkedHashMap<String, JSONObject>()
        root.keys().forEach { key ->
            if (!key.startsWith("_")) root.optJSONObject(key)?.let { tools[key] = it }
        }
        registryTools = tools
        root.optJSONObject("_config")?.optJSONArray("termuxMirrors").toStringList()
            .takeIf { it.isNotEmpty() }?.let { termuxMirrors = (it + TermuxRepo.DEFAULT_MIRRORS).distinct() }
    }

    /**
     * Termux index from the first mirror that answers, cached for a few hours. Returns the
     * mirror too, because the .debs must be downloaded from the same one.
     */
    private fun termuxIndex(maxAgeMs: Long): Pair<String, TermuxRepo.Index>? {
        var stale: Pair<String, File>? = null
        for (mirror in termuxMirrors) {
            val cache = File(registryDir, "termux-$termuxArch-${mirror.hashCode().toUInt()}.txt")
            if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < maxAgeMs) {
                try { return mirror to TermuxRepo.parseIndex(cache.readText()) } catch (_: Exception) {}
            }
            try {
                val text = TermuxRepo.fetchText(TermuxRepo.indexUrl(mirror, termuxArch))
                cache.writeText(text)
                return mirror to TermuxRepo.parseIndex(text)
            } catch (e: Exception) {
                Log.w(TAG, "Termux mirror failed ($mirror): ${e.message}")
                if (stale == null && cache.isFile) stale = mirror to cache
            }
        }
        return stale?.let { (m, f) -> try { m to TermuxRepo.parseIndex(f.readText()) } catch (_: Exception) { null } }
    }

    suspend fun syncVersions() {
        _isSyncing.value = true
        try {
            val root = withContext(Dispatchers.IO) { fetchRegistry() }
            applyRegistryConfig(root)
            val abi  = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val list = mutableListOf<RemoteVersion>()
            val metas = mutableListOf<ToolMeta>()
            val needsTermux = registryTools.values.any { tool ->
                tool.optJSONArray("versions")?.let { arr -> (0 until arr.length()).any { arr.optJSONObject(it)?.optString("source") == "termux" } } == true
            }
            val termux = if (needsTermux) withContext(Dispatchers.IO) { termuxIndex(TERMUX_INDEX_MAX_AGE_MS) } else null

            for ((toolName, toolObj) in registryTools) {
                val activeVer = getActiveVersion(toolName)
                val bundled   = BUNDLED_DEFAULTS[toolName]
                val languages = toolObj.optJSONObject("languages").toStringMap()

                metas.add(ToolMeta(
                    id          = toolName,
                    displayName = toolObj.optString("displayName", toolName),
                    category    = toolObj.optString("category", "Runtime"),
                    iconUrl     = toolObj.optString("iconUrl", ""),
                    description = toolObj.optString("description", ""),
                    extensions  = languages.keys.toList(),
                    hasLanguageServer = toolObj.optJSONObject("lsp") != null,
                ))

                // The version shipped inside the APK always comes first
                bundled?.let { (bundledVer, bundledTag) ->
                    list.add(RemoteVersion(
                        tool        = toolName,
                        version     = bundledVer,
                        tag         = bundledTag,
                        downloadUrl = "",
                        isInstalled = true,
                        isActive    = (activeVer == null || activeVer == bundledVer),
                        source      = "bundled",
                        label       = "Built-in",
                    ))
                }

                val versionsArray = toolObj.optJSONArray("versions") ?: JSONArray()
                for (i in 0 until versionsArray.length()) {
                    val obj = versionsArray.optJSONObject(i) ?: continue
                    if (obj.optString("status", "available") == "unavailable") continue
                    // Kept in the registry only for older app builds.
                    if (obj.optBoolean("legacy", false)) continue
                    val parsed = if (obj.optString("source") == "termux") {
                        termuxVersion(toolName, obj, termux)
                    } else {
                        zipVersion(toolName, obj, abi)
                    } ?: continue
                    if (bundled != null && parsed.version == bundled.first) continue
                    val dir = File(versionsDir, "$toolName/${parsed.version}")
                    val installed = parsed.version.isNotEmpty() && isInstalledDir(toolName, dir)
                    list.add(parsed.copy(isInstalled = installed, isActive = installed && activeVer == parsed.version))
                }

                // Installed versions the registry no longer lists stay visible (and usable).
                File(versionsDir, toolName).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                    if (list.none { it.tool == toolName && it.version == dir.name } && isInstalledDir(toolName, dir)) {
                        list.add(RemoteVersion(
                            tool = toolName, version = dir.name, tag = dir.name, downloadUrl = "",
                            isInstalled = true, isActive = activeVer == dir.name, source = "installed", label = "Installed",
                        ))
                    }
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

    private fun isInstalledDir(tool: String, dir: File): Boolean {
        if (!dir.isDirectory || File(dir, DOWNLOAD_IN_PROGRESS_MARKER).exists()) return false
        if (File(dir, RuntimeManifest.FILE_NAME).isFile) return true
        val binName = if (tool == "node") "node" else tool
        return File(dir, "bin/$binName").exists()
    }

    private fun zipVersion(toolName: String, obj: JSONObject, abi: String): RemoteVersion? {
        val ver = obj.optString("version").ifEmpty { return null }
        val tag = obj.optString("tag", ver)
        // "mirrors" (per ABI) lists every copy of the file; the plain ABI key holds one URL
        // for older app builds. An ABI entry may itself be a list. Falls back to "universal".
        val mirrors = obj.optJSONObject("mirrors")
        val urls = (urlList(mirrors?.opt(abi)) + urlList(obj.opt(abi))).distinct()
            .ifEmpty { (urlList(mirrors?.opt("universal")) + urlList(obj.opt("universal"))).distinct() }
        if (urls.isEmpty()) return null
        val rulesObj = obj.optJSONObject("rules")
        val sha = obj.optJSONObject("sha256ByAbi")?.optString(abi, "").orEmpty().ifEmpty { obj.optString("sha256", "") }
        return RemoteVersion(
            tool = toolName, version = ver, tag = tag, downloadUrl = urls.first(),
            sha256 = sha,
            wrappers = parseWrappers(rulesObj?.optJSONObject("wrappers")),
            env = parseEnvBlock(rulesObj?.optJSONObject("env")),
            source = "zip", downloadUrls = urls, sizeBytes = obj.optLong("size", 0L),
            label = friendlyLabel(tag, "zip"),
        )
    }

    private fun termuxVersion(toolName: String, obj: JSONObject, termux: Pair<String, TermuxRepo.Index>?): RemoteVersion? {
        val packages = obj.optJSONArray("packages").toStringList().ifEmpty { return null }
        val tagHint = obj.optString("tag", "Latest")
        if (termux == null) {
            return RemoteVersion(
                tool = toolName, version = "", tag = tagHint, downloadUrl = "", source = "termux", packages = packages,
                isUnavailable = true, note = "Couldn't reach the package servers. Check your connection and refresh.",
                label = friendlyLabel(tagHint, "termux"),
            )
        }
        val index = termux.second
        val main = index.find(obj.optString("versionPackage", packages.first())) ?: return null
        val size = try { TermuxRepo.resolve(index, packages).sumOf { it.size } } catch (_: Exception) { 0L }
        return RemoteVersion(
            tool = toolName, version = versionKey(main.version), tag = "v${displayVersion(main.version)}",
            downloadUrl = "", source = "termux", packages = packages, sizeBytes = size,
            label = friendlyLabel(tagHint, "termux"),
        )
    }

    private fun urlList(value: Any?): List<String> = when (value) {
        is String -> listOf(value).filter { it.isNotEmpty() }
        is JSONArray -> value.toStringList().filter { it.isNotEmpty() }
        else -> emptyList()
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

    // ── Languages (for the editor) ────────────────────────────────────────────

    data class LanguageRuntime(val manifest: RuntimeManifest, val installDir: File, val languageId: String)

    /** The active installed runtime that handles files with [extension], if any. */
    fun languageRuntimeFor(extension: String): LanguageRuntime? {
        val ext = extension.lowercase()
        versionsDir.listFiles()?.forEach { toolDir ->
            if (!toolDir.isDirectory) return@forEach
            val active = getActiveVersion(toolDir.name) ?: return@forEach
            val dir = File(toolDir, active)
            val manifest = RuntimeManifest.readFrom(dir) ?: return@forEach
            manifest.languages[ext]?.let { return LanguageRuntime(manifest, dir, it) }
        }
        return null
    }

    /** Display name of a registry runtime that *would* handle [extension] once installed. */
    fun installableLanguageFor(extension: String): String? {
        val ext = extension.lowercase()
        return registryTools.entries.firstOrNull { (_, obj) ->
            obj.optJSONObject("languages")?.has(ext) == true
        }?.let { (id, obj) -> obj.optString("displayName", id) }
    }

    // ── Active version management ─────────────────────────────────────────────

    fun getActiveVersion(tool: String): String? {
        val stored = prefs.getString("active_$tool", null) ?: return null
        val bundledVer = BUNDLED_DEFAULTS[tool]?.first
        return if (stored == bundledVer) null else stored
    }

    /**
     * Marks [tool] as having [version] installed and active WITHOUT running the
     * binary for verification. Use this for cross-compiled tools (e.g. Clang)
     * that cannot self-execute inside the Android app sandbox.
     */
    fun markToolInstalled(tool: String, version: String) {
        prefs.edit().putString("active_$tool", version).apply()
        syncActiveVersionToFile(tool, version)
        updateActiveUI(tool, version)
        rebuildWrappers()
        Log.i(TAG, "[$tool] Marked installed at $version (no verification)")
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

        // Verify (the bundled version lives in the APK and needs no check here)
        if (version == bundledVer) return true
        VersionChecker.clearVerified(tool)
        val result = verifyInstall(tool, File(versionsDir, "$tool/$version"))

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
        throw RuntimeException(
            "Couldn't switch to $tool $version, so Kodrix kept $previousActive.\n${result.errorReason ?: ""}".trim()
        )
    }

    /**
     * Runs the installed runtime's version command the same way the terminal will.
     * Uses the registry's "verify" command when given (e.g. `go version`).
     */
    private suspend fun verifyInstall(tool: String, installDir: File): VersionChecker.VerifiedVersion {
        val manifest = RuntimeManifest.readFrom(installDir)
        val verify = registryTools[tool]?.optJSONObject("verify")
        val cmd = verify?.optJSONArray("command").toStringList()
        val (binary, args) = if (cmd.isNotEmpty()) {
            File(RuntimeExec.expand(cmd.first(), installDir)) to cmd.drop(1)
        } else {
            val name = manifest?.binaries?.firstOrNull()?.let { it.substringAfter('=', it) } ?: tool
            File(installDir, "bin/$name") to listOf("--version")
        }
        return VersionChecker.check(
            tool, expectedVersion = installDir.name, binaryPath = binary.absolutePath, context = context,
            args = args, extraEnv = manifest?.env ?: emptyMap(),
        )
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

    // ── Install ───────────────────────────────────────────────────────────────

    /** Installs [ver] from whichever source it names, then verifies and activates it. */
    suspend fun install(ver: RemoteVersion) {
        when (ver.source) {
            "termux" -> installFromTermux(ver)
            "zip" -> downloadVersion(ver.tool, ver.version, ver.downloadUrl, ver.sha256, ver.downloadUrls)
            else -> Log.w(TAG, "Nothing to install for ${ver.tool} ${ver.version} (${ver.source})")
        }
    }

    private fun errorKey(tool: String, version: String) = "${tool}_$version"

    private fun setProgress(tool: String, version: String, stage: String, progress: Float) {
        _installStates.value = _installStates.value + (version to InstallState(version, stage, progress))
        _downloadProgress.value = _downloadProgress.value + (version to progress)
        showProgressNotification(tool, version, stage, progress)
    }

    /**
     * Resolves [ver]'s packages against a Termux mirror, downloads and SHA-256-checks every
     * .deb, extracts them, adds the language server and verifies the result actually runs.
     *
     * Extraction goes straight into the final directory (shebangs and absolute symlinks are
     * rewritten to that exact path, so it can't be moved afterwards). The in-progress
     * marker makes an interrupted install get cleaned up on next start; other installed
     * versions live in their own directories and are never touched.
     */
    private suspend fun installFromTermux(ver: RemoteVersion) {
        val tool = ver.tool
        val version = ver.version
        val finalDir = File(versionsDir, "$tool/$version")
        val marker = File(finalDir, DOWNLOAD_IN_PROGRESS_MARKER)
        val debCache = File(context.cacheDir, "termux-debs")
        _installErrors.value = _installErrors.value - errorKey(tool, version)
        try {
            setProgress(tool, version, "resolving", 0f)
            val installed = withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (mirror in termuxMirrors) {
                    try {
                        val index = TermuxRepo.parseIndex(TermuxRepo.fetchText(TermuxRepo.indexUrl(mirror, termuxArch)))
                        val pkgs = TermuxRepo.resolve(index, ver.packages)
                        val total = pkgs.sumOf { it.size }.coerceAtLeast(1L)
                        var done = 0L
                        finalDir.deleteRecursively()
                        finalDir.mkdirs()
                        marker.writeText("started at ${System.currentTimeMillis()}")
                        for (p in pkgs) {
                            val deb = File(debCache, p.filename.substringAfterLast('/'))
                            val base = done
                            TermuxRepo.download("$mirror/${p.filename}", deb, p.sha256) { bytes ->
                                setProgress(tool, version, "downloading", ((base + bytes).toFloat() / total).coerceAtMost(0.99f))
                            }
                            done += p.size
                            TermuxRepo.extractDeb(deb, finalDir)
                            deb.delete()
                        }
                        TermuxRepo.rewriteShebangs(finalDir)
                        return@withContext pkgs.map { "${it.name}=${it.version}" }
                    } catch (e: Exception) {
                        Log.w(TAG, "[$tool] Termux install via $mirror failed: ${e.message}")
                        lastError = e
                    }
                }
                throw lastError ?: RuntimeException("No package mirror reachable")
            }

            val manifest = buildManifest(tool, version, "termux", installed)
            manifest.lsp?.npm?.takeIf { it.isNotEmpty() }?.let { npm ->
                setProgress(tool, version, "language server", 1f)
                withContext(Dispatchers.IO) { installNpmLanguageServer(finalDir, npm) }
            }
            manifest.writeTo(finalDir)
            marker.delete()

            setProgress(tool, version, "verifying", 1f)
            val result = verifyInstall(tool, finalDir)
            if (!result.isVerified) throw RuntimeException(result.errorReason ?: "The runtime didn't start")
            finishInstall(tool, version)
        } catch (e: Exception) {
            failInstall(tool, version, e)
            finalDir.deleteRecursively()
        } finally {
            _downloadProgress.value = _downloadProgress.value - version
        }
    }

    /** Manifest for an install, from the tool's registry entry. */
    private fun buildManifest(tool: String, version: String, source: String, packages: List<String>): RuntimeManifest {
        val obj = registryTools[tool] ?: JSONObject()
        return RuntimeManifest(
            tool = tool,
            version = version,
            displayName = obj.optString("displayName", tool),
            source = source,
            packages = packages,
            binaries = obj.optJSONArray("binaries").toStringList(),
            env = obj.optJSONObject("env").toStringMap(),
            languages = obj.optJSONObject("languages").toStringMap().mapKeys { it.key.lowercase().removePrefix(".") },
            lsp = RuntimeManifest.Lsp.fromJson(obj.optJSONObject("lsp")),
        )
    }

    /**
     * Installs JavaScript language servers (pyright, intelephense, …) into
     * `<install>/lsp` using the Node.js and npm built into the app.
     */
    private fun installNpmLanguageServer(installDir: File, packages: List<String>) {
        val lspDir = File(installDir, "lsp").apply { mkdirs() }
        File(lspDir, "package.json").writeText("""{"name":"kodrix-lsp","version":"1.0.0","private":true}""")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val node = File(nativeLibDir, "libnode_bin.so").absolutePath
        val npmCli = File(filesDir, "npm_pkg/bin/npm-cli.js").absolutePath
        val libLinks = File(filesDir, "lib").absolutePath
        val dnsOverride = File(filesDir, "usr/etc/dns-override.js")
        val nodeOptions = if (dnsOverride.exists()) "export NODE_OPTIONS='--require ${dnsOverride.absolutePath}'; " else ""
        val quoted = packages.joinToString(" ") { "'" + it.replace("'", "") + "'" }
        val cmd = "export LD_LIBRARY_PATH='$libLinks'; export OPENSSL_CONF=/dev/null; $nodeOptions" +
            "exec '$node' '$npmCli' install $quoted --no-fund --no-audit --prefix '${lspDir.absolutePath}'"
        val proc = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(lspDir).redirectErrorStream(true).apply {
            environment()["HOME"] = filesDir.absolutePath
            environment()["TMPDIR"] = File(filesDir, "tmp").apply { mkdirs() }.absolutePath
            environment()["PATH"] = "$nativeLibDir:${filesDir.absolutePath}/usr/bin:/system/bin"
        }.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            throw RuntimeException("Language server install (npm ${packages.joinToString()}) failed:\n${output.takeLast(600)}")
        }
    }

    private suspend fun finishInstall(tool: String, version: String) {
        cancelProgressNotification()
        showCompletionNotification(tool, version, true)
        _installStates.value = _installStates.value + (version to InstallState(version, "completed", 1f))
        prefs.edit().putString("active_$tool", version).apply()
        syncActiveVersionToFile(tool, version)
        rebuildWrappers()
        syncVersions()
    }

    private fun failInstall(tool: String, version: String, e: Exception) {
        Log.e(TAG, "[$tool $version] Install failed", e)
        val msg = e.message ?: e.javaClass.simpleName
        _installStates.value = _installStates.value + (version to InstallState(version, "failed", 0f, msg))
        _installErrors.value = _installErrors.value + (errorKey(tool, version) to msg)
        _availableVersions.value = _availableVersions.value.map {
            if (it.tool == tool && it.version == version) it.copy(isInstalled = false) else it
        }
        cancelProgressNotification()
        showCompletionNotification(tool, version, false, msg)
    }

    /**
     * Downloads, SHA-256 verifies, extracts, and activates a zip-packaged version, trying
     * each of [urls] (mirrors) in turn. Kept for callers that pass a single URL.
     */
    suspend fun downloadVersion(tool: String, version: String, url: String, sha256: String = "", urls: List<String> = listOf(url)) {
        val toolDir = File(versionsDir, "$tool/$version")
        toolDir.mkdirs()
        val marker  = File(toolDir, DOWNLOAD_IN_PROGRESS_MARKER)
        val zipFile = File(toolDir, "bundle.zip")
        _installErrors.value = _installErrors.value - errorKey(tool, version)

        try {
            setProgress(tool, version, "downloading", 0f)
            marker.writeText("started at ${System.currentTimeMillis()}")

            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                var ok = false
                for (candidate in urls.ifEmpty { listOf(url) }.distinct()) {
                    try {
                        val conn: HttpURLConnection = TermuxRepo.openWithRedirects(candidate)
                        val total = conn.contentLength.toLong()
                        var downloaded = 0L
                        conn.inputStream.use { input ->
                            zipFile.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var n = input.read(buf)
                                while (n != -1) {
                                    output.write(buf, 0, n)
                                    downloaded += n
                                    setProgress(tool, version, "downloading", if (total > 0) downloaded.toFloat() / total else 0f)
                                    n = input.read(buf)
                                }
                            }
                        }
                        if (sha256.isNotEmpty()) {
                            setProgress(tool, version, "verifying", 1f)
                            val actual = sha256Hex(zipFile)
                            if (!actual.equals(sha256, ignoreCase = true)) {
                                throw SecurityException("Checksum mismatch for $tool $version from $candidate")
                            }
                            Log.i(TAG, "[$tool $version] SHA-256 verified ✓")
                        }
                        ok = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "[$tool $version] Download from $candidate failed: ${e.message}")
                        lastError = e
                        zipFile.delete()
                    }
                }
                if (!ok) throw lastError ?: RuntimeException("No download URL")

                setProgress(tool, version, "extracting", 1f)
                ZipUtils.unzip(zipFile, toolDir)
                zipFile.delete()
            }

            // Ensure correct read/write permissions for all extracted contents
            toolDir.walkTopDown().forEach { file ->
                file.setReadable(true, true)
                file.setWritable(true, true)
                if (file.isDirectory) {
                    file.setExecutable(true, true)
                }
            }

            // Make all binaries in bin/ and libexec/ executable
            File(toolDir, "bin").listFiles()?.forEach { it.setExecutable(true, true) }
            File(toolDir, "libexec").walkTopDown().forEach { if (it.isFile) it.setExecutable(true, true) }

            val manifest = buildManifest(tool, version, "zip", emptyList())
            if (registryTools.containsKey(tool)) {
                manifest.lsp?.npm?.takeIf { it.isNotEmpty() }?.let { npm ->
                    setProgress(tool, version, "language server", 1f)
                    withContext(Dispatchers.IO) { installNpmLanguageServer(toolDir, npm) }
                }
                manifest.writeTo(toolDir)
            }

            marker.delete()
            Log.i(TAG, "[$tool $version] Download & extraction complete")

            setProgress(tool, version, "verifying", 1f)
            val verResult = verifyInstall(tool, toolDir)
            if (!verResult.isVerified) throw RuntimeException(verResult.errorReason ?: "The runtime didn't start")

            // Only mark installed after a successful verification
            _availableVersions.value = _availableVersions.value.map {
                if (it.tool == tool && it.version == version) it.copy(isInstalled = true) else it
            }

            _installStates.value = _installStates.value + (version to InstallState(version, "completed", 1f))
            cancelProgressNotification()
            showCompletionNotification(tool, version, true)

            if (getActiveVersion(tool) == null && tool !in BUNDLED_DEFAULTS) {
                prefs.edit().putString("active_$tool", version).apply()
                syncActiveVersionToFile(tool, version)
                rebuildWrappers()
                updateActiveUI(tool, version)
            }
        } catch (e: Exception) {
            failInstall(tool, version, e)
            zipFile.takeIf { it.exists() }?.delete()
            toolDir.deleteRecursively()
        } finally {
            _downloadProgress.value = _downloadProgress.value - version
        }
    }

    /** Deletes an installed version (never the one in use). */
    suspend fun uninstall(tool: String, version: String) {
        if (getActiveVersion(tool) == version) {
            if (tool in BUNDLED_DEFAULTS) setActiveVersion(tool, BUNDLED_DEFAULTS.getValue(tool).first)
            else {
                prefs.edit().remove("active_$tool").apply()
                syncActiveVersionToFile(tool, null)
            }
        }
        withContext(Dispatchers.IO) { File(versionsDir, "$tool/$version").deleteRecursively() }
        rebuildWrappers()
        syncVersions()
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

    fun showProgressNotification(tool: String, version: String, stage: String, progress: Float) {
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pct = (progress * 100).toInt()
        val text = when (stage) {
            "resolving"   -> "Preparing $tool…"
            "downloading" -> "Downloading $tool $version: $pct%"
            "extracting"  -> "Extracting $tool $version…"
            "language server" -> "Installing the $tool language server…"
            "verifying"   -> "Checking $tool $version runs…"
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

        updateAppNotification(
            id = "${tool}_$version",
            title = "Installing ${tool.uppercase()} $version",
            text = text,
            progress = progress,
            isOngoing = true
        )
    }

    fun cancelProgressNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2002)
    }

    fun showCompletionNotification(tool: String, version: String, success: Boolean, error: String? = null) {
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, "kodrix_runtime_download")
            .setContentTitle(if (success) "$tool Installed" else "Installation Failed")
            .setContentText(if (success) "$tool $version is ready!" else "Failed: ${error?.lineSequence()?.firstOrNull() ?: "Unknown error"}")
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        nm.notify(2003, builder.build())

        updateAppNotification(
            id = "${tool}_$version",
            title = if (success) "${tool.uppercase()} Installed" else "${tool.uppercase()} Installation Failed",
            text = if (success) "Version $version is ready to use!" else "Failed: ${error ?: "Unknown error"}",
            progress = null,
            isOngoing = false
        )
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        java.lang.System.exit(0)
    }

    // ── Fallback registry (offline, first run only) ───────────────────────────
    // Only used when every mirror fails AND there's no cached copy yet. Kept minimal:
    // the real catalogue lives in KodrixMarketplace/versions.json.

    private val FALLBACK_REGISTRY = """
    {
      "node": {
        "displayName": "Node.js",
        "category": "Runtime",
        "iconUrl": "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/icons/node.png",
        "versions": [
          {
            "version": "26.2.0",
            "tag": "v26.2.0 (Latest)",
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
          }
        ]
      },
      "git": {
        "displayName": "Git",
        "category": "Tools",
        "iconUrl": "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/icons/git.png",
        "versions": []
      }
    }
    """.trimIndent()
}
