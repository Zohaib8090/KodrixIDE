package com.kodrix.zohaib.runtime

import android.content.Context
import java.io.File

/**
 * Builds the command line and environment for running a *downloaded* runtime.
 *
 * Android 10+ refuses to execve() files in the app's data dir when targetSdk >= 29
 * (SELinux denies execute_no_trans), so a downloaded ELF such as
 * `files/versions/node/26.2.0/bin/node` fails with "Permission denied". The system
 * linker is still allowed to map such files, so they are started as
 * `/system/bin/linker64 <elf> args…`. libkodrix_exec.so is preloaded so anything the
 * program itself spawns gets the same treatment, `/proc/self/exe` reports the real
 * binary, and Termux's compiled-in `/data/data/com.termux/files/usr` resolves to the
 * install dir (KODRIX_USR).
 *
 * Binaries shipped inside the APK (nativeLibraryDir) are executable normally and are
 * passed through unchanged.
 */
object RuntimeExec {
    const val SHIM_LIB = "libkodrix_exec.so"

    fun isElf(file: File): Boolean = readHead(file, 4)?.let {
        it.size == 4 && it[0] == 0x7f.toByte() && it[1] == 'E'.code.toByte() && it[2] == 'L'.code.toByte() && it[3] == 'F'.code.toByte()
    } ?: false

    /** /system/bin/linker64 for 64-bit ELFs, /system/bin/linker for 32-bit. */
    fun linkerFor(elf: File): String {
        val head = readHead(elf, 5)
        val is64 = head == null || head.size < 5 || head[4].toInt() == 2
        return if (is64) "/system/bin/linker64" else "/system/bin/linker"
    }

    /** Interpreter and optional single argument from a `#!` line, or null. */
    fun shebang(file: File): Pair<String, String?>? {
        val head = readHead(file, 256) ?: return null
        if (head.size < 3 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return null
        val line = String(head, Charsets.ISO_8859_1).substring(2).substringBefore('\n').trim()
        if (line.isEmpty()) return null
        val interp = line.substringBefore(' ').substringBefore('\t')
        val arg = line.removePrefix(interp).trim().ifEmpty { null }
        return interp to arg
    }

    private fun appDataDirs(context: Context): List<String> =
        listOf(context.applicationInfo.dataDir, "/data/data/${context.packageName}").distinct()

    /** True when [file] lives in app-writable storage and so can't be exec'd directly. */
    fun isAppData(context: Context, file: File): Boolean {
        val path = file.absolutePath
        return appDataDirs(context).any { path == it || path.startsWith("$it/") }
    }

    /**
     * Full argv for running [binary] with [args]. ELFs in app data go through the
     * linker; scripts in app data are run by their interpreter, itself resolved the
     * same way. Everything else is returned as-is.
     */
    fun command(context: Context, binary: File, args: List<String>, depth: Int = 0): List<String> {
        if (!isAppData(context, binary) || depth > 3) return listOf(binary.absolutePath) + args
        if (isElf(binary)) return listOf(linkerFor(binary), binary.absolutePath) + args
        val (rawInterp, interpArg) = shebang(binary) ?: return listOf(binary.absolutePath) + args
        // Android has no /usr; its env lives in /system/bin.
        val interp = if (rawInterp == "/usr/bin/env") "/system/bin/env" else rawInterp
        val interpFile = File(interp)
        val rest = listOfNotNull(interpArg) + binary.absolutePath + args
        return command(context, interpFile, rest, depth + 1)
    }

    /** The ELF the process will actually be, for KODRIX_EXE / `/proc/self/exe`. */
    private fun effectiveElf(context: Context, binary: File, depth: Int = 0): File? {
        if (!isAppData(context, binary) || depth > 3) return null
        if (isElf(binary)) return binary
        val (interp, _) = shebang(binary) ?: return null
        return effectiveElf(context, File(interp), depth + 1)
    }

    /**
     * Environment for running something from [installDir]: its own bin/ and lib/ first,
     * the exec shim, and the Termux prefix remap. [extra] (from the runtime's manifest)
     * wins over the defaults; `${install}` is expanded and relative values are resolved
     * against [installDir].
     */
    fun environment(
        context: Context,
        installDir: File,
        binary: File? = null,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val filesDir = context.filesDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val shim = File(nativeLibDir, SHIM_LIB)
        val env = LinkedHashMap<String, String>()
        env["HOME"] = filesDir
        env["TMPDIR"] = File(filesDir, "tmp").apply { mkdirs() }.absolutePath
        env["PREFIX"] = installDir.absolutePath
        env["PATH"] = listOf(
            "${installDir.absolutePath}/bin", "$filesDir/usr/bin", "$filesDir/bin", nativeLibDir, "/system/bin", "/system/xbin",
        ).joinToString(":")
        env["LD_LIBRARY_PATH"] = "${installDir.absolutePath}/lib:$filesDir/lib"
        env["SSL_CERT_FILE"] = File(installDir, "etc/tls/cert.pem").takeIf { it.exists() }?.absolutePath
            ?: "$filesDir/usr/etc/cacert.pem"
        env["KODRIX_USR"] = installDir.absolutePath
        env["KODRIX_APP_DATA"] = appDataDirs(context).joinToString(":")
        env["KODRIX_LOG"] = "$filesDir/kodrix_exec.log"
        if (shim.exists()) env["LD_PRELOAD"] = shim.absolutePath
        binary?.let { effectiveElf(context, it) }?.let { env["KODRIX_EXE"] = it.absolutePath }
        extra.forEach { (k, v) -> env[k] = expand(v, installDir) }
        return env
    }

    /** `${install}` → install dir; a bare relative value is taken relative to it. */
    fun expand(value: String, installDir: File): String {
        val v = value.replace("\${install}", installDir.absolutePath)
        return when {
            v.isEmpty() -> installDir.absolutePath
            v.startsWith("/") || v.contains("://") || v.contains('$') -> v
            v.contains('/') || File(installDir, v).exists() -> File(installDir, v).absolutePath
            else -> v
        }
    }

    /**
     * A `#!/system/bin/sh` wrapper for usr/bin/. The wrapper itself is fine to exec
     * (its interpreter is a system binary); it then starts [target] the permitted way.
     */
    fun wrapperScript(context: Context, target: File, installDir: File, extraEnv: Map<String, String>): String {
        val env = environment(context, installDir, target, extraEnv)
        val argv = command(context, target, emptyList())
        return buildString {
            append("#!/system/bin/sh\n")
            env.forEach { (k, v) ->
                when (k) {
                    // Keep the caller's session values; only put this runtime's bin/ first.
                    "HOME", "TMPDIR" -> Unit
                    "PATH" -> append("export PATH=\"${shellEscape(installDir.absolutePath)}/bin:\$PATH\"\n")
                    else -> append("export $k=\"${shellEscape(v)}\"\n")
                }
            }
            append("exec ")
            append(argv.joinToString(" ") { "\"${shellEscape(it)}\"" })
            append(" \"\$@\"\n")
        }
    }

    private fun shellEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    private fun readHead(file: File, n: Int): ByteArray? = try {
        file.inputStream().use { s ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = s.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        }
    } catch (_: Exception) {
        null
    }
}
