package com.kodrix.zohaib.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Self-description written into every installed runtime (`kodrix-runtime.json`), built
 * from the registry entry at install time. Everything language-specific the app needs
 * (commands to put on PATH, env, file types, how to start the language server) comes
 * from here, so supporting a new language means publishing a registry entry, not
 * shipping a new APK.
 */
data class RuntimeManifest(
    val tool: String,
    val version: String,
    val displayName: String,
    /** "termux" (resolved from the Termux repository) or "zip" (a prebuilt archive). */
    val source: String,
    /** Termux packages actually installed, as "name=version". */
    val packages: List<String> = emptyList(),
    /** Commands from bin/ exposed on the terminal PATH. */
    val binaries: List<String> = emptyList(),
    /** Extra env; `${install}` expands to the install dir. */
    val env: Map<String, String> = emptyMap(),
    /** File extension (no dot, lowercase) → LSP languageId. */
    val languages: Map<String, String> = emptyMap(),
    val lsp: Lsp? = null,
) {
    data class Lsp(
        /** argv; `${install}` → install dir, `${node}` → the Node.js built into the app. */
        val command: List<String>,
        /** npm packages installed into `${install}/lsp` with the built-in Node, for
         *  language servers written in JavaScript (e.g. pyright, intelephense). */
        val npm: List<String> = emptyList(),
        val initializationOptions: JSONObject? = null,
    ) {
        fun toJson() = JSONObject().apply {
            put("command", JSONArray(command))
            if (npm.isNotEmpty()) put("npm", JSONArray(npm))
            initializationOptions?.let { put("initializationOptions", it) }
        }

        companion object {
            fun fromJson(o: JSONObject?): Lsp? {
                o ?: return null
                val cmd = o.optJSONArray("command").toStringList()
                if (cmd.isEmpty()) return null
                return Lsp(cmd, o.optJSONArray("npm").toStringList(), o.optJSONObject("initializationOptions"))
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("version", version)
        put("displayName", displayName)
        put("source", source)
        put("packages", JSONArray(packages))
        put("binaries", JSONArray(binaries))
        put("env", JSONObject(env as Map<*, *>))
        put("languages", JSONObject(languages as Map<*, *>))
        lsp?.let { put("lsp", it.toJson()) }
    }

    fun writeTo(installDir: File) {
        File(installDir, FILE_NAME).writeText(toJson().toString(2))
    }

    companion object {
        const val FILE_NAME = "kodrix-runtime.json"

        fun readFrom(installDir: File): RuntimeManifest? {
            val f = File(installDir, FILE_NAME)
            if (!f.isFile) return null
            return try {
                val o = JSONObject(f.readText())
                RuntimeManifest(
                    tool = o.getString("tool"),
                    version = o.getString("version"),
                    displayName = o.optString("displayName", o.getString("tool")),
                    source = o.optString("source", "zip"),
                    packages = o.optJSONArray("packages").toStringList(),
                    binaries = o.optJSONArray("binaries").toStringList(),
                    env = o.optJSONObject("env").toStringMap(),
                    languages = o.optJSONObject("languages").toStringMap().mapKeys { it.key.lowercase().removePrefix(".") },
                    lsp = Lsp.fromJson(o.optJSONObject("lsp")),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    this ?: return emptyList()
    return (0 until length()).mapNotNull { optString(it, null) }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    this ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    keys().forEach { k -> out[k] = optString(k, "") }
    return out
}
