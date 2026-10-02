package com.kodrix.zohaib.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TermuxRepoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val index = TermuxRepo.parseIndex(
        """
        Package: python
        Version: 3.14.6-1
        Depends: libandroid-support, libexpat (>= 2.0), openssl | libressl
        Filename: pool/main/p/python/python_3.14.6-1_aarch64.deb
        Size: 1000
        SHA256: aa
        Description: Python 3 programming language

        Package: libandroid-support
        Version: 29
        Filename: pool/main/liba/libandroid-support/x.deb
        Size: 10
        Description: Library to support missing Android functionality

        Package: libexpat
        Version: 2.7
        Depends: termux-tools, libandroid-support
        Filename: pool/main/libe/libexpat/x.deb
        Size: 20
        Description: XML parser

        Package: openssl
        Version: 3.5
        Provides: libssl
        Filename: pool/main/o/openssl/x.deb
        Size: 30
        Description: Secure Sockets Layer toolkit

        Package: termux-tools
        Version: 1
        Filename: pool/main/t/termux-tools/x.deb
        Size: 1

        Package: python-pip
        Version: 26
        Depends: python
        Filename: pool/main/p/python-pip/x.deb
        Size: 5
        Description: Pip for Python

        Package: python-dbg
        Version: 3.14
        Filename: pool/main/p/python/dbg.deb
        Size: 5
        Description: Debug symbols

        Package: ruby
        Version: 4.0
        Filename: pool/main/r/ruby/x.deb
        Size: 5
        Description: Dynamic programming language

        Package: mruby
        Version: 3.3
        Filename: pool/main/m/mruby/x.deb
        Size: 5
        Description: Lightweight Ruby

        Package: asciidoctor
        Version: 2
        Filename: pool/main/a/asciidoctor/x.deb
        Size: 5
        Description: Documentation tool written in ruby
        """.trimIndent()
    )

    // ── Index ────────────────────────────────────────────────────────────────

    @Test fun parsesFields() {
        val py = index.find("python")!!
        assertEquals("3.14.6-1", py.version)
        assertEquals(1000L, py.size)
        assertEquals("Python 3 programming language", py.description)
        // version constraints are stripped; "a | b" stays one entry of alternatives
        assertEquals(listOf(listOf("libandroid-support"), listOf("libexpat"), listOf("openssl", "libressl")), py.depends)
    }

    @Test fun findUsesProvides() {
        assertEquals("openssl", index.find("libssl")!!.name)
        assertNull(index.find("nope"))
    }

    @Test fun searchRanksExactThenPrefixThenContainsThenDescription() {
        val names = index.search("ruby").map { it.name }
        assertEquals(listOf("ruby", "mruby", "asciidoctor"), names)
    }

    @Test fun searchHidesNoiseAndBlank() {
        assertFalse(index.search("python").any { it.name == "python-dbg" })
        assertTrue(index.search("   ").isEmpty())
        assertEquals("python", index.search("PYTHON").first().name)
    }

    // ── Resolve ──────────────────────────────────────────────────────────────

    @Test fun resolveListsDependenciesFirstAndSkipsTermuxApp() {
        val names = TermuxRepo.resolve(index, listOf("python-pip")).map { it.name }
        assertEquals("python-pip", names.last())
        assertTrue(names.indexOf("libandroid-support") < names.indexOf("libexpat"))
        assertTrue(names.indexOf("libexpat") < names.indexOf("python"))
        assertFalse("termux-tools belongs to the Termux app", "termux-tools" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun resolvePicksAnAvailableAlternative() {
        // python needs "openssl | libressl"; libressl isn't in the index
        assertTrue(TermuxRepo.resolve(index, listOf("python")).any { it.name == "openssl" })
    }

    @Test fun resolveReportsMissingPackage() {
        try {
            TermuxRepo.resolve(index, listOf("definitely-not-a-package"))
            fail("expected ResolveException")
        } catch (e: TermuxRepo.ResolveException) {
            assertTrue(e.message!!.contains("definitely-not-a-package"))
        }
    }

    // ── Shebangs ─────────────────────────────────────────────────────────────

    private fun fix(line: String, dir: File) = TermuxRepo.fixShebang(line, dir)

    @Test fun shebangPointsTermuxPrefixAtInstallDir() {
        val dir = tmp.newFolder("i1")
        File(dir, "bin").mkdirs(); File(dir, "bin/node").writeText("x")
        assertEquals("#!${dir.path}/bin/node", fix("#!/data/data/com.termux/files/usr/bin/node", dir))
    }

    @Test fun shebangEnvUsesLocalProgramElseSystemEnv() {
        val dir = tmp.newFolder("i2")
        File(dir, "bin").mkdirs(); File(dir, "bin/node").writeText("x")
        assertEquals("#!${dir.path}/bin/node", fix("#!/usr/bin/env node", dir))
        assertEquals("#!/system/bin/env ruby -w", fix("#!/usr/bin/env ruby -w", dir))
        assertEquals("#!/system/bin/env python3", fix("#!/data/data/com.termux/files/usr/bin/env python3", dir))
    }

    @Test fun shebangMissingShellFallsBackToSystemSh() {
        val dir = tmp.newFolder("i3")
        assertEquals("#!/system/bin/sh", fix("#!/data/data/com.termux/files/usr/bin/sh", dir))
        assertEquals("#!/system/bin/sh -e", fix("#!/data/data/com.termux/files/usr/bin/bash -e", dir))
    }

    @Test fun shebangLeavesSystemInterpretersAlone() {
        assertNull(fix("#!/system/bin/sh", tmp.newFolder("i4")))
    }

    @Test fun rewriteShebangsFollowsLinksButStaysInsideInstall() {
        val dir = tmp.newFolder("i5")
        File(dir, "lib/node_modules/npm/bin").mkdirs(); File(dir, "bin").mkdirs()
        File(dir, "bin/node").writeText("\u007fELF")
        val cli = File(dir, "lib/node_modules/npm/bin/npm-cli.js").apply { writeText("#!/usr/bin/env node\nconsole.log(1)\n") }
        Files.createSymbolicLink(File(dir, "bin/npm").toPath(), File("../lib/node_modules/npm/bin/npm-cli.js").toPath())
        // a link leaving the install must never be rewritten
        val outside = tmp.newFile("outside.sh").apply { writeText("#!/usr/bin/env node\n") }
        Files.createSymbolicLink(File(dir, "bin/escape").toPath(), outside.toPath())

        TermuxRepo.rewriteShebangs(dir)

        assertEquals("#!${dir.path}/bin/node", cli.readLines().first())
        assertTrue(Files.isSymbolicLink(File(dir, "bin/npm").toPath()))
        assertEquals("#!/usr/bin/env node", outside.readLines().first())
    }

    // ── .deb extraction ──────────────────────────────────────────────────────

    private fun tarEntry(name: String, type: Char, content: ByteArray = ByteArray(0), link: String = "", mode: Int = 420): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode))
        put(124, "%011o".format(content.size))
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar")
        val out = ByteArrayOutputStream()
        out.write(h)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512))
        return out.toByteArray()
    }

    private fun deb(vararg entries: ByteArray): File {
        val tar = ByteArrayOutputStream().also { o -> entries.forEach(o::write); o.write(ByteArray(1024)) }.toByteArray()
        val xz = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options()).use { it.write(tar) } }.toByteArray()
        val ar = ByteArrayOutputStream()
        ar.write("!<arch>\n".toByteArray())
        fun member(name: String, data: ByteArray) {
            ar.write("%-16s%-12s%-6s%-6s%-8s%-10d`\n".format(name, "0", "0", "0", "100644", data.size).toByteArray())
            ar.write(data)
            if (data.size % 2 == 1) ar.write('\n'.code)
        }
        member("debian-binary", "2.0\n".toByteArray())
        member("control.tar.xz", ByteArray(3))
        member("data.tar.xz", xz)
        return tmp.newFile().apply { writeBytes(ar.toByteArray()) }
    }

    private val usr = "./data/data/com.termux/files/usr"

    @Test fun extractsFilesLinksAndExecBitsUnderUsrOnly() {
        val dest = tmp.newFolder("d1")
        val count = TermuxRepo.extractDeb(
            deb(
                tarEntry("$usr/", '5'),
                tarEntry("$usr/bin/", '5'),
                tarEntry("$usr/bin/hello", '0', "echo hi\n".toByteArray(), mode = 493),
                tarEntry("$usr/share/doc.txt", '0', "doc".toByteArray()),
                tarEntry("$usr/bin/hi", '2', link = "$usr/bin/hello".removePrefix(".")),
                tarEntry("./data/data/com.termux/files/home/.bashrc", '0', "no".toByteArray()),
            ),
            dest,
        )
        assertTrue(count >= 3)
        assertEquals("echo hi\n", File(dest, "bin/hello").readText())
        assertTrue("exec bit kept", File(dest, "bin/hello").canExecute())
        assertFalse("plain file isn't executable", File(dest, "share/doc.txt").canExecute())
        // absolute Termux target is remapped into the install dir
        assertEquals("${dest.canonicalPath}/bin/hello", Files.readSymbolicLink(File(dest, "bin/hi").toPath()).toString())
        assertFalse("files outside usr/ are skipped", File(dest, "home").exists() || File(dest, ".bashrc").exists())
    }

    @Test fun blocksWritingThroughASymlinkThatLeavesTheInstall() {
        val dest = tmp.newFolder("d2")
        val victim = tmp.newFolder("victim")
        try {
            TermuxRepo.extractDeb(
                deb(
                    tarEntry("$usr/lib/evil", '2', link = victim.path),
                    tarEntry("$usr/lib/evil/pwned", '0', "x".toByteArray()),
                ),
                dest,
            )
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
        assertFalse(File(victim, "pwned").exists())
    }

    @Test fun ignoresDotDotPaths() {
        val dest = tmp.newFolder("d3")
        TermuxRepo.extractDeb(deb(tarEntry("$usr/../../escape", '0', "x".toByteArray())), dest)
        assertFalse(File(dest.parentFile, "escape").exists())
    }

    @Test fun rejectsNonDebFiles() {
        try {
            TermuxRepo.extractDeb(tmp.newFile().apply { writeText("not a deb at all") }, tmp.newFolder("d4"))
            fail("expected IOException")
        } catch (_: java.io.IOException) {
        }
    }
}
