package com.kodrix.zohaib.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinCatalogTest {
    private val tools get() = BuiltinCatalog.tools

    @Test fun parsesAndHasCoreLanguages() {
        listOf("node", "python", "c", "rust", "go", "markdown", "toml").forEach { assertTrue(it in tools, "missing $it") }
    }

    @Test fun everyEntryIsWellFormed() {
        for ((id, t) in tools) {
            assertTrue(t.optString("displayName").isNotEmpty(), "$id displayName")
            assertTrue(t.optJSONArray("binaries")?.length() ?: 0 > 0, "$id binaries")
            val versions = t.getJSONArray("versions")
            assertTrue(versions.length() > 0, "$id versions")
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                assertEquals("termux", v.getString("source"), "$id source")
                assertTrue(v.getString("tag").isNotEmpty(), "$id tag")
                assertTrue(v.getJSONArray("packages").length() > 0, "$id packages")
            }
            if (t.has("icon")) assertTrue(t.getString("iconUrl").endsWith(t.getString("icon")), "$id iconUrl")
        }
    }

    @Test fun placeholdersAreSubstituted() {
        for ((id, t) in tools) {
            val s = t.toString()
            assertTrue(!s.contains("%install%") && !s.contains("%node%"), "$id has unsubstituted placeholder")
        }
        val lsp = tools.getValue("python").getJSONObject("lsp").getJSONArray("command")
        assertEquals("\${node}", lsp.getString(0))
    }

    @Test fun languageServersDeclareFileTypes() {
        for ((id, t) in tools) if (t.has("lsp")) assertTrue(t.has("languages"), "$id has lsp but no languages")
    }
}
