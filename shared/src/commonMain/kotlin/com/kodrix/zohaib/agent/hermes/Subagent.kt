package com.kodrix.zohaib.agent.hermes

import com.kodrix.zohaib.agent.hermes.Goal
import com.kodrix.zohaib.agent.hermes.Skill
import com.kodrix.zohaib.agent.hermes.MemoryStore

/**
 * Subagent — a one-shot LLM call with a fresh context, used to delegate
 * subtasks. The classic Hermes / Cline pattern: a focused task with
 * explicit context, no history baggage.
 *
 * The subagent has no memory of the parent conversation, but it does get
 * the same skill and memory snapshot so it can work consistently.
 */
data class SubagentTask(
    val task: String,
    val systemHint: String? = null,
    val model: String? = null,        // null = use the parent's model
    val maxTokens: Int = 1024,
)

/**
 * Callback for actually calling the LLM. The platform impl wires this to
 * the AiHttpClient that talks to the local agent server.
 *
 * Returns the final assistant text.
 */
typealias SubagentLlm = suspend (SubagentTask) -> String

/**
 * The Subagent delegates one task at a time. The runtime is responsible
 * for resolving the model + LLM; this class only composes the prompt.
 */
class Subagent(
    private val skills: SkillLoader,
    private val memory: MemoryStore,
) {
    /**
     * Build the system prompt for a subagent. Inherits skills + memory so
     * the subagent has the same context as the parent.
     */
    fun buildSystemPrompt(extra: String? = null): String {
        val parts = mutableListOf<String>()
        parts.add(
            "You are a sub-agent. You have a single focused task with no prior " +
                "conversation history. Produce a complete answer in one response."
        )
        if (!extra.isNullOrBlank()) parts.add(extra)
        val mem = memory.toPromptSection()
        if (mem.isNotBlank()) parts.add(mem)
        val skillsBlock = skills.all()
            .filter { it.description.isNotBlank() }
            .joinToString("\n") { it.toPromptSection() }
        if (skillsBlock.isNotBlank()) {
            parts.add("## Available skills\n$skillsBlock")
        }
        return parts.joinToString("\n\n")
    }

    /**
     * Convenience: run a subagent and return its raw text response.
     * Errors propagate to the caller (which decides whether to feed them
     * back to the parent or surface to the user).
     */
    suspend fun run(task: SubagentTask, llm: SubagentLlm): String {
        val system = buildSystemPrompt(task.systemHint)
        return llm(task.copy(systemHint = system))
    }
}
