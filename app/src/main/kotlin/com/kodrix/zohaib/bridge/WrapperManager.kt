package com.kodrix.zohaib.bridge

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * WrapperManager — generates and atomically updates terminal executable wrappers.
 *
 * Design principles:
 *  1. **Hybrid routing** — native ELF binaries get zero-overhead OS symlinks via [Os.symlink].
 *     Script entry points (e.g. npm-cli.js) get a tiny shell wrapper that prefixes the
 *     correct interpreter, keeping the shell's exec path clean.
 *
 *  2. **Atomic swap** — all new symlinks/wrappers are written into a `bin_new/` staging
 *     directory first. A single [Files.move] with ATOMIC_MOVE then replaces the live `bin/`
 *     so in-flight terminal processes never see a partially-populated PATH directory.
 *
 *  3. **Environment injection** — env vars declared in the registry `rules.env` block are
 *     baked into each generated script wrapper, keeping Kotlin fully decoupled from
 *     per-language runtime requirements.
 */
object WrapperManager {
    private const val TAG = "WrapperManager"

    /**
     * Metadata parsed from the registry `rules.wrappers` block for a single wrapper entry.
     *
     * @param name        The command name to create in `usr/bin/` (e.g. "npm").
     * @param type        Either "symlink" or "script".
     * @param path        Path to the target relative to the version's install dir
     *                    (e.g. "lib/node_modules/npm/bin/npm-cli.js").
     * @param interpreter For "script" type: the command that should execute the script (e.g. "node").
     */
    data class WrapperSpec(
        val name: String,
        val type: String,         // "symlink" | "script"
        val path: String,         // relative to installDir
        val interpreter: String = ""  // only for type == "script"
    )

    /**
     * Full set of metadata needed to recreate wrappers for one installed tool version.
     */
    data class ToolWrapperConfig(
        val toolName: String,
        val installDir: File,          // absolute path e.g. filesDir/versions/node/26.2.0
        val fallbackSoName: String,    // e.g. "libnode_bin.so" — used when no active version is set
        val nativeLibPath: String,
        val libLinksDir: String,
        val env: Map<String, String>,  // env vars to inject into script wrappers
        val wrappers: List<WrapperSpec>
    )

