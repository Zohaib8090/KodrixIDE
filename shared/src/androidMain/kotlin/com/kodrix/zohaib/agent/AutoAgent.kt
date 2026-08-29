package com.kodrix.zohaib.agent

import android.util.Log
import com.kodrix.zohaib.agent.hermes.MemoryStore
import com.kodrix.zohaib.agent.hermes.Skill
import com.kodrix.zohaib.agent.hermes.SkillLoader
import com.kodrix.zohaib.agent.hermes.Subagent
import com.kodrix.zohaib.agent.hermes.SubagentTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The main agent runtime. One instance per agent session; manages the
 * model ↔ tool loop, the chat history, the streaming UI, and the auto-
 * run mode (where the agent keeps working until the goal judge says
 * it's done or a hard cap is hit).
 *
 * Inspired by Hermes' agent loop + Cline's tool approval + Kilo's
 * "auto" mode. Pairs with [AiHttpClient] (the LLM transport) and the
 * [ToolRegistry] (what the model can call).
 *
 * Public state (all [MutableStateFlow]s so the Compose UI binds to them):
 *   - [messages] : the chat history, updated as turns happen
 *   - [streamingContent] : the in-progress assistant text (between turns)
 *   - [status] : "idle" | "thinking" | "calling_tool:NAME" | "auto-running"
 *   - [pendingApproval] : a tool call awaiting user approval (or null)
 *   - [availableProviders] : the providers the server has, refreshed on start
 *   - [activeProvider] : the current provider/model selection
 *   - [activeSkill] : an optional skill the user has pinned for this session
 *
 * Lifecycle:
 *   - start()  — start the server, load providers, list tools
 *   - send()   — send a user message; runs one turn
 *   - autoRun() — give the agent a goal; it keeps going until done or capped
 *   - approveTool() / rejectTool() — handle pending approvals
 *   - stop()   — cancel any in-flight turn and the server
 */
class AutoAgent(
    private val http: AiHttpClient,
    private val registry: ToolRegistry,
    private val skills: SkillLoader,
    private val memory: MemoryStore,
    private val subagent: Subagent,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent = _streamingContent.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status = _status.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval = _pendingApproval.asStateFlow()

    private val _availableProviders = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val availableProviders = _availableProviders.asStateFlow()

    private val _activeProvider = MutableStateFlow<ActiveProvider?>(null)
    val activeProvider = _activeProvider.asStateFlow()

    private val _activeSkill = MutableStateFlow<Skill?>(null)
    val activeSkill = _activeSkill.asStateFlow()

    private var currentJob: Job? = null

    fun refreshProviders() {
        scope.launch {
            try {
                val providers = http.listProviders().filter { it.enabled && it.models.isNotEmpty() }
                _availableProviders.value = providers
                if (_activeProvider.value == null && providers.isNotEmpty()) {
                    val first = providers.first()
                    _activeProvider.value = ActiveProvider(first.id, first.models.first(), first.supportsReasoning)
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to list providers: ${e.message}")
            }
        }
    }

    fun setProvider(id: String, model: String) {
        val p = _availableProviders.value.firstOrNull { it.id == id } ?: return
        _activeProvider.value = ActiveProvider(id, model, p.supportsReasoning)
    }

    fun setActiveSkill(skill: Skill?) {
        _activeSkill.value = skill
    }

    /**
     * Send a user message. Runs ONE turn: model call → optional tool call(s) →
     * (if tool, ask user to approve, then feed result back). The model decides
     * whether to keep going.
     */
    fun send(userText: String) {
        if (_activeProvider.value == null) {
            _status.value = "no provider selected"
            return
        }
        currentJob = scope.launch {
            runTurn(userText)
        }
    }

    /**
     * Auto-run: give the agent a goal; it keeps working until the model
     * reports "no more tool calls" (implicit completion), the hard
     * iteration cap is hit, or [stop] is called.
     */
    fun autoRun(goal: String, maxIterations: Int = 15) {
        if (_activeProvider.value == null) {
            _status.value = "no provider selected"
            return
        }
        currentJob = scope.launch {
            _status.value = "auto-running"
            // First, inject the goal as a system message + user message.
            val history = _messages.value + ChatMessage(
                role = "user",
                content = "GOAL: $goal\n\nWork toward this goal using the available tools. " +
                    "When the goal is achieved, give a final summary without calling any more tools."
            )
            _messages.value = history
            var i = 0
            while (i < maxIterations) {
                i++
                val lastUserIndex = _messages.value.indexOfLast { it.role == "user" }
                if (lastUserIndex < 0) break
                val more = runOneIteration(lastUserIndex, interactive = false)
                if (!more) break
            }
            _status.value = "auto-run finished ($i iterations)"
        }
    }

    /** Approve a pending tool call and continue. */
    fun approveTool(approval: PendingApproval) {
        scope.launch {
            val resultText = runCatching {
                withContext(Dispatchers.IO) { approval.tool.execute(approval.args, approval.context) }
            }.getOrElse { "ERROR: ${it.message}" }
            _pendingApproval.value = null
            // Append the tool result and continue the loop.
            appendToolResult(approval.callId, resultText)
            continueAfterToolCall()
        }
    }

    /** Reject a pending tool call. */
    fun rejectTool(approval: PendingApproval) {
        _pendingApproval.value = null
        appendToolResult(approval.callId, "REJECTED by user")
        continueAfterToolCall()
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        _status.value = "stopped"
        _pendingApproval.value = null
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        _streamingContent.value = null
    }

    // ---------- internal ----------

    private suspend fun runTurn(userText: String) {
        _status.value = "thinking"
        val history = _messages.value + ChatMessage(role = "user", content = userText)
        _messages.value = history
        val lastUserIndex = history.indexOfLast { it.role == "user" }
        runOneIteration(lastUserIndex, interactive = true)
    }

    /**
     * Run a single model → optional tool → (re-prompt) cycle, starting
     * from the most recent user message. Returns true if more iterations
     * may be needed (e.g. tool result just fed back), false if the model
     * produced a final answer with no tool calls.
     */
    private suspend fun runOneIteration(userIndex: Int, interactive: Boolean): Boolean {
        val active = _activeProvider.value ?: return false
        val systemPrompt = buildSystemPrompt()
        val slice = _messages.value.subList(0, userIndex + 1).toMutableList()
        // Prepend the system message.
        slice.add(0, ChatMessage(role = "system", content = systemPrompt))
        val tools = registry.asToolDefinitions()

        val response = try {
            _streamingContent.value = ""
            http.chatStreaming(
                model = active.model,
                messages = slice,
                tools = tools,
                toolChoice = "auto",
                temperature = 0.2,
                onChunk = { delta ->
                    if (delta.content.isNotEmpty()) {
                        _streamingContent.value = (_streamingContent.value ?: "") + delta.content
                    }
                },
            )
        } catch (e: Exception) {
            _status.value = "error: ${e.message}"
            _streamingContent.value = null
            return false
        }
        _streamingContent.value = null

        // Append the assistant message.
        val assistantMsg = ChatMessage(
            role = "assistant",
            content = response.content,
            tool_calls = response.toolCalls.takeIf { it.isNotEmpty() },
        )
        _messages.value = _messages.value + assistantMsg

        val toolCalls = response.toolCalls
        if (toolCalls.isEmpty()) {
            // Final answer.
            _status.value = "idle"
            return false
        }

        // Process the first tool call; queue the rest via a follow-up.
        val first = toolCalls.first()
        val remaining = toolCalls.drop(1)
        val tool = registry.get(first.name)
        if (tool == null) {
            appendToolResult(first.id, "ERROR: unknown tool '${first.name}'")
            return true
        }
        val args = try {
            json.parseToJsonElement(first.arguments).jsonObject
        } catch (e: Exception) {
            appendToolResult(first.id, "ERROR: tool arguments are not valid JSON: ${e.message}")
            return true
        }
        // Auto-approve safe tools in auto-run mode; otherwise require approval.
        val isAutoApproved = !interactive && tool.safe
        if (isAutoApproved) {
            val resultText = runCatching {
                withContext(Dispatchers.IO) { tool.execute(args, currentContext()) }
            }.getOrElse { "ERROR: ${it.message}" }
            appendToolResult(first.id, resultText)
            // Process remaining sequentially
            for (next in remaining) {
                val nt = registry.get(next.name)
                if (nt == null) { appendToolResult(next.id, "ERROR: unknown tool '${next.name}'"); continue }
                val nargs = try { json.parseToJsonElement(next.arguments).jsonObject } catch (e: Exception) { continue }
                val r = runCatching { withContext(Dispatchers.IO) { nt.execute(nargs, currentContext()) } }.getOrElse { "ERROR: ${it.message}" }
                appendToolResult(next.id, r)
            }
            return true
        } else {
            _status.value = "calling_tool:${tool.name}"
            _pendingApproval.value = PendingApproval(
                callId = first.id,
                tool = tool,
                args = args,
                context = currentContext(),
            )
            return false
        }
    }

    private fun continueAfterToolCall() {
        currentJob = scope.launch {
            val lastUserIndex = _messages.value.indexOfLast { it.role == "user" }
            if (lastUserIndex < 0) return@launch
            runOneIteration(lastUserIndex, interactive = false)
        }
    }

    private fun appendToolResult(callId: String, result: String) {
        _messages.value = _messages.value + ChatMessage(
            role = "tool",
            tool_call_id = callId,
            content = result.take(8000), // cap so a huge log doesn't blow context
        )
    }

    private fun buildSystemPrompt(): String {
        val parts = mutableListOf<String>()
        parts.add(
            "You are Kodrix Agent, a coding assistant running inside the Kodrix IDE on Android. " +
                "You have access to tools that operate on the user's local project. " +
                "Be concise, prefer using tools over guessing, and use the auto-loaded skills when relevant."
        )
        val mem = memory.toPromptSection()
        if (mem.isNotBlank()) parts.add(mem)
        val activeSkill = _activeSkill.value
        if (activeSkill != null) {
            parts.add("## Active skill (user has pinned this for the session)\n${activeSkill.body}")
        }
        val skillsBlock = skills.all()
            .filter { it.description.isNotBlank() }
            .joinToString("\n") { it.toPromptSection() }
        if (skillsBlock.isNotBlank()) parts.add("## Available skills\n$skillsBlock")
        return parts.joinToString("\n\n")
    }

    // The current tool context is supplied by the caller (the runtime
    // stores the latest one; the agent panel updates it when the active
    // project changes). For v1 we read it from a static slot set by
    // IDEView on project change.
    @Volatile private var toolContextRef: ToolContext? = null
    fun setToolContext(ctx: ToolContext) { toolContextRef = ctx }
    private fun currentContext(): ToolContext = toolContextRef ?: StubToolContext

    companion object {
        private const val TAG = "kodrix-agent"
    }
}

data class ActiveProvider(
    val id: String,
    val model: String,
    val supportsReasoning: Boolean,
)

data class PendingApproval(
    val callId: String,
    val tool: Tool,
    val args: JsonObject,
    val context: ToolContext,
)

/** Fallback when no project is active. Tools will fail with clear errors. */
private object StubToolContext : ToolContext {
    override val projectRoot = "/"
    override suspend fun readFile(path: String) = throw RuntimeException("no project open")
    override suspend fun writeFile(path: String, content: String) = throw RuntimeException("no project open")
    override suspend fun listDir(path: String, maxDepth: Int) = throw RuntimeException("no project open")
    override suspend fun grep(pattern: String, glob: String?, path: String?) = throw RuntimeException("no project open")
    override suspend fun runShell(command: String, timeoutMs: Long) = throw RuntimeException("no project open")
    override suspend fun runGit(args: List<String>) = throw RuntimeException("no project open")
    override suspend fun lspDiagnostics(path: String) = throw RuntimeException("no project open")
    override suspend fun lspDefinition(path: String, line: Int, col: Int) = throw RuntimeException("no project open")
    override suspend fun askUser(question: String, options: List<String>): String? = null
}
