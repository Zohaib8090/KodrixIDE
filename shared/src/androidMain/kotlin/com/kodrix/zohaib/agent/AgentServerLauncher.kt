package com.kodrix.zohaib.agent

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
 *   - start() : spawns `node dist/server.js` from the bundled agent server
 *               directory, returns the base URL (e.g. http://127.0.0.1:3080).
 *   - healthCheck() : GETs /healthz; returns true if the server responds.
 *   - stop() : kills the child process.
 *   - The child has its OWN 12-minute idle-timer self-shutdown, so even
 *     if `stop()` is never called (e.g. IDE crash), the server will exit.
 *
 * Where to put the agent server on the device:
 *   This class looks for the server in the IDE's `filesDir/agentServer/`
 *   by default. The actual delivery mechanism (bundled assets, sideloaded
 *   from the marketplace, downloaded on first run) is a separate concern;
 *   this class only assumes a `server.js` lives at the configured path.
 */
class AgentServerLauncher(
    private val installDir: File,
    private val host: String = "127.0.0.1",
    private val port: Int = 3080,
    private val idleTimeoutMs: Long = 12L * 60L * 1000L,
) {
    val baseUrl: String get() = "http://$host:$port"

    @Volatile private var process: Process? = null

    /**
     * Start the server if it isn't already running, and wait for it to
     * respond on /healthz. Returns true on success.
     */
    fun start(): Boolean {
        // Already running (in this process) — just check health.
        if (process?.isAlive == true) return healthCheck()

        val script = File(installDir, "dist/server.js")
        if (!script.exists()) {
            Log.w(TAG, "agent server script not found at $script; run `npm run build` inside $installDir")
            return false
        }

        return try {
            val pb = ProcessBuilder(
                "node", "dist/server.js",
                "--port", port.toString(),
                "--host", host,
                "--idle-timeout-ms", idleTimeoutMs.toString(),
            )
            pb.directory(installDir)
            pb.redirectErrorStream(true)
            // Inherit env so the server can read DEEPSEEK_API_KEY, etc.
            pb.environment().putAll(System.getenv())

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
