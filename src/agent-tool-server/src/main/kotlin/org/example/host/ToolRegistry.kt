package org.example.host

import com.fasterxml.jackson.databind.JsonNode
import org.example.util.Json

data class ToolParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String = ""
)

data class ToolDefinition(
    val name: String,
    // Human-readable concise description (typically same as annotation.shortDescription)
    val description: String,
    val shortDescription: String,
    val longDescription: String,
    val parameters: List<ToolParam>,
    // Parameters technical specification (from annotation.paramsSpec)
    val paramsSpec: String,
    val pluginId: String
)

/**
 * Non-reflective tool handler: given JSON args, produce a result.
 */
fun interface ToolHandler {
    fun call(args: JsonNode): Any?
}

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolHandler>()
    private val defs = mutableListOf<ToolDefinition>()

    /**
     * Register a tool explicitly without any reflection.
     */
    fun register(definition: ToolDefinition, handler: ToolHandler) {
        defs += definition
        tools[definition.name] = handler
    }

    // Reflection-based registration removed to eliminate kotlin-reflect dependency.

    fun listTools(): List<ToolDefinition> = defs.toList()

    fun invoke(name: String, args: JsonNode): Any? {
        val handler = tools[name] ?: throw NoSuchElementException("Tool not found: ${'$'}name")
        return handler.call(args)
    }
}