    /**
     * Atomically recreate all wrappers in `filesDir/usr/bin/`.
     *
     * Call this:
     *  - On app startup (from PtyBridge before first terminal launch)
     *  - After [BinaryManager.setActiveVersion] switches a tool version
     *
     * @param configs  One [ToolWrapperConfig] per tool that needs wrappers. Callers
     *                 (BinaryManager) are responsible for building this list from registry
     *                 data + currently active versions on disk.
     */
    fun recreateWrappers(context: Context, configs: List<ToolWrapperConfig>) {
        val filesDir      = context.filesDir
        val usrBinDir     = File(filesDir, "usr/bin")
        val stagingDir    = File(filesDir, "usr/bin_new")

        // Clean up any leftover staging dir from a previous crashed run
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        try {
            for (cfg in configs) {
                for (spec in cfg.wrappers) {
                    val target = File(cfg.installDir, spec.path)
                    val dest   = File(stagingDir, spec.name)

                    when (spec.type) {
                        "symlink" -> writeSymlink(target, dest, cfg)
                        "script"  -> writeScriptWrapper(target, dest, spec.interpreter, cfg)
                        else      -> Log.w(TAG, "[${cfg.toolName}] Unknown wrapper type '${spec.type}' for '${spec.name}' — skipping")
                    }
                }
            }

            // Atomic swap: bin_new → bin using rename-and-delete fallback
            val oldBackupDir = File(filesDir, "usr/bin_old")
            oldBackupDir.deleteRecursively() // ensure clean state

            if (usrBinDir.exists()) {
                if (!usrBinDir.renameTo(oldBackupDir)) {
                    Log.w(TAG, "Failed to rename usr/bin to usr/bin_old, trying file-by-file fallback or direct move")
                }
            }

            if (stagingDir.renameTo(usrBinDir)) {
                oldBackupDir.deleteRecursively()
                Log.i(TAG, "Wrappers atomically updated (${configs.sumOf { it.wrappers.size }} entries)")
            } else {
                // Restore backup if renaming stagingDir to usrBinDir failed
                if (oldBackupDir.exists()) {
                    oldBackupDir.renameTo(usrBinDir)
                }
                // Try Files.move as a final effort
                try {
                    Files.move(stagingDir.toPath(), usrBinDir.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Log.i(TAG, "Wrappers updated via Files.move fallback")
                } catch (moveEx: Exception) {
                    throw RuntimeException("Failed to rename staging directory usr/bin_new to usr/bin: ${moveEx.message}", moveEx)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate wrappers — staging dir left intact for diagnostics", e)
            // Leave staging dir in place for post-mortem inspection; live bin/ is unaffected.
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Creates an OS-level symlink [dest] → [target].
     *
     * If [target] doesn't exist yet (tool not yet downloaded), falls back to writing
     * a minimal "not installed" error script so the command still responds gracefully.
     */
    private fun writeSymlink(target: File, dest: File, cfg: ToolWrapperConfig) {
        try {
            if (dest.exists() || isSymlink(dest)) {
                dest.delete()  // remove old symlink before creating new one
            }

            if (target.exists()) {
                target.setExecutable(true)
                Os.symlink(target.absolutePath, dest.absolutePath)
                Log.d(TAG, "  symlink ${dest.name} → ${target.absolutePath}")
            } else {
                // Target binary not present — write a fallback shell that tries the native .so
                writeNotInstalledScript(dest, cfg.toolName, cfg.nativeLibPath, cfg.fallbackSoName, cfg.libLinksDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "  Failed symlink for ${dest.name}: ${e.message}")
        }
    }

    /**
     * Writes a shell script that invokes [interpreter] with the [target] script path,
     * prepending any env vars from [cfg.env].
     */
    private fun writeScriptWrapper(
        target: File,
        dest: File,
        interpreter: String,
        cfg: ToolWrapperConfig
    ) {
        val usrBinPath = cfg.installDir.parentFile?.parentFile?.parentFile
            ?.let { File(File(it, "usr"), "bin") }?.absolutePath ?: ""

        val envExports = buildString {
            cfg.env.forEach { (k, v) ->
                val absValue = if (v.startsWith("/")) v else "${cfg.installDir.absolutePath}/$v"
                append("export $k=\"$absValue\"\n")
            }
        }

        val scriptContent = if (target.exists()) {
            """
            #!/system/bin/sh
            $envExports
            export LD_LIBRARY_PATH="${cfg.installDir.absolutePath}/lib:${cfg.nativeLibPath}:${cfg.libLinksDir}"
            exec "$usrBinPath/$interpreter" "${target.absolutePath}" "${'$'}@"
            """.trimIndent()
        } else {
            // Script target missing — write a "not installed" fallback
            """
            #!/system/bin/sh
            echo "${dest.name}: target script not found at ${target.absolutePath}"
            echo "Try re-downloading this runtime from the Kodrix Marketplace."
            exit 1
            """.trimIndent()
        }

        try {
            dest.writeText(scriptContent)
            dest.setExecutable(true)
            Log.d(TAG, "  script   ${dest.name} → ${target.absolutePath} (via $interpreter)")
        } catch (e: Exception) {
            Log.e(TAG, "  Failed script wrapper for ${dest.name}: ${e.message}")
        }
    }

    /**
     * Writes a shell stub that attempts to exec the bundled native `.so` for the tool,
     * or prints a helpful error if not available.
     */
    private fun writeNotInstalledScript(
        dest: File,
        toolName: String,
        nativeLibPath: String,
        fallbackSoName: String,
        libLinksDir: String
    ) {
        val fallback = File(nativeLibPath, fallbackSoName)
        val content = if (fallback.exists()) {
            """
            #!/system/bin/sh
            export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
            exec "$nativeLibPath/$fallbackSoName" "${'$'}@"
            """.trimIndent()
        } else {
            """
            #!/system/bin/sh
            echo "$toolName is not installed. Please install it from the Kodrix Marketplace."
            exit 1
            """.trimIndent()
        }
        try {
            dest.writeText(content)
            dest.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "  Failed fallback script for ${dest.name}: ${e.message}")
        }
    }

    /** [File.isSymbolicLink] isn't available until API 26; this covers all versions. */
    private fun isSymlink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) { false }
    }

    // ── Safe Mode (Emergency Rollback) ────────────────────────────────────────

    /**
     * Writes minimal bundled-only wrappers into `filesDir/usr/bin_safe/`.
     *
     * This directory is populated ONCE at app startup and is NEVER modified by
     * [recreateWrappers]. It always points directly to the bundled `.so` libraries,
     * giving the user a guaranteed recovery path if a downloaded runtime breaks
     * the dynamic `usr/bin/` wrappers.
     *
     * Call once from [BinaryManager.init] or [PtyBridge] during app startup.
     */
    fun writeSafeModeWrappers(context: Context) {
        val filesDir      = context.filesDir
        val safeBinDir    = File(filesDir, "usr/bin_safe")
        val nativeLibPath = context.applicationInfo.nativeLibraryDir
        val libLinksDir   = File(filesDir, "lib").absolutePath

        safeBinDir.mkdirs()
        Log.i(TAG, "Populating/updating usr/bin_safe/ with bundled wrappers…")

        // node — bundled libnode_bin.so
        writeSafeScript(
            dest    = File(safeBinDir, "node"),
            content = """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
                exec "$nativeLibPath/libnode_bin.so" "${'$'}@"
            """.trimIndent()
        )

        // npm — bundled npm_pkg
        writeSafeScript(
            dest    = File(safeBinDir, "npm"),
            content = """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
                exec "$nativeLibPath/libnode_bin.so" "$filesDir/npm_pkg/bin/npm-cli.js" "${'$'}@"
            """.trimIndent()
        )

        // npx — bundled npm_pkg
        writeSafeScript(
            dest    = File(safeBinDir, "npx"),
            content = """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
                exec "$nativeLibPath/libnode_bin.so" "$filesDir/npm_pkg/bin/npx-cli.js" "${'$'}@"
            """.trimIndent()
        )

        // git — bundled libgit_bin.so
        writeSafeScript(
            dest    = File(safeBinDir, "git"),
            content = """
                #!/system/bin/sh
                export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
                exec "$nativeLibPath/libgit_bin.so" "${'$'}@"
            """.trimIndent()
        )

        // git-remote-http / https — bundled
        val gitRemoteContent = """
            #!/system/bin/sh
            export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
            exec "$nativeLibPath/libgit_remote_http_bin.so" "${'$'}@"
        """.trimIndent()
        writeSafeScript(File(safeBinDir, "git-remote-http"),  gitRemoteContent)
        writeSafeScript(File(safeBinDir, "git-remote-https"), gitRemoteContent)

        Log.i(TAG, "usr/bin_safe/ populated with ${safeBinDir.list()?.size ?: 0} wrappers")
    }

    private fun writeSafeScript(dest: File, content: String) {
        try {
            dest.writeText(content)
            dest.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write safe-mode wrapper ${dest.name}: ${e.message}")
        }
    }
}

