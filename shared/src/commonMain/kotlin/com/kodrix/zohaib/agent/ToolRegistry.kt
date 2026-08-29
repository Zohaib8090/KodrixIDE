package com.kodrix.zohaib.agent

import kotlinx.serialization.json.JsonObject

/**
 * In-memory tool registry. The agent runtime looks tools up here, both
 * to build the JSON-schema list it sends to the model and to dispatch
 * tool_calls from the model.
 */
class ToolRegistry(initial: List<Tool> = emptyList()) {
    private val byName: MutableMap<String, Tool> = initial.associateBy { it.name }.toMutableMap()

    fun register(tool: Tool) { byName[tool.name] = tool }
    fun unregister(name: String) { byName.remove(name) }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
    fun names(): List<String> = byName.keys.toList()

    /** All tool definitions in the format the model expects. */
    fun asToolDefinitions(): List<ToolDefinition> = all().map { t ->
        ToolDefinition(
            type = "function",
            function = ToolDefinition.FunctionDef(
                name = t.name,
                description = t.description,
                parameters = t.parametersSchema,
            )
        )
    }
}
