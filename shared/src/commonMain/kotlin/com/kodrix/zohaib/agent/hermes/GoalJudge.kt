package com.kodrix.zohaib.agent.hermes

/**
 * The LLM-judge completion gate: after each turn, the runtime asks the
 * model "is this goal satisfied?" If yes, the goal is marked completed.
 * If no, the agent continues.
 *
 * The judge is a separate small LLM call (ideally a cheaper model than
 * the one doing the work). It's what makes the auto-run mode actually
 * "auto" — without it, the agent would either give up after one turn
 * or loop forever.
 *
 * Inspired by Hermes' `/goal` command.
 */
class GoalJudge(
    private val llm: JudgeLlm,
) {
    /**
     * Returns true if the goal is satisfied given the current conversation.
     */
    suspend fun isSatisfied(goal: Goal, recentHistory: List<String>): Boolean {
        val prompt = buildJudgePrompt(goal, recentHistory)
        val verdict = llm(prompt).lowercase()
        return verdict.contains("yes") && !verdict.contains("not")
    }

    private fun buildJudgePrompt(goal: Goal, recentHistory: List<String>): String {
        return buildString {
            append("You are a goal-completion judge. The agent has been working on this goal:\n\n")
            append("GOAL: ${goal.description}\n\n")
            append("Here are the agent's recent messages (most recent last):\n\n")
            for ((i, msg) in recentHistory.withIndex()) {
                append("[${i + 1}] $msg\n")
            }
            append("\nHas the goal been satisfied? Reply with one word on its own line: YES or NO.\n")
            append("Reply NO if the agent is still mid-task, hasn't tried anything yet, or has explicitly given up.\n")
            append("Reply YES only if you can see concrete evidence the goal is done.\n")
        }
    }
}

/**
 * Callback for the judge's small LLM call. Same pattern as SubagentLlm:
 * the platform impl wires this to the AiHttpClient.
 */
typealias JudgeLlm = suspend (String) -> String
