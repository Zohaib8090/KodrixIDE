package com.kodrix.zohaib.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxRepoTest {

    private val sample = """
        Package: nodejs
        Version: 22.1.0
        Depends: libc++, openssl (>= 3), zlib | zlib-ng
        Provides: node
        Filename: pool/main/n/nodejs/nodejs_22.1.0_aarch64.deb
        Size: 100
        Installed-Size: 200
        SHA256: aa
        Description: JavaScript runtime
         continuation line

        Package: libc++
        Version: 1
        Filename: pool/main/libc/libc++.deb

        Package: openssl
        Version: 3
        Depends: zlib
        Filename: pool/main/o/openssl.deb

        Package: zlib
        Version: 1.3
        Filename: pool/main/z/zlib.deb

        Package: zlib-dev
        Version: 1.3
        Filename: pool/main/z/zlib-dev.deb
        Description: nodejs headers

        Package: termux-tools
        Version: 1
        Filename: pool/main/t/termux-tools.deb
    """.trimIndent() + "\n"

    @Test fun parsesFieldsAndProvides() {
        val idx = TermuxRepo.parseIndex(sample)
        val n = assertNotNull(idx.packages["nodejs"])
        assertEquals("22.1.0", n.version)
        assertEquals(100L, n.size)
        assertEquals(200L, n.installedSizeKb)
        assertEquals("JavaScript runtime", n.description)
        assertEquals(listOf("libc++"), n.depends[0])
        assertEquals(listOf("openssl"), n.depends[1]) // constraint stripped
        assertEquals(listOf("zlib", "zlib-ng"), n.depends[2])
        assertEquals("nodejs", idx.find("node")?.name) // via Provides
    }

    @Test fun resolveOrdersDependenciesFirstAndSkipsIgnored() {
        val idx = TermuxRepo.parseIndex(sample)
        val order = TermuxRepo.resolve(idx, listOf("nodejs")).map { it.name }
        assertEquals("nodejs", order.last())
        assertTrue(order.indexOf("zlib") < order.indexOf("openssl"))
        assertEquals(order.size, order.toSet().size)
        assertTrue(TermuxRepo.resolve(idx, listOf("termux-tools")).isEmpty())
    }

    @Test fun resolveReportsMissing() {
        val idx = TermuxRepo.parseIndex(sample)
        val e = assertFailsWith<TermuxRepo.ResolveException> { TermuxRepo.resolve(idx, listOf("nope")) }
        assertTrue("nope" in e.message!!)
    }

    @Test fun searchRanksAndFiltersNoise() {
        val idx = TermuxRepo.parseIndex(sample)
        assertEquals("zlib", idx.search("zlib").first().name)
        assertTrue(idx.search("zlib").none { it.name == "zlib-dev" })
        assertTrue(idx.search("headers").isEmpty()) // only match is a -dev package
        assertTrue(idx.search("termux").isEmpty())
        assertTrue(idx.search("  ").isEmpty())
    }

    @Test fun archMapping() {
        assertEquals("aarch64", TermuxRepo.termuxArch("arm64-v8a"))
        assertEquals("arm", TermuxRepo.termuxArch("armeabi-v7a"))
        assertEquals("x86_64", TermuxRepo.termuxArch("x86_64"))
        assertEquals("i686", TermuxRepo.termuxArch("x86"))
    }

    @Test fun shebangRewrites() {
        val dir = Files.createTempDirectory("kx").toFile()
        File(dir, "bin").mkdirs()
        File(dir, "bin/python3").writeText("")
        val p = TermuxRepo.TERMUX_PREFIX_ABS
        assertEquals("#!${dir.path}/bin/python3", TermuxRepo.fixShebang("#!$p/bin/python3", dir))
        assertEquals("#!${dir.path}/bin/python3", TermuxRepo.fixShebang("#!/usr/bin/env python3", dir))
        assertEquals("#!/system/bin/env ruby", TermuxRepo.fixShebang("#!/usr/bin/env ruby", dir))
        assertEquals("#!/system/bin/sh -e", TermuxRepo.fixShebang("#!$p/bin/sh -e", dir))
        assertNull(TermuxRepo.fixShebang("#!/system/bin/sh", dir))
        dir.deleteRecursively()
    }

    // ── .deb extraction ──────────────────────────────────────────────────────

    private fun tarEntry(name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", mode: Int = 0b110_100_100): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name); put(100, "%07o".format(mode)); put(124, "%011o".format(body.size))
        h[156] = type.code.toByte(); put(157, link); put(257, "ustar")
        val out = ByteArrayOutputStream(); out.write(h); out.write(body)
        out.write(ByteArray((512 - body.size % 512) % 512))
        return out.toByteArray()
    }

    private fun makeDeb(dest: File, tar: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray())
        fun member(name: String, data: ByteArray) {
            out.write("%-16s%-12s%-6s%-6s%-8s%-10d`\n".format(name, "0", "0", "0", "100644", data.size).toByteArray())
            out.write(data); if (data.size % 2 == 1) out.write('\n'.code)
        }
        member("debian-binary", "2.0\n".toByteArray())
        member("data.tar", tar)
        dest.writeBytes(out.toByteArray()); return dest
    }

    private val usr = TermuxRepo.TERMUX_USR

    @Test fun extractsFilesLinksAndSkipsOutsideUsr() {
        val work = Files.createTempDirectory("kx").toFile()
        val tar = ByteArrayOutputStream().apply {
            write(tarEntry("./${usr}bin/", '5'))
            write(tarEntry("./${usr}bin/hello", '0', "echo hi".toByteArray(), mode = 0b111_101_101))
            write(tarEntry("./${usr}bin/hi", '2', link = "hello"))
            write(tarEntry("./${usr}bin/abs", '2', link = "${TermuxRepo.TERMUX_PREFIX_ABS}/bin/hello"))
            write(tarEntry("./etc/other", '0', "x".toByteArray()))
            write(ByteArray(1024))
        }.toByteArray()
        val deb = makeDeb(File(work, "a.deb"), tar)
        val dest = File(work, "out")
        TermuxRepo.extractDeb(deb, dest)
        assertEquals("echo hi", File(dest, "bin/hello").readText())
        assertTrue(File(dest, "bin/hello").canExecute())
        assertEquals("hello", Files.readSymbolicLink(File(dest, "bin/hi").toPath()).toString())
        assertEquals(dest.canonicalPath + "/bin/hello", Files.readSymbolicLink(File(dest, "bin/abs").toPath()).toString())
        assertTrue(!File(dest, "etc").exists())
        work.deleteRecursively()
    }

    @Test fun rejectsNonDebAndTraversal() {
        val work = Files.createTempDirectory("kx").toFile()
        val bad = File(work, "bad.deb").apply { writeText("not a deb at all") }
        assertFailsWith<java.io.IOException> { TermuxRepo.extractDeb(bad, File(work, "o")) }
        val tar = ByteArrayOutputStream().apply {
            write(tarEntry("./${usr}../../evil", '0', "x".toByteArray())); write(ByteArray(1024))
        }.toByteArray()
        TermuxRepo.extractDeb(makeDeb(File(work, "t.deb"), tar), File(work, "o2"))
        assertTrue(!File(work, "evil").exists())
        work.deleteRecursively()
    }

    @Test fun sha256Helper() {
        // sanity for the digest the downloader verifies against
        val d = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(d.startsWith("ba7816bf"))
    }
}
