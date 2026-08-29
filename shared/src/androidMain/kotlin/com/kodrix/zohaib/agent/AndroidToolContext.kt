package com.kodrix.zohaib.agent

import android.content.Context
import com.kodrix.zohaib.agent.hermes.AndroidFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Android implementation of [ToolContext]. Wraps the existing Kodrix
 * filesystem layer + spawns shell commands via the bundled node runtime
 * (so commands run inside the same sandbox the terminal uses).
 *
 * v1 scope: file ops + shell. The LSP/git hooks are stubs that
 * the agent can call but currently just return placeholder text; they
 * are safe to add incrementally.
 */
class AndroidToolContext(
    appContext: Context,
    projectRootPath: String,
) : ToolContext {
    override val projectRoot: String = projectRootPath

    private val fs = AndroidFileSystem(File(projectRoot))

    override suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        if (!resolved.exists()) throw RuntimeException("file not found: $path")
        resolved.readText(Charsets.UTF_8)
    }

    override suspend fun writeFile(path: String, content: String) = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        resolved.parentFile?.mkdirs()
        resolved.writeText(content, Charsets.UTF_8)
    }

    override suspend fun listDir(path: String, maxDepth: Int): List<String> = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        if (!resolved.exists()) return@withContext emptyList()
        if (!resolved.isDirectory) return@withContext listOf(resolved.absolutePath)
        val out = mutableListOf<String>()
        walk(resolved, maxDepth, 0, out)
        out
    }

    override suspend fun grep(pattern: String, glob: String?, path: String?): List<String> = withContext(Dispatchers.IO) {
        val root = if (path != null) resolvePath(path) else resolvePath(".")
        val regex = Regex(pattern)
        val out = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                if (glob != null && !f.matchesGlob(glob)) return@forEach
                try {
                    f.useLines { lines ->
                        for ((i, line) in lines.withIndex()) {
                            if (regex.containsMatchIn(line)) {
                                out.add("${f.absolutePath}:${i + 1}:${line.take(200)}")
                            }
                        }
                    }
                } catch (_: Throwable) { /* binary file or permission */ }
            }
        }
        out.take(100)
    }

    override suspend fun runShell(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        // Spawn via /system/bin/sh. The agent's tools run with the app's
        // permissions, so this is constrained to what's accessible from
        // filesDir. For real-world use, route through PtyBridge once the
        // tool-execution pipeline is wired.
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(File(projectRoot))
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        var finished = false
        try {
            finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            return@withContext "(timed out after ${timeoutMs}ms)"
        }
        // Drain stdout (blocking, but bounded by timeout above).
        if (finished) {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { output.append(it).append("\n") }
        }
        if (output.isBlank()) "(no output, exit=${process.exitValue()})" else output.toString().trim()
    }

    override suspend fun runGit(args: List<String>): String = withContext(Dispatchers.IO) {
        val joined = args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        runShell("git $joined", 30_000)
    }

    override suspend fun lspDiagnostics(path: String): String = withContext(Dispatchers.IO) {
        val resolved = resolvePath(path)
        if (!resolved.exists()) return@withContext "no such file: $path"
        "LSP diagnostics not wired yet for $path; use grep to inspect manually"
    }

    override suspend fun lspDefinition(path: String, line: Int, col: Int): String = withContext(Dispatchers.IO) {
        "LSP definition not wired yet for $path:$line:$col"
    }

    override suspend fun askUser(question: String, options: List<String>): String? = null

    private fun resolvePath(path: String): File {
        val p = File(path)
        return if (p.isAbsolute) p else File(projectRoot, path)
    }

    private fun walk(dir: File, maxDepth: Int, depth: Int, out: MutableList<String>) {
        if (depth > maxDepth) return
        for (f in dir.listFiles() ?: return) {
            out.add(f.absolutePath)
            if (f.isDirectory) walk(f, maxDepth, depth + 1, out)
        }
    }

    private fun File.matchesGlob(glob: String): Boolean {
        val rx = Regex("^" + glob.replace(".", "\\.").replace("*", ".*") + "$")
        return rx.matches(name)
    }
}

