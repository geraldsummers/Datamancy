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
    
    val description: String,
    val shortDescription: String,
    val longDescription: String,
    val parameters: List<ToolParam>,
    
    val paramsSpec: String,
    val pluginId: String
)


fun interface ToolHandler {
    fun call(args: JsonNode, userContext: String?): Any?
}

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolHandler>()
    private val defs = mutableListOf<ToolDefinition>()

    
    fun register(definition: ToolDefinition, handler: ToolHandler) {
        defs += definition
        tools[definition.name] = handler
    }

    

    fun listTools(): List<ToolDefinition> = defs.toList()

    fun invoke(name: String, args: JsonNode, userContext: String? = null): Any? {
        val handler = tools[name] ?: throw NoSuchElementException("Tool not found: $name")
        return handler.call(args, userContext)
    }
}
