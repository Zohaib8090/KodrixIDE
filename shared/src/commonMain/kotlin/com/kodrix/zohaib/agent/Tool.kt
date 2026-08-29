package com.kodrix.zohaib.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A tool the model can call. Tools are described to the model as
 * `(name, description, parameters JSON schema)`; when the model wants
 * to use one, the IDE executes [execute] with the parsed arguments and
 * returns a string result (which is fed back to the model as a tool
 * message).
 *
 * The registry itself is platform-agnostic; the concrete tool set is
 * built by the Android app at startup (see AndroidToolRegistry).
 */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema (Draft 7) for the parameters object. */
    val parametersSchema: JsonObject
    /** Whether this tool is considered "safe" — may be auto-approved. */
    val safe: Boolean get() = false
    /** Execute the tool. Throw on error; the agent loop will surface the error. */
    suspend fun execute(args: JsonObject, context: ToolContext): String
}

interface ToolContext {
    /** Current working directory (project root), for relative paths. */
    val projectRoot: String
    /** Read a file relative to the project root, or absolute. */
    suspend fun readFile(path: String): String
    /** Write a file, creating parent dirs as needed. */
    suspend fun writeFile(path: String, content: String)
    /** List directory contents. */
    suspend fun listDir(path: String, maxDepth: Int = 1): List<String>
    /** Search files for a regex pattern. */
    suspend fun grep(pattern: String, glob: String? = null, path: String? = null): List<String>
    /** Run a shell command. Returns combined stdout+stderr. */
    suspend fun runShell(command: String, timeoutMs: Long = 30_000): String
    /** Run a git command in the project root. */
    suspend fun runGit(args: List<String>): String
    /** Get LSP diagnostics for a file (errors, warnings). */
    suspend fun lspDiagnostics(path: String): String
    /** Get the definition of a symbol at a given file:line:col. */
    suspend fun lspDefinition(path: String, line: Int, col: Int): String
    /** Ask the user a multi-choice question; returns the selected label. */
    suspend fun askUser(question: String, options: List<String>): String?
}

/**
 * Helper: pull a required string field from the args object.
 */
fun JsonObject.requireString(name: String): String {
    val v = this[name] ?: throw IllegalArgumentException("missing required arg: $name")
    return when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
}

fun JsonObject.optionalString(name: String, default: String = ""): String {
    val v = this[name] ?: return default
    return when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
}

fun JsonObject.optionalInt(name: String, default: Int = 0): Int {
    val v = this[name] ?: return default
    return when (v) {
        is JsonPrimitive -> v.content.toIntOrNull() ?: default
        else -> default
    }
}

object Schemas {
    /**
     * Build a JSON Schema for an object with the given properties.
     *
     * The spec for each property is a map of (name, value) pairs where
     * each value is a string. Numeric/boolean values are passed as their
     * JSON-string form (the schema treats them as JSON anyway).
     */
    fun objectSchema(properties: Map<String, Map<String, String>>, required: List<String> = emptyList()): JsonObject {
        val props = buildJsonObject {
            for ((name, spec) in properties) {
                put(name, buildJsonObject {
                    spec.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
        }
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", props)
            if (required.isNotEmpty()) {
                val arr = kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) })
                put("required", arr)
            }
        }
    }
}
