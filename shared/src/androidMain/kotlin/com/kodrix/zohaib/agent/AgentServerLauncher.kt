package com.kodrix.zohaib.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal on-demand spawner for the Kodrix agent server (Node + Fastify).
 *
 * The server is a separate child process — NOT a library loaded into the
 * APK. This avoids the JNI/CPU/RAM cost of bundling Node.js into the app
 * and keeps the agent server swappable independently of the IDE build.
 *
 * Lifecycle:
 *   - start() : extracts the bundled server from `assets/agentServer/`
 *               into filesDir/agentServer/ on first run, spawns
 *               `node server.bundled.js`, waits for /healthz.
 *   - healthCheck() : GETs /healthz; returns true if the server responds.
 *   - stop() : kills the child process.
 *   - The child has its OWN 12-minute idle-timer self-shutdown, so even
 *     if `stop()` is never called (e.g. IDE crash), the server will exit.
 *
 * The server ships in the APK as a single self-contained `server.bundled.js`
 * file (esbuild-bundled, ~1.5 MB, all Node deps inlined). It is invoked
 * via the same `libnode_bin.so` that Kodrix's terminal already uses —
 * located through `applicationInfo.nativeLibraryDir` (same as PtyBridge).
 */
class AgentServerLauncher(
    private val context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 3080,
    private val idleTimeoutMs: Long = 12L * 60L * 1000L,
) {
    private val installDir: File = File(context.filesDir, "agentServer")
    val baseUrl: String get() = "http://$host:$port"

    @Volatile private var process: Process? = null

    /**
     * Start the server if it isn't already running, and wait for it to
     * respond on /healthz. Returns true on success.
     */
    fun start(): Boolean {
        // Already running (in this process) — just check health.
        if (process?.isAlive == true) return healthCheck()

        try {
            ensureInstalled()
        } catch (e: Exception) {
            Log.e(TAG, "failed to extract agent server from assets", e)
            return false
        }

        val script = File(installDir, "server.bundled.js")
        if (!script.exists()) {
            Log.w(TAG, "agent server script not found at $script")
            return false
        }

        return try {
            val nativeLibPath = context.applicationInfo.nativeLibraryDir
            val nodeBin = File(nativeLibPath, "libnode_bin.so").absolutePath
            // Use the same `$usrBinDir/node` symlink the terminal uses if
            // PtyBridge has already set it up. Otherwise fall back to the
            // direct libnode_bin.so path. The symlink path is preferred
            // because that's what `npm` and other tools expect.
            val nodeCmd = pickNodeCommand(nativeLibPath)

            val pb = ProcessBuilder(
                nodeCmd, "server.bundled.js",
                "--port", port.toString(),
                "--host", host,
                "--idle-timeout-ms", idleTimeoutMs.toString(),
            )
            pb.directory(installDir)
            pb.redirectErrorStream(true)
            // Inherit env so the server can read DEEPSEEK_API_KEY, etc.
            // Also ensure Node can find any native modules the bundle needs.
            pb.environment().putAll(System.getenv())
            pb.environment()["NODE_PATH"] = installDir.absolutePath

            process = pb.start()

            // Stream the child's stdout to logcat under the kodrix-agent tag
            // so we can see boot errors, provider test failures, etc.
            Thread({
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { Log.i(TAG, it) }
                }
            }, "kodrix-agent-stdout").apply { isDaemon = true }.start()

            waitForHealthy(timeoutMs = 10_000)
        } catch (e: Exception) {
            Log.e(TAG, "failed to start agent server", e)
            false
        }
    }

    /**
     * Extract the bundled server from `assets/agentServer/` into
     * `filesDir/agentServer/` on first run. Subsequent runs are a no-op
     * because we check for the file's existence.
     *
     * If a future build ships a newer `server.bundled.js`, you can clear
     * the file via `adb shell run-as com.kodrix.zohaib rm -rf files/agentServer`.
     */
    private fun ensureInstalled() {
        val dest = File(installDir, "server.bundled.js")
        if (dest.exists() && dest.length() > 0) return
        installDir.mkdirs()
        context.assets.open("agentServer/server.bundled.js").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        // Make executable (defensive — some Android filesystems need it).
        dest.setExecutable(true, false)
        Log.i(TAG, "extracted agent server to ${dest.absolutePath} (${dest.length()} bytes)")
    }

    /**
     * Prefer the PtyBridge symlink at `$filesDir/usr/bin/node` (already
     * set up by the terminal). Fall back to the raw libnode_bin.so in
     * `applicationInfo.nativeLibraryDir`.
     */
    private fun pickNodeCommand(nativeLibPath: String): String {
        val symlinked = File(context.filesDir, "usr/bin/node")
        return if (symlinked.exists()) symlinked.absolutePath
        else File(nativeLibPath, "libnode_bin.so").absolutePath
    }

    /** GET /healthz. Returns true if the server responds with 200. */
    fun healthCheck(): Boolean = try {
        val conn = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 1_000
        conn.requestMethod = "GET"
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (e: Exception) {
        Log.d(TAG, "healthCheck failed: ${e.message}")
        false
    }

    /** Kill the child process. The server's own idle-timer is a backstop. */
    fun stop() {
        process?.let { p ->
            try {
                p.destroy()
                if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "stop() error", e)
            }
        }
        process = null
    }

    private fun waitForHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (healthCheck()) {
                Log.i(TAG, "agent server is healthy at $baseUrl")
                return true
            }
            Thread.sleep(200)
        }
        Log.w(TAG, "agent server did not become healthy within ${timeoutMs}ms")
        return false
    }

    companion object {
        private const val TAG = "kodrix-agent"
    }
}

