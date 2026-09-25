package com.kodrix.zohaib.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * VersionChecker — verifies which binary is *actually* running on disk.
 *
 * It runs the binary with `--version` and captures the output, rather than
 * trusting what SharedPreferences or download metadata says.
 *
 * Trigger this ONLY:
 *   1. After a new version has been fully downloaded + extracted.
 *   2. When the user switches the active version (flicks the "Use" switch).
 *
 * It does NOT run on every app start and does NOT poll.
 */
object VersionChecker {

    private const val TAG = "VersionChecker"
    // Generous: a first start of e.g. rustc maps a ~100 MB libLLVM from flash.
    private const val TIMEOUT_SECONDS = 30L

    /** Maps tool name → verified version string (e.g. "node" → "v22.0.0"). */
    private val _verifiedVersions = MutableStateFlow<Map<String, VerifiedVersion>>(emptyMap())
    val verifiedVersions = _verifiedVersions.asStateFlow()

    data class VerifiedVersion(
        val tool: String,
        val version: String,       // e.g. "v22.0.0" or "Unknown"
        val isVerified: Boolean,   // false when the binary failed to run
        val errorReason: String? = null
    )

    /**
     * Verify the version of [tool] by executing [binaryPath] --version.
     *
     * Must be called from a coroutine. Switches to Dispatchers.IO internally.
     *
     * @param tool            e.g. "node"
     * @param expectedVersion optional version string we expect (e.g. "26.2.0")
     * @param binaryPath      absolute path to the binary file
     */
    suspend fun check(
        tool: String,
        expectedVersion: String? = null,
        binaryPath: String,
        context: Context,
        args: List<String> = listOf("--version"),
        extraEnv: Map<String, String> = emptyMap(),
    ): VerifiedVersion =
        withContext(Dispatchers.IO) {
            val binary = File(binaryPath)
            val fallbackVersion = expectedVersion ?: "Unknown"

            if (!binary.exists()) {
                Log.w(TAG, "[$tool] Binary not found at $binaryPath")
                return@withContext emit(tool, fallbackVersion, false, "Not Found")
            }

            if (!binary.canExecute()) {
                Log.w(TAG, "[$tool] Binary not executable at $binaryPath — setting +x")
                binary.setExecutable(true)
            }

            try {
                val process = if (com.kodrix.zohaib.runtime.RuntimeExec.isAppData(context, binary)) {
                    // Downloaded runtime: Android won't exec it directly, so start it the
                    // same way its terminal wrapper does (via the system linker).
                    val installDir = binary.parentFile?.parentFile ?: binary.parentFile!!
                    val argv = com.kodrix.zohaib.runtime.RuntimeExec.command(context, binary, args)
                    ProcessBuilder(argv).redirectErrorStream(true).apply {
                        environment().putAll(com.kodrix.zohaib.runtime.RuntimeExec.environment(context, installDir, binary, extraEnv))
                    }.start()
                } else {
                    // Bundled binary in nativeLibraryDir. Android's linker ignores
                    // LD_LIBRARY_PATH set directly on a ProcessBuilder child, so set it
                    // inside a shell before exec-ing the binary.
                    val customLibDir = File(binary.parentFile?.parentFile, "lib")
                    val ldPath = buildString {
                        if (customLibDir.exists() && customLibDir.isDirectory) {
                            append(customLibDir.absolutePath).append(":")
                        }
                        append(File(context.filesDir, "lib").absolutePath)
                    }
                    val quotedArgs = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
                    val shellCmd = "LD_LIBRARY_PATH=\"$ldPath\" OPENSSL_CONF=/dev/null \"$binaryPath\" $quotedArgs"
                    ProcessBuilder("/system/bin/sh", "-c", shellCmd).redirectErrorStream(true).start()
                }

                val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val output = process.inputStream.bufferedReader().readText().trim()

                if (!finished) {
                    process.destroyForcibly()
                    Log.e(TAG, "[$tool] --version timed out after ${TIMEOUT_SECONDS}s")
                    return@withContext emit(tool, fallbackVersion, false, "Timed out after ${TIMEOUT_SECONDS}s — the runtime started but never answered.")
                }

                val exitCode = process.exitValue()
                if (exitCode != 0 || output.isBlank()) {
                    Log.e(TAG, "[$tool] --version exited $exitCode, output='$output'")
                    return@withContext emit(tool, fallbackVersion, false, explain(output, exitCode))
                }

                // Node outputs "v22.0.0", git outputs "git version 2.34.0" — normalise
                val version = when (tool) {
                    "git" -> output.removePrefix("git version ").lines().first().trim()
                    "python" -> output.removePrefix("Python ").lines().first().trim()
                    else  -> output.lines().first().trim()
                }

                Log.i(TAG, "[$tool] Verified version: $version")
                emit(tool, version, true)
            } catch (e: Exception) {
                Log.e(TAG, "[$tool] Failed to run --version", e)
                val rawMsg = e.message ?: e.javaClass.simpleName
                val cleanMsg = if (rawMsg.contains("error=")) {
                    rawMsg.substringAfter("error=").substringAfter(", ").trim()
                } else rawMsg
                emit(tool, fallbackVersion, false, explain(cleanMsg, -1))
            }
        }

    /**
     * Turns raw failure output into a sentence a user can act on, keeping the original
     * text after it for diagnosis (the UI shows the whole thing, not a 20-char stub).
     */
    fun explain(output: String, exitCode: Int): String {
        val raw = output.trim().ifEmpty { "exit code $exitCode" }
        val hint = when {
            raw.contains("Permission denied", ignoreCase = true) || raw.contains("can't execute", ignoreCase = true) ->
                "Android blocked running this download directly."
            raw.contains("not found", ignoreCase = true) && raw.contains("library", ignoreCase = true) ->
                "A library this runtime needs is missing — try reinstalling it."
            raw.contains("No such file", ignoreCase = true) ->
                "A file this runtime needs is missing — try reinstalling it."
            raw.contains("Exec format error", ignoreCase = true) || raw.contains("ENOEXEC") ->
                "This download is built for a different processor type than your device."
            else -> "The runtime didn't start."
        }
        return "$hint\n$raw"
    }

    /**
     * Clears the verified version for a tool (e.g. when a switch happens and
     * we want the UI to show "Checking…" while verification runs).
     */
    fun clearVerified(tool: String) {
        _verifiedVersions.value = _verifiedVersions.value - tool
    }

    private fun emit(tool: String, version: String, isVerified: Boolean, errorReason: String? = null): VerifiedVersion {
        val result = VerifiedVersion(tool, version, isVerified, errorReason)
        _verifiedVersions.value = _verifiedVersions.value + (tool to result)
        return result
    }
}
