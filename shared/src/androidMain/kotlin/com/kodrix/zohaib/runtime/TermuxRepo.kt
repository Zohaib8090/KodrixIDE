package com.kodrix.zohaib.runtime

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Minimal apt client for Termux's package repository: fetches the `Packages` index,
 * resolves dependency closures, downloads SHA-256-verified `.deb`s and extracts them.
 *
 * Deliberately free of Android APIs so it can be compiled and tested on a plain JVM.
 * Termux packages are prebuilt for Android (bionic, `/system/bin/linker64`), so nothing
 * here requires the Termux app to be installed.
 */
object TermuxRepo {

    /** Tried in order. Mirrors sync independently, so an index and the .debs it names
     *  must always come from the same mirror. */
    val DEFAULT_MIRRORS = listOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux/termux-main",
        "https://mirror.mwt.me/termux/main",
        "https://mirror.accum.se/mirror/termux.dev/termux-main",
        "https://termux.librehat.com/apt/termux-main",
    )

    /** Packages that belong to the Termux app itself and make no sense inside Kodrix. */
    val IGNORED_PACKAGES = setOf(
        "termux-tools", "termux-exec", "termux-keyring", "termux-am", "termux-am-socket",
        "termux-core", "termux-licenses", "apt", "dpkg",
    )

    private val NOISE_SUFFIXES = listOf("-static", "-dbg", "-doc", "-dev")

    /** Everything a package extracts lives under this prefix inside `data.tar`. */
    const val TERMUX_USR = "data/data/com.termux/files/usr/"
    const val TERMUX_PREFIX_ABS = "/data/data/com.termux/files/usr"

    data class Pkg(
        val name: String,
        val version: String,
        /** Each entry is a list of alternatives ("a | b"), version constraints stripped. */
        val depends: List<List<String>>,
        val provides: List<String>,
        val filename: String,
        val size: Long,
        val installedSizeKb: Long,
        val sha256: String,
        /** One-line summary from the index. */
        val description: String = "",
    )

    class Index(val packages: Map<String, Pkg>) {
        private val providers: Map<String, List<String>> = buildMap<String, MutableList<String>> {
            packages.values.forEach { p -> p.provides.forEach { getOrPut(it) { mutableListOf() }.add(p.name) } }
        }

        fun find(name: String): Pkg? = packages[name] ?: providers[name]?.firstOrNull()?.let { packages[it] }

        /**
         * Packages matching [query], best first: exact name, then name prefix, then name
         * contains, then description contains. Development headers, debug symbols and
         * static libraries are left out, since nobody installs those on purpose.
         */
        fun search(query: String, limit: Int = 40): List<Pkg> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            fun rank(p: Pkg): Int {
                val n = p.name.lowercase()
                return when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    p.description.lowercase().contains(q) -> 3
                    else -> -1
                }
            }
            return packages.values.asSequence()
                .filter { p -> NOISE_SUFFIXES.none { p.name.endsWith(it) } && p.name !in IGNORED_PACKAGES }
                .map { it to rank(it) }
                .filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { it.first.name.length }, { it.first.name }))
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    class ResolveException(message: String) : Exception(message)

    fun termuxArch(androidAbi: String): String = when {
        androidAbi.startsWith("arm64") -> "aarch64"
        androidAbi.startsWith("armeabi") -> "arm"
        androidAbi == "x86_64" -> "x86_64"
        androidAbi == "x86" -> "i686"
        else -> "aarch64"
    }

    fun indexUrl(mirror: String, arch: String) = "$mirror/dists/stable/main/binary-$arch/Packages"

    // ── Index ────────────────────────────────────────────────────────────────

    fun parseIndex(text: String): Index {
        val result = HashMap<String, Pkg>(4096)
        val fields = HashMap<String, String>()
        fun flush() {
            val name = fields["Package"]
            val filename = fields["Filename"]
            if (name != null && filename != null) {
                result[name] = Pkg(
                    name = name,
                    version = fields["Version"].orEmpty(),
                    depends = parseDepends(listOfNotNull(fields["Pre-Depends"], fields["Depends"]).joinToString(",")),
                    provides = fields["Provides"].orEmpty().split(',').map { stripConstraint(it) }.filter { it.isNotEmpty() },
                    filename = filename,
                    size = fields["Size"]?.toLongOrNull() ?: 0L,
                    installedSizeKb = fields["Installed-Size"]?.toLongOrNull() ?: 0L,
                    sha256 = fields["SHA256"].orEmpty(),
                    description = fields["Description"].orEmpty(),
                )
            }
            fields.clear()
        }
        text.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> flush()
                line[0] == ' ' || line[0] == '\t' -> Unit // continuation lines (descriptions)
                else -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) fields[line.substring(0, colon)] = line.substring(colon + 1).trim()
                }
            }
        }
        flush()
        return Index(result)
    }

    private fun stripConstraint(s: String) = s.substringBefore('(').trim()

    private fun parseDepends(s: String): List<List<String>> =
        s.split(',').map { alt -> alt.split('|').map { stripConstraint(it) }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }

    /**
     * Returns [roots] plus their transitive dependencies, dependencies first, so
     * extracting in order never needs a later package. Throws if anything is missing.
     */
    fun resolve(index: Index, roots: List<String>, ignore: Set<String> = IGNORED_PACKAGES): List<Pkg> {
        val order = ArrayList<Pkg>()
        val seen = HashSet<String>()
        val missing = LinkedHashSet<String>()
        fun visit(requested: String) {
            if (requested in ignore) return
            val pkg = index.find(requested) ?: run { missing += requested; return }
            if (pkg.name in ignore || !seen.add(pkg.name)) return
            for (alternatives in pkg.depends) {
                val pick = alternatives.firstOrNull { it in ignore || index.find(it) != null } ?: alternatives.first()
                visit(pick)
            }
            order += pkg
        }
        roots.forEach(::visit)
        if (missing.isNotEmpty()) throw ResolveException("Not in the Termux repository: ${missing.joinToString()}")
        return order
    }

    // ── Network ──────────────────────────────────────────────────────────────

    fun openWithRedirects(url: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "KodrixIDE")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location for $current")
                conn.disconnect()
                current = URL(URL(current), location).toString()
            } else if (code != 200) {
                conn.disconnect()
                throw IOException("HTTP $code for $current")
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects for $url")
    }

    fun fetchText(url: String): String = openWithRedirects(url).inputStream.bufferedReader().use { it.readText() }

    /**
     * Downloads [url] to [dest], verifying [sha256] when non-empty. [onProgress] receives
     * bytes written so far. On any failure the partial file is removed.
     */
    fun download(url: String, dest: File, sha256: String, onProgress: (Long) -> Unit = {}) {
        dest.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            openWithRedirects(url).inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
            if (sha256.isNotEmpty()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw SecurityException("Checksum mismatch for ${dest.name}: expected $sha256, got $actual")
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    /**
     * Extracts the Termux `usr/` contents of [deb] into [destDir], so
     * `.../com.termux/files/usr/bin/x` lands at `destDir/bin/x`. Files outside `usr/`
     * are skipped. Returns the number of entries written.
     */
    fun extractDeb(deb: File, destDir: File): Int {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        BufferedInputStream(deb.inputStream(), 64 * 1024).use { raw ->
            val magic = readExactly(raw, 8)
            if (String(magic, Charsets.US_ASCII) != "!<arch>\n") throw IOException("${deb.name} is not a .deb (ar) file")
            while (true) {
                val header = try { readExactly(raw, 60) } catch (_: EOFException) {
                    throw IOException("No data.tar member in ${deb.name}")
                }
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
                if (name.startsWith("data.tar")) {
                    val member = BoundedInputStream(raw, size)
                    val decompressed: InputStream = when {
                        name.endsWith(".xz") -> XZInputStream(member)
                        name.endsWith(".gz") -> GZIPInputStream(member)
                        name == "data.tar" -> member
                        else -> throw IOException("Unsupported compression in ${deb.name}: $name")
                    }
                    return extractTar(decompressed, destCanonical)
                }
                skipExactly(raw, size + (size and 1L))
            }
        }
    }

    private fun extractTar(input: InputStream, destDir: File): Int {
        val header = ByteArray(512)
        var count = 0
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val padded = (size + 511) / 512 * 512
            val rawName = run {
                val n = cString(header, 0, 100)
                val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") cString(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            when (type) {
                'L' -> { longName = readString(input, size).trimEnd('\u0000'); skipExactly(input, padded - size); continue }
                'K' -> { longLink = readString(input, size).trimEnd('\u0000'); skipExactly(input, padded - size); continue }
                'x' -> {
                    val pax = parsePax(readString(input, size))
                    pax["path"]?.let { paxPath = it }
                    pax["linkpath"]?.let { paxLink = it }
                    skipExactly(input, padded - size)
                    continue
                }
                'g' -> { skipExactly(input, padded); continue }
            }
            val name = paxPath ?: longName ?: rawName
            val link = paxLink ?: longLink ?: cString(header, 157, 100)
            longName = null; longLink = null; paxPath = null; paxLink = null

            val rel = relativeUsrPath(name)
            if (rel == null) {
                skipExactly(input, padded)
                continue
            }
            val out = File(destDir, rel)
            if (!out.canonicalFile.path.startsWith(destDir.path + File.separator) && out.canonicalFile != destDir) {
                throw SecurityException("Blocked path escaping install dir: $name")
            }
            val mode = parseOctal(header, 100, 8).toInt()
            when (type) {
                '5' -> out.mkdirs()
                '2' -> {
                    out.parentFile?.mkdirs()
                    Files.deleteIfExists(out.toPath())
                    Files.createSymbolicLink(out.toPath(), Paths.get(remapLinkTarget(link, destDir)))
                }
                '1' -> {
                    val targetRel = relativeUsrPath(link)
                    val source = targetRel?.let { File(destDir, it) }
                    if (source != null && source.isFile) {
                        out.parentFile?.mkdirs()
                        source.copyTo(out, overwrite = true)
                        if (source.canExecute()) out.setExecutable(true, false)
                    }
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    Files.deleteIfExists(out.toPath())
                    out.outputStream().use { copyExactly(input, it, size) }
                    skipExactly(input, padded - size)
                    if (mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                    count++
                    continue
                }
            }
            skipExactly(input, padded)
            count++
        }
        return count
    }

    /** "./data/data/com.termux/files/usr/bin/x" -> "bin/x"; null for anything outside usr/. */
    private fun relativeUsrPath(tarPath: String): String? {
        val p = tarPath.removePrefix("./").removePrefix("/")
        if (!p.startsWith(TERMUX_USR)) return null
        val rel = p.removePrefix(TERMUX_USR).trimEnd('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        return rel
    }

    /** Absolute symlinks into Termux's prefix are pointed at the install dir instead. */
    private fun remapLinkTarget(target: String, destDir: File): String =
        if (target.startsWith(TERMUX_PREFIX_ABS)) destDir.path + target.removePrefix(TERMUX_PREFIX_ABS) else target

    /**
     * Rewrites `#!/data/data/com.termux/files/usr/...` shebangs in [installDir]/bin (and
     * libexec) to the real install path. Text-only, so the longer path is safe here.
     */
    fun rewriteShebangs(installDir: File) {
        val dirs = listOf(File(installDir, "bin"), File(installDir, "libexec"))
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val root = installDir.canonicalFile
            // Commands are often links into lib/ (bin/npm → lib/node_modules/npm/bin/npm-cli.js),
            // so fix the real file a link points at too, as long as it's inside the install.
            dir.walkTopDown().mapNotNull { f ->
                if (!Files.isSymbolicLink(f.toPath())) f.takeIf { it.isFile }
                else runCatching { f.canonicalFile }.getOrNull()
                    ?.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
            }.distinct().filter { it.length() in 3..(2L * 1024 * 1024) }
                .forEach { f ->
                    val head = f.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
                    if (head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return@forEach
                    val text = f.readText(Charsets.ISO_8859_1)
                    val nl = text.indexOf('\n').let { if (it < 0) text.length else it }
                    val first = text.substring(0, nl)
                    val fixed = fixShebang(first, installDir) ?: return@forEach
                    val wasExec = f.canExecute()
                    f.writeText(fixed + text.substring(nl), Charsets.ISO_8859_1)
                    if (wasExec) f.setExecutable(true, false)
                }
        }
    }

    /**
     * Rewrites one `#!` line so it points at something that exists on this device, or
     * returns null to leave it alone:
     *  - Termux's prefix becomes [installDir];
     *  - `/usr/bin/env prog` (no /usr on Android) runs prog from [installDir]/bin when it
     *    is there, otherwise through Android's own `/system/bin/env`;
     *  - a shell (sh/bash/dash) that the install doesn't include falls back to `/system/bin/sh`.
     */
    internal fun fixShebang(line: String, installDir: File): String? {
        val body = line.removePrefix("#!").trim()
        val interp = body.substringBefore(' ').substringBefore('\t')
        val rest = body.removePrefix(interp).trim()
        val envLike = interp == "/usr/bin/env" || interp == "$TERMUX_PREFIX_ABS/bin/env"
        if (envLike && rest.isNotEmpty() && !rest.startsWith("-")) {
            val prog = rest.substringBefore(' ')
            val args = rest.removePrefix(prog).trim()
            val local = File(installDir, "bin/$prog")
            return if (local.exists()) "#!${local.path}" + (if (args.isNotEmpty()) " $args" else "")
            else "#!/system/bin/env $rest"
        }
        if (!interp.startsWith(TERMUX_PREFIX_ABS)) return null
        val mapped = File(installDir.path + interp.removePrefix(TERMUX_PREFIX_ABS))
        val name = mapped.name
        val target = if (!mapped.exists() && (name == "sh" || name == "bash" || name == "dash")) "/system/bin/sh" else mapped.path
        return "#!$target" + (if (rest.isNotEmpty()) " $rest" else "")
    }

    // ── Stream helpers ───────────────────────────────────────────────────────

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        if (!readFully(input, buf)) throw EOFException()
        return buf
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("Truncated archive")
            }
            off += n
        }
        return true
    }

    private fun skipExactly(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("Truncated archive")
            left -= r
        }
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("Truncated archive")
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun readString(input: InputStream, n: Long): String {
        val buf = ByteArray(n.toInt())
        if (!readFully(input, buf)) throw EOFException()
        return String(buf, Charsets.UTF_8)
    }

    private fun cString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 encoding for very large files.
        if (buf[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (buf[i].toLong() and 0xff)
            return v
        }
        val s = cString(buf, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            val record = text.substring(sp + 1, minOf(text.length, i + len)).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    /** Limits reads to one `ar` member so decompressors can't run past it. */
    private class BoundedInputStream(private val inner: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = inner.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}
