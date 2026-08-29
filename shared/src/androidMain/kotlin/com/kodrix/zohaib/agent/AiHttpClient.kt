package com.kodrix.zohaib.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the local agent server. Speaks the server's
 * /v1/chat/completions OpenAI-compatible surface.
 *
 * Supports both blocking (request/response) and SSE streaming modes.
 * No external HTTP library — the JDK's HttpURLConnection is enough.
 *
 * Provider keys live on the server (env: refs), so the IDE never sees
 * raw API keys. The IDE just talks to localhost.
 */
class AiHttpClient(
    private val baseUrl: String = "http://127.0.0.1:3080",
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Non-streaming chat completion. Returns the assistant text and any
     * tool calls.
     */
    suspend fun chatBlocking(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        toolChoice: String = "auto",
        temperature: Double = 0.2,
    ): ChatResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(model, messages, tools, toolChoice, temperature, stream = false)
        val responseJson = postJson("/v1/chat/completions", body)
        parseResponse(responseJson)
    }

    /**
     * Streaming chat completion. Invokes [onChunk] for each SSE chunk's
     * delta. Returns the final accumulated assistant message.
     */
    suspend fun chatStreaming(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        toolChoice: String = "auto",
        temperature: Double = 0.2,
        onChunk: (StreamingDelta) -> Unit,
    ): ChatResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(model, messages, tools, toolChoice, temperature, stream = true)
        val conn = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.connectTimeout = 10_000
        conn.readTimeout = 0 // streaming — no overall timeout
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        if (conn.responseCode !in 200..299) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("Server error ${conn.responseCode}: $errBody")
        }

        val fullText = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: break
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            try {
                val obj = json.parseToJsonElement(payload).jsonObject
                val choicesArr = obj["choices"] as? kotlinx.serialization.json.JsonArray
                val choiceObj = choicesArr?.get(0) as? JsonObject
                val delta = choiceObj?.get("delta")?.jsonObject
                if (delta != null) {
                    val content = delta["content"]?.toString()?.trim('"') ?: ""
                    if (content.isNotEmpty()) {
                        fullText.append(content)
                        onChunk(StreamingDelta(content = content, toolCallDelta = null))
                    }
                    val toolDelta = delta["tool_calls"]
                    if (toolDelta != null && toolDelta is kotlinx.serialization.json.JsonArray) {
                        for (tc in toolDelta) {
                            val o = tc.jsonObject
                            val index = o["index"]?.toString()?.toIntOrNull() ?: 0
                            val id = o["id"]?.toString()?.trim('"')
                            val fn = o["function"]?.jsonObject
                            val name = fn?.get("name")?.toString()?.trim('"')
                            val args = fn?.get("arguments")?.toString()?.trim('"') ?: ""
                            // Ensure we have a slot
                            while (toolCalls.size <= index) toolCalls.add(ToolCall(id = "", name = "", arguments = ""))
                            val current = toolCalls[index]
                            toolCalls[index] = current.copy(
                                id = if (!id.isNullOrEmpty()) id else current.id,
                                name = if (!name.isNullOrEmpty()) name else current.name,
                                arguments = current.arguments + args,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad SSE chunk: ${e.message}")
            }
        }
        reader.close()
        ChatResponse(
            content = fullText.toString().ifEmpty { null },
            toolCalls = toolCalls.filter { it.id.isNotEmpty() && it.name.isNotEmpty() },
            finishReason = "stop",
        )
    }

    suspend fun listProviders(): List<ProviderInfo> = withContext(Dispatchers.IO) {
        val r = postJson("/providers", buildJsonObject {})
        val providers = r["providers"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return@withContext emptyList()
        providers.map {
            val o = it.jsonObject
            ProviderInfo(
                id = o["id"]?.toString()?.trim('"') ?: "",
                label = o["label"]?.toString()?.trim('"') ?: "",
                protocol = o["protocol"]?.toString()?.trim('"') ?: "",
                models = (o["models"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.toString().trim('"') } ?: emptyList(),
                enabled = o["enabled"]?.toString() == "true",
                supportsReasoning = o["supportsReasoning"]?.toString() == "true",
            )
        }
    }

    private fun postJson(path: String, body: JsonObject): JsonObject {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("Server error ${conn.responseCode}: $errBody")
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        toolChoice: String,
        temperature: Double,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("temperature", temperature)
        put("stream", stream)
        if (tools.isNotEmpty()) {
            put("tools", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ToolDefinition.serializer()),
                tools
            ))
        }
        put("tool_choice", toolChoice)
        put("messages", json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()),
            messages
        ))
    }

    private fun parseResponse(obj: JsonObject): ChatResponse {
        val choicesArr = obj["choices"] as? kotlinx.serialization.json.JsonArray
        val choice = choicesArr?.get(0) as? JsonObject
        val msg = choice?.get("message")?.jsonObject
        val content = msg?.get("content")?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
        val toolCallsRaw = msg?.get("tool_calls") as? kotlinx.serialization.json.JsonArray
        val toolCalls = toolCallsRaw?.mapNotNull { tc ->
            val o = tc.jsonObject
            val id = o["id"]?.toString()?.trim('"') ?: return@mapNotNull null
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            ToolCall(
                id = id,
                name = fn["name"]?.toString()?.trim('"') ?: return@mapNotNull null,
                arguments = fn["arguments"]?.toString()?.trim('"') ?: "",
            )
        } ?: emptyList()
        return ChatResponse(content = content, toolCalls = toolCalls, finishReason = choice?.get("finish_reason")?.toString()?.trim('"'))
    }

    companion object {
        private const val TAG = "kodrix-agent"
    }
}
