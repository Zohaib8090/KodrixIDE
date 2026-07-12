package com.kodrix.zohaib.lsp

import com.kodrix.zohaib.platform.logDebug
import com.kodrix.zohaib.platform.logError
import com.kodrix.zohaib.platform.logInfo
import com.kodrix.zohaib.platform.executeCommand
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class LspClient(
    private val command: List<String>,
    private val workingDir: String,
    private val env: Map<String, String> = emptyMap()
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: BufferedInputStream? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val messageId = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onDiagnosticsReceived: ((PublishDiagnosticsParams) -> Unit)? = null
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    fun start() {
        val pb = ProcessBuilder(command)
        pb.directory(java.io.File(workingDir))
        pb.environment().putAll(env)

        try {
            process = pb.start()
            stdin = process?.outputStream
            stdout = BufferedInputStream(process?.inputStream)

            logInfo("LspClient", "LSP Process started: ${command.joinToString(" ")}")
            startListening()
            startErrorListening()
        } catch (e: Exception) {
            logError("LspClient", "Failed to start LSP process", e)
        }
    }

    private fun startErrorListening() {
        scope.launch {
            val err = process?.errorStream ?: return@launch
            try {
                val reader = err.bufferedReader()
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line == null) break
                    logError("LspClient", "STDERR: $line")
                }
            } catch (e: Exception) {
                logError("LspClient", "Error listening to LSP stderr", e)
            }
        }
    }

    private fun startListening() {
        scope.launch {
            val input = stdout ?: return@launch
            try {
                while (isActive) {
                    val headers = mutableMapOf<String, String>()
                    var line = readLine(input) ?: break

                    while (line.isNotEmpty()) {
                        if (line.contains(": ")) {
                            val parts = line.split(": ", limit = 2)
                            if (parts.size == 2) headers[parts[0]] = parts[1]
                        }
                        line = readLine(input) ?: break
                    }
                    if (line.isEmpty() && !isActive) break

                    val contentLength = headers.entries
                        .find { it.key.equals("Content-Length", ignoreCase = true) }
                        ?.value?.trim()?.toIntOrNull()

                    if (contentLength == null) {
                        logDebug("LspClient", "Missing or invalid Content-Length")
                        continue
                    }

                    val buffer = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buffer, read, contentLength - read)
                        if (r <= 0) break
                        read += r
                    }

                    if (read == contentLength) {
                        handleMessage(String(buffer, Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                logError("LspClient", "Error listening to LSP", e)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c == '\r'.code) {
                input.mark(1)
                val next = input.read()
                if (next != '\n'.code && next != -1) input.reset()
                break
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handleMessage(content: String) {
        try {
            val element = json.parseToJsonElement(content).jsonObject

            if (element.containsKey("id") && (element.containsKey("result") || element.containsKey("error"))) {
                val id = element["id"]?.jsonPrimitive?.intOrNull ?: -1
                if (id != -1) pendingRequests.remove(id)?.complete(element)
            } else if (element.containsKey("method")) {
                val method = element["method"]?.jsonPrimitive?.content ?: ""
                if (method == "textDocument/publishDiagnostics") {
                    val params = json.decodeFromJsonElement<PublishDiagnosticsParams>(element["params"]!!)
                    onDiagnosticsReceived?.invoke(params)
                }
            }
        } catch (e: Exception) {
            logError("LspClient", "Error handling message", e)
        }
    }

    @Synchronized
    private fun sendRaw(message: JsonObject) {
        val jsonString = json.encodeToString(JsonObject.serializer(), message)
        val payload = "Content-Length: ${jsonString.toByteArray().size}\r\n\r\n$jsonString"
        try {
            stdin?.write(payload.toByteArray())
            stdin?.flush()
        } catch (e: Exception) {
            logError("LspClient", "Failed to send message", e)
        }
    }

    suspend fun request(method: String, params: Any): JsonObject? {
        val id = messageId.getAndIncrement()
        val paramsJson = paramsToJson(params)
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", paramsJson)
        }
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        sendRaw(req)
        return withTimeoutOrNull(5000) { deferred.await() }
    }

    fun notify(method: String, params: Any) {
        val paramsJson = paramsToJson(params)
        val notif = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", paramsJson)
        }
        sendRaw(notif)
    }

    private fun paramsToJson(params: Any): JsonElement {
        return when (params) {
            is JsonObject -> params
            is JsonElement -> params
            is String -> JsonPrimitive(params)
            is Number -> JsonPrimitive(params)
            is Boolean -> JsonPrimitive(params)
            else -> buildJsonObject { put("value", params.toString()) }
        }
    }

    fun stop() {
        scope.cancel()
        process?.destroy()
    }
}
