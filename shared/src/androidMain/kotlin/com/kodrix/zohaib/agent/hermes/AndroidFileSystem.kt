package com.kodrix.zohaib.agent.hermes

import java.io.File

/**
 * Android implementation of [FileSystem] rooted at a single base directory.
 * All paths passed in are relative to the base; the base is usually
 * `context.filesDir/agent/`.
 *
 * Implementation notes:
 *   - We canonicalize paths and verify they don't escape the base
 *     (path traversal protection — a malicious skill can't read
 *     `../../../../sdcard/...`).
 *   - We use simple recursive listing; Hermes skills are small (<100 files)
 *     so a walk is cheap.
 */
class AndroidFileSystem(private val base: File) : FileSystem {

    private fun resolve(path: String): File {
        val clean = path.trimStart('/').trimStart('\\')
        val resolved = File(base, clean).canonicalFile
        val baseCanonical = base.canonicalFile
        if (!resolved.absolutePath.startsWith(baseCanonical.absolutePath)) {
            throw SecurityException("Path escapes agent root: $path")
        }
        return resolved
    }

    override fun listFiles(dir: String): List<String> {
        val d = resolve(dir)
        if (!d.exists() || !d.isDirectory) return emptyList()
        return walk(d, base).map { relativePath(it, base) }
    }

    override fun readFile(path: String): String? {
        val f = resolve(path)
        if (!f.exists() || !f.isFile) return null
        return f.readText(Charsets.UTF_8)
    }

    override fun writeFile(path: String, content: String) {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    override fun delete(path: String): Boolean {
        val f = resolve(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    private fun walk(dir: File, root: File): List<File> {
        val out = mutableListOf<File>()
        for (f in dir.listFiles() ?: return out) {
            if (f.isFile) out.add(f)
            else if (f.isDirectory) out.addAll(walk(f, root))
        }
        return out
    }

    private fun relativePath(f: File, root: File): String {
        return f.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
    }
}
