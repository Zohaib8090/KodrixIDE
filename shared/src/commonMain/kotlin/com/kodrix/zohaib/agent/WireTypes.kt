package com.kodrix.zohaib.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types for the OpenAI-compatible chat completions surface that
 * the local agent server speaks. These are in commonMain so the
 * tool registry, the agent runtime, and the HTTP client can all
 * share them.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    val tool_calls: List<ToolCall>? = null,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDef,
) {
    @Serializable
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JsonElement,
    )
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
)

data class StreamingDelta(
    val content: String,
    val toolCallDelta: ToolCall? = null,
)

data class ProviderInfo(
    val id: String,
    val label: String,
    val protocol: String,
    val models: List<String>,
    val enabled: Boolean,
    val supportsReasoning: Boolean,
)
