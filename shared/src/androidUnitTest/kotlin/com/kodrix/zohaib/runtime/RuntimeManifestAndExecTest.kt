package com.kodrix.zohaib.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeManifestAndExecTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun manifestRoundTrips() {
        val dir = tmp.newFolder()
        val m = RuntimeManifest(
            tool = "python", version = "3.14.6", displayName = "Python", source = "termux",
            packages = listOf("python=3.14.6-1"), roots = listOf("python", "python-pip"),
            binaries = listOf("python", "pip"), env = mapOf("PYTHONHOME" to "\${install}"),
            languages = mapOf("py" to "python"),
            lsp = RuntimeManifest.Lsp(listOf("\${node}", "\${install}/lsp/x.js", "--stdio"), npm = listOf("pyright"), initializationOptions = JSONObject("{\"a\":1}")),
        )
        m.writeTo(dir)
        val back = RuntimeManifest.readFrom(dir)!!
        assertEquals(m.copy(lsp = null), back.copy(lsp = null))
        assertEquals(m.lsp!!.command, back.lsp!!.command)
        assertEquals(listOf("pyright"), back.lsp!!.npm)
        assertEquals(1, back.lsp!!.initializationOptions!!.getInt("a"))
    }

    @Test fun manifestNormalisesFileTypesAndToleratesGarbage() {
        val dir = tmp.newFolder()
        File(dir, RuntimeManifest.FILE_NAME).writeText(
            """{"tool":"rust","version":"1","languages":{".RS":"rust","Toml":"toml"}}"""
        )
        assertEquals(mapOf("rs" to "rust", "toml" to "toml"), RuntimeManifest.readFrom(dir)!!.languages)

        File(dir, RuntimeManifest.FILE_NAME).writeText("{ truncated")
        assertNull("a corrupt manifest must not crash the app", RuntimeManifest.readFrom(dir))
        assertNull(RuntimeManifest.readFrom(tmp.newFolder()))
    }

    @Test fun emptyLspCommandMeansNoLanguageServer() {
        assertNull(RuntimeManifest.Lsp.fromJson(JSONObject("""{"command":[]}""")))
        assertNull(RuntimeManifest.Lsp.fromJson(null))
    }

    @Test fun expandHandlesInstallTokenAbsoluteAndRelativeValues() {
        val dir = tmp.newFolder("inst")
        File(dir, "etc").mkdirs()
        assertEquals("${dir.absolutePath}/lib/go", RuntimeExec.expand("\${install}/lib/go", dir))
        assertEquals("/abs/path", RuntimeExec.expand("/abs/path", dir))
        assertEquals("https://x.y/z", RuntimeExec.expand("https://x.y/z", dir))
        assertEquals(dir.absolutePath, RuntimeExec.expand("", dir))
        assertEquals("${dir.absolutePath}/etc", RuntimeExec.expand("etc", dir))          // exists in the install
        assertEquals("plain-value", RuntimeExec.expand("plain-value", dir))              // not a path
        assertEquals("a\$HOME", RuntimeExec.expand("a\$HOME", dir))                      // shell vars untouched
    }

    @Test fun shebangParsing() {
        fun sb(text: String) = RuntimeExec.shebang(tmp.newFile().apply { writeText(text) })
        assertEquals("/system/bin/sh" to null, sb("#!/system/bin/sh\necho"))
        assertEquals("/usr/bin/env" to "python3", sb("#!/usr/bin/env python3\n"))
        assertEquals("/bin/bash" to "-e", sb("#!/bin/bash   -e\n"))
        assertNull(sb("echo no shebang"))
        assertNull(sb("#!\n"))
        assertNull(sb(""))
    }

    @Test fun elfDetectionAndLinkerChoice() {
        fun elf(bits: Int) = tmp.newFile().apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), bits.toByte(), 1, 1)) }
        val e64 = elf(2); val e32 = elf(1)
        assertTrue(RuntimeExec.isElf(e64))
        assertEquals("/system/bin/linker64", RuntimeExec.linkerFor(e64))
        assertEquals("/system/bin/linker", RuntimeExec.linkerFor(e32))
        assertFalse(RuntimeExec.isElf(tmp.newFile().apply { writeText("#!/bin/sh") }))
        assertFalse(RuntimeExec.isElf(File(tmp.root, "missing")))
        assertNotNull(RuntimeExec.SHIM_LIB)
    }
}
