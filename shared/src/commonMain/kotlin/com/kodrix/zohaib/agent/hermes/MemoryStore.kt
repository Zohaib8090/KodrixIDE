package com.kodrix.zohaib.agent.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent memory for the agent. Stores a list of facts the agent has
 * learned (e.g. "Project uses Gradle 8.14 with JDK 21") that survive
 * across chat sessions.
 *
 * Backed by a single JSON file on disk for simplicity — no FTS5, no DB.
 * At this scale (a few hundred facts per user) linear scans are fast
 * enough and there's nothing to break.
 *
 * Inspired by Hermes' MEMORY.md + USER.md pattern: two-tier curated memory.
 */
@Serializable
data class MemoryFact(
    val key: String,
    val value: String,
    val source: String = "agent", // "user" | "agent" | "tool"
    val createdAt: Long = currentTimeMillis(),
    val lastUsedAt: Long = createdAt,
)

@Serializable
data class MemorySnapshot(
    val version: Int = 1,
    val facts: List<MemoryFact> = emptyList(),
)

class MemoryStore(
    private val fs: FileSystem,
    private val memoryFile: String = "memory.json",
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private var snapshot: MemorySnapshot = load()

    private fun load(): MemorySnapshot {
        val raw = fs.readFile(memoryFile) ?: return MemorySnapshot()
        return try {
            json.decodeFromString(MemorySnapshot.serializer(), raw)
        } catch (_: Throwable) {
            // Corrupt or old format — start fresh rather than crash.
            MemorySnapshot()
        }
    }

    fun all(): List<MemoryFact> = snapshot.facts

    fun get(key: String): MemoryFact? = snapshot.facts.firstOrNull { it.key == key }

    fun put(fact: MemoryFact) {
        val existing = snapshot.facts.indexOfFirst { it.key == fact.key }
        val updated = fact.copy(lastUsedAt = currentTimeMillis())
        snapshot = if (existing >= 0) {
            snapshot.copy(facts = snapshot.facts.toMutableList().also { it[existing] = updated })
        } else {
            snapshot.copy(facts = snapshot.facts + updated)
        }
        persist()
    }

    fun delete(key: String): Boolean {
        val before = snapshot.facts.size
        snapshot = snapshot.copy(facts = snapshot.facts.filterNot { it.key == key })
        if (snapshot.facts.size == before) return false
        persist()
        return true
    }

    /**
     * Return facts that match the query substring (case-insensitive). Used
     * by the agent runtime to recall relevant context before a turn.
     */
    fun search(query: String, limit: Int = 10): List<MemoryFact> {
        if (query.isBlank()) return snapshot.facts.take(limit)
        val q = query.lowercase()
        return snapshot.facts
            .filter { it.key.lowercase().contains(q) || it.value.lowercase().contains(q) }
            .sortedByDescending { it.lastUsedAt }
            .take(limit)
    }

    /**
     * Build a memory block to include in the system prompt. Returns an
     * empty string if there's nothing to inject.
     */
    fun toPromptSection(): String {
        if (snapshot.facts.isEmpty()) return ""
        return buildString {
            append("## Memory (persistent across sessions)\n")
            for (f in snapshot.facts.take(20)) {
                append("- ${f.key}: ${f.value}\n")
            }
        }
    }

    private fun persist() {
        fs.writeFile(memoryFile, json.encodeToString(snapshot))
    }
}

/** Platform-agnostic wall-clock so tests can pass a fake. */
internal expect fun currentTimeMillis(): Long
