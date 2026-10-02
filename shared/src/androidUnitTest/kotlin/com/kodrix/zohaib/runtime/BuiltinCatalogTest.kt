package com.kodrix.zohaib.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural checks on the built-in language catalog: a typo here would only show up on a phone. */
class BuiltinCatalogTest {
    private val tools = BuiltinCatalog.tools

    private fun strings(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    @Test fun hasTheLanguagesWeAdvertise() {
        val expected = listOf("node", "python", "c", "rust", "go", "lua", "php", "zig", "dart", "gleam", "swift", "ruby", "java", "kotlin")
        assertTrue("missing: ${expected - tools.keys}", tools.keys.containsAll(expected))
    }

    @Test fun everyEntryIsInstallable() {
        for ((id, t) in tools) {
            assertTrue("$id: displayName", t.optString("displayName").isNotEmpty())
            assertTrue("$id: iconUrl", t.optString("iconUrl").startsWith("https://"))
            val versions = t.getJSONArray("versions")
            assertTrue("$id: needs a version entry", versions.length() > 0)
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                assertEquals("$id: source", "termux", v.getString("source"))
                assertTrue("$id: packages", strings(v.getJSONArray("packages")).all { it.isNotBlank() } && v.getJSONArray("packages").length() > 0)
                assertTrue("$id: tag", v.getString("tag").isNotEmpty())
                // versionPackage (what the version number comes from) must be one of the packages
                v.optString("versionPackage").takeIf { it.isNotEmpty() }?.let {
                    assertTrue("$id: versionPackage $it not in packages", it in strings(v.getJSONArray("packages")))
                }
            }
        }
    }

    @Test fun fileTypesAreLowercaseWithoutDots() {
        for ((id, t) in tools) {
            val langs = t.optJSONObject("languages") ?: continue
            langs.keys().forEach { ext ->
                assertEquals("$id: .$ext", ext.lowercase().removePrefix("."), ext)
                assertTrue("$id: $ext needs a languageId", langs.getString(ext).isNotEmpty())
            }
        }
    }

    @Test fun noFileTypeIsClaimedTwice() {
        val seen = mutableMapOf<String, String>()
        for ((id, t) in tools) {
            t.optJSONObject("languages")?.keys()?.forEach { ext ->
                assertFalse("$ext claimed by both ${seen[ext]} and $id", ext in seen)
                seen[ext] = id
            }
        }
    }

    @Test fun languageServerCommandsAreRunnable() {
        for ((id, t) in tools) {
            val lsp = t.optJSONObject("lsp") ?: continue
            val cmd = strings(lsp.getJSONArray("command"))
            assertTrue("$id: command[0] must be inside the install or the built-in node, was ${cmd[0]}",
                cmd[0].startsWith("\${install}/") || cmd[0] == "\${node}")
            assertFalse("$id: unresolved %…% placeholder", cmd.any { it.contains('%') })
            if (lsp.has("npm")) {
                assertEquals("$id: npm servers run on the built-in node", "\${node}", cmd[0])
                assertTrue("$id: node server path should live in install/lsp", cmd[1].startsWith("\${install}/lsp/"))
            }
            assertTrue("$id: a language server needs file types to attach to", t.optJSONObject("languages")?.length() ?: 0 > 0)
        }
    }

    @Test fun commandAliasesAndHintsAreSane() {
        for ((id, t) in tools) {
            val bins = strings(t.optJSONArray("binaries"))
            bins.forEach { assertTrue("$id: bad binary entry '$it'", it.isNotBlank() && !it.startsWith("=") && !it.endsWith("=") && !it.contains('/')) }
            val names = bins.map { it.substringBefore('=') }
            assertEquals("$id: duplicate command names", names.size, names.toSet().size)
            val hints = t.optJSONObject("hints")
            hints?.keys()?.forEach { h ->
                assertFalse("$id: hint '$h' shadows a real command", h in names)
                assertTrue("$id: hint '$h' is empty", hints.getString(h).isNotBlank())
            }
        }
    }

    @Test fun verifyCommandsPointInsideTheInstall() {
        for ((id, t) in tools) {
            val v = t.optJSONObject("verify") ?: continue
            assertTrue("$id: verify", strings(v.getJSONArray("command"))[0].startsWith("\${install}/bin/"))
        }
    }

    @Test fun envValuesExpandCleanly() {
        for ((id, t) in tools) {
            val env = t.optJSONObject("env") ?: continue
            env.keys().forEach { k ->
                val v = env.getString(k)
                assertFalse("$id: $k has an unresolved %…% placeholder", v.contains('%'))
            }
        }
    }

    @Test fun entriesSurviveAJsonRoundTrip() {
        for ((id, t) in tools) assertEquals(id, JSONObject(t.toString()).toString().length, t.toString().length)
    }
}
