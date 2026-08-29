package com.kodrix.zohaib.agent.hermes

import kotlinx.serialization.Serializable

/**
 * A Skill is a markdown document that teaches the agent how to do a specific
 * kind of work — like a "playbook" the agent reads when relevant.
 *
 * Storage layout (on disk):
 *   {skillsDir}/
 *     code-review.md          (top-level skill)
 *     git/
 *       commit-message.md     (skill in a subdirectory; name includes parent)
 *
 * The skill body is the entire markdown file. The filename (minus .md) is
 * the skill's stable id; if it's in a subdirectory, the id is the joined
 * path (e.g. "git/commit-message") so the agent can reference it.
 *
 * Skills are inspired by the agentskills.io standard used by Hermes.
 */
@Serializable
data class Skill(
    /** Stable id, e.g. "code-review" or "git/commit-message". */
    val id: String,
    /** Human-readable name parsed from the first H1 in the markdown, if any. */
    val name: String,
    /** First paragraph / description block (everything before the first H2). */
    val description: String,
    /** Full markdown body. */
    val body: String,
) {
    /**
     * Render this skill for inclusion in a system prompt. Keeps it compact
     * so the prompt doesn't bloat with skill metadata.
     */
    fun toPromptSection(): String = buildString {
        append("### Skill: $id\n")
        if (name != id) append("Name: $name\n")
        if (description.isNotBlank()) {
            append("\n")
            append(description)
            append("\n")
        }
    }
}
