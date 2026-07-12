package com.kodrix.zohaib.ai

import com.kodrix.zohaib.platform.logInfo
import com.kodrix.zohaib.platform.logError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Desktop-specific AI backend that communicates with the Gemini API
 * directly via HTTPS (no WebView needed on desktop).
 *
 * Usage:
 *   1. Set API key via setApiKey() or GEMINI_API_KEY env var
 *   2. Call ask(prompt) for single-turn, or startChatSession() for multi-turn
 *   3. Responses are returned as Markdown strings
 */
class DesktopAIBackendManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val askMutex = Mutex()

    private val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    /** Set to true once the API key is validated */
    private var isReady = false

    /** Gemini API key — loaded from env or set programmatically */
    private var apiKey: String? = null

    /** Current chat session ID for multi-turn conversations */
    private var currentSessionId: String? = null

    /** Model identifier */
    var model: String = "gemini-2.0-flash"

    /** System instruction / persona injected into every request */
    var systemInstruction: String = ""
        set(value) {
            field = value
            currentSessionId = null // Reset session when system instruction changes
        }

    init {
        // Try loading from environment variable
        apiKey = System.getenv("GEMINI_API_KEY")
        if (!apiKey.isNullOrBlank()) {
            isReady = true
            _status.value = "AI Engine: Ready (env key)"
            logInfo("DesktopAI", "Loaded Gemini API key from GEMINI_API_KEY")
        } else {
            _status.value = "AI: No API key — call setApiKey() or set GEMINI_API_KEY env"
        }
    }

    /**
     * Set the Gemini API key programmatically.
     * Call this from the Settings UI when the user enters a key.
     */
    fun setApiKey(key: String) {
        if (key.isBlank()) {
            _status.value = "AI: Invalid API key"
            return
        }
        apiKey = key
        isReady = true
        currentSessionId = null
        _status.value = "AI Engine: Ready"
        logInfo("DesktopAI", "API key set programmatically")
    }

    /** Returns true if the manager is ready to make requests */
    fun isReady(): Boolean = isReady && !apiKey.isNullOrBlank()

    /** Returns the current API key (for UI display) */
    fun getApiKey(): String? = apiKey

    /**
     * Start a new multi-turn chat session.
     * Returns the session ID that can be used for follow-up messages.
     */
    fun startChatSession(): String {
        val id = "session_${System.currentTimeMillis()}"
        currentSessionId = id
        logInfo("DesktopAI", "Started new chat session: $id")
        return id
    }

    /**
     * Reset/clear the current chat session (start fresh context).
     */
    fun resetChatSession() {
        currentSessionId = null
        logInfo("DesktopAI", "Chat session reset")
    }

    /**
     * Send a single prompt and get a response.
     * If a chat session is active, the conversation context is maintained.
     *
     * @param prompt The user's message
     * @param sessionId Optional session ID (defaults to current session)
     * @return The AI response text, or an error message prefixed with "Error:"
     */
    suspend fun ask(prompt: String, sessionId: String? = null): String = askMutex.withLock {
        if (!isReady()) return "Error: API key not set. Go to Settings → AI to configure your Gemini API key."

        try {
            _status.value = "AI: Thinking..."

            val effectiveSessionId = sessionId ?: currentSessionId
            val urlStr = if (effectiveSessionId != null) {
                // Multi-turn: use the message endpoint with sessionId
                "https://generativelanguage.googleapis.com/v1beta/models/$model:continueSession?key=$apiKey"
            } else {
                // Single-turn: use generateContent
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            }

            val requestBody = buildRequestBody(prompt, effectiveSessionId)
            val response = doPost(urlStr, requestBody)

            if (response.startsWith("Error:")) {
                _status.value = "AI: Request failed"
                return response
            }

            // Parse the response to extract the text
            val text = extractResponseText(response)
            if (text.isBlank()) {
                _status.value = "AI: Empty response"
                return "Error: AI returned an empty response"
            }

            // If multi-turn and we don't have a session ID yet, the response may contain one
            if (effectiveSessionId == null) {
                // Extract session ID for follow-up
                val newSessionId = extractSessionId(response)
                if (newSessionId != null) {
                    currentSessionId = newSessionId
                }
            }

            _status.value = "AI Engine: Ready"
            return text
        } catch (e: CancellationException) {
            _status.value = "AI: Cancelled"
            return "Error: Request was cancelled"
        } catch (e: Exception) {
            logError("DesktopAI", "Request failed", e)
            _status.value = "AI: Error — ${e.message}"
            return "Error: ${e.message}"
        }
    }

    /**
     * Stream a response — calls [onChunk] with each text fragment as it arrives.
     * Useful for real-time display of AI output.
     */
    suspend fun askStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isReady()) {
            onError("API key not set. Go to Settings → AI to configure your Gemini API key.")
            return
        }

        _status.value = "AI: Streaming..."
        try {
            val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
            val requestBody = buildRequestBody(prompt, currentSessionId)

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.doOutput = true

            // Write request body
            conn.outputStream.use { os ->
                val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
                writer.write(requestBody)
                writer.flush()
            }

            // Read SSE stream
            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            var line: String?
            var fullText = StringBuilder()

            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("data: ")) {
                    val json = line!!.removePrefix("data: ").trim()
                    if (json == "[DONE]") break
                    val chunk = extractResponseText(json)
                    if (chunk.isNotEmpty()) {
                        fullText.append(chunk)
                        onChunk(chunk)
                    }
                }
            }

            conn.disconnect()
            _status.value = "AI Engine: Ready"
        } catch (e: Exception) {
            logError("DesktopAI", "Stream failed", e)
            _status.value = "AI: Stream error"
            onError(e.message ?: "Unknown streaming error")
        }
    }

    /**
     * Build the JSON request body for the Gemini API.
     */
    private fun buildRequestBody(prompt: String, sessionId: String?): String {
        val parts = mutableListOf<Map<String, Any>>()

        // Add system instruction if set (as a user message with context)
        if (systemInstruction.isNotBlank() && sessionId == null) {
            // For the first message in a non-session request, include context
        }

        // Add the user message
        parts.add(mapOf("text" to prompt))

        val content = mapOf("parts" to parts)

        val builder = StringBuilder()
        builder.append("{")
        if (systemInstruction.isNotBlank()) {
            builder.append("\"systemInstruction\":{\"parts\":[{\"text\":\"${escapeJson(systemInstruction)}\"}]},")
        }
        builder.append("\"contents\":[$content]")

        // Generation config
        builder.append(""",\"generationConfig":{
            "temperature":0.7,
            "topP":0.95,
            "topK":40,
            "maxOutputTokens":8192
        }""")

        builder.append("}")
        return builder.toString()
    }

    /**
     * Perform a synchronous HTTP POST request.
     */
    private fun doPost(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 60000
            conn.readTimeout = 120000
            conn.doOutput = true

            conn.outputStream.use { os ->
                val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                logError("DesktopAI", "HTTP $responseCode: $errorStream")
                return "Error: API returned HTTP $responseCode — $errorStream"
            }

            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extract the text content from a Gemini API JSON response.
     */
    private fun extractResponseText(json: String): String {
        // The response format is: { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
        try {
            // Find the "text" field inside parts
            val textRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val matches = textRegex.findAll(json).toList()
            return if (matches.isNotEmpty()) {
                matches.joinToString("") { match ->
                    match.groupValues[1]
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
            } else ""
        } catch (e: Exception) {
            logError("DesktopAI", "Failed to parse response", e)
            return ""
        }
    }

    /**
     * Try to extract a session ID from a continueSession response.
     */
    private fun extractSessionId(json: String): String? {
        val sessionRegex = Regex(""""sessionId"\s*:\s*"([^"]+)"""")
        return sessionRegex.find(json)?.groupValues?.get(1)
    }

    /**
     * Escape a string for JSON embedding.
     */
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Encode a string for URL query parameters.
     */
    private fun urlEncode(str: String): String {
        return URLEncoder.encode(str, StandardCharsets.UTF_8.name())
    }

    /**
     * Clean up resources.
     */
    fun shutdown() {
        scope.cancel()
        _status.value = "AI: Stopped"
        logInfo("DesktopAI", "Backend manager shut down")
    }
}