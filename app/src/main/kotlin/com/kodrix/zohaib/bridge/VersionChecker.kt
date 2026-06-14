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
    private const val TIMEOUT_SECONDS = 5L

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
    suspend fun check(tool: String, expectedVersion: String? = null, binaryPath: String, context: Context): VerifiedVersion =
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
                // Inject critical dynamic library environment variables for Android compatibility.
                // IMPORTANT: Android's Bionic linker strips LD_LIBRARY_PATH set on a ProcessBuilder
                // child process. We work around this by invoking the binary through /system/bin/sh,
                // which sets LD_LIBRARY_PATH in-shell before exec-ing the binary. The linker then
                // reads it from the shell's own environment and honours it.
                val customLibDir = File(binary.parentFile?.parentFile, "lib")
                val ldPath = buildString {
                    if (customLibDir.exists() && customLibDir.isDirectory) {
                        append(customLibDir.absolutePath).append(":")
                    }
                    append(File(context.filesDir, "lib").absolutePath)
                }

                val shellCmd = "LD_LIBRARY_PATH=\"$ldPath\" OPENSSL_CONF=/dev/null \"$binaryPath\" --version"
                val pb = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
                    .redirectErrorStream(true)

                val process = pb.start()

                val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val output = process.inputStream.bufferedReader().readText().trim()

                if (!finished) {
                    process.destroyForcibly()
                    Log.e(TAG, "[$tool] --version timed out after ${TIMEOUT_SECONDS}s")
                    return@withContext emit(tool, fallbackVersion, false, "Timed Out")
                }

                val exitCode = process.exitValue()
                if (exitCode != 0 || output.isBlank()) {
                    Log.e(TAG, "[$tool] --version exited $exitCode, output='$output'")
                    val reason = if (output.isNotBlank()) output.take(20) else "Exit $exitCode"
                    return@withContext emit(tool, fallbackVersion, false, reason)
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
                emit(tool, fallbackVersion, false, cleanMsg)
            }
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
