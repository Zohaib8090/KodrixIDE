package com.kodrix.zohaib.agent.hermes

/**
 * In-process file system interface. The IDE platform impl reads/writes
 * real files; tests can pass an in-memory map.
 *
 * Kept tiny on purpose — Hermes-style agents should not need a heavy
 * filesystem abstraction.
 */
interface FileSystem {
    fun listFiles(dir: String): List<String>          // relative paths under the skills dir
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String)
    fun exists(path: String): Boolean
    fun mkdirs(path: String)
    fun delete(path: String): Boolean
}

/**
 * Loads skills from the configured skills directory. Skills are .md files;
 * one skill per file. The first H1 (`# name`) becomes the skill's display
 * name; everything before the first H2 becomes the description.
 */
class SkillLoader(
    private val fs: FileSystem,
    private val skillsDir: String = "skills",
) {
    private val cache = mutableMapOf<String, Skill>()

    /** Load all skills from disk and cache them. Call after creating the loader. */
    fun loadAll(): List<Skill> {
        cache.clear()
        if (!fs.exists(skillsDir)) {
            fs.mkdirs(skillsDir)
            return emptyList()
        }
        val files = fs.listFiles(skillsDir).filter { it.endsWith(".md") }
        for (file in files) {
            val content = fs.readFile(file) ?: continue
            val id = skillIdFromPath(file)
            val skill = parseSkill(id, content)
            cache[id] = skill
        }
        return cache.values.sortedBy { it.id }
    }

    /** Look up a single skill by id (e.g. "code-review" or "git/commit-message"). */
    fun get(id: String): Skill? = cache[id]

    /** All loaded skills. */
    fun all(): List<Skill> = cache.values.sortedBy { it.id }

    private fun skillIdFromPath(path: String): String {
        // Strip the skillsDir prefix and the .md suffix.
        val rel = path.removePrefix(skillsDir).trimStart('/').trimStart('\\')
        return rel.removeSuffix(".md")
    }

    private fun parseSkill(id: String, body: String): Skill {
        val lines = body.lines()
        var name = id
        val descLines = mutableListOf<String>()
        var inDescription = true
        var i = 0

        // First H1 becomes the name.
        for (line in lines) {
            if (line.startsWith("# ")) {
                name = line.removePrefix("# ").trim()
                i++
                break
            }
            i++
        }

        // Collect description until first H2 (or end of file).
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("## ")) {
                inDescription = false
                break
            }
            if (inDescription) descLines.add(line)
            i++
        }
        val description = descLines.joinToString("\n").trim()
        return Skill(id = id, name = name, description = description, body = body)
    }
}
