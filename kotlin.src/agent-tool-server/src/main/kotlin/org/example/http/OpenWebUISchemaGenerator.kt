package org.example.http

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.ToolDefinition
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.util.Json

/**
 * Generates Open WebUI-compatible tool schemas from the tool registry.
 *
 * Open WebUI expects tools in OpenAI function calling format:
 * {
 *   "name": "tool_name",
 *   "description": "What this tool does",
 *   "parameters": {
 *     "type": "object",
 *     "required": ["param1"],
 *     "properties": {
 *       "param1": {
 *         "type": "string",
 *         "description": "Parameter description"
 *       }
 *     }
 *   }
 * }
 */
object OpenWebUISchemaGenerator {

    /**
     * Generate Open WebUI compatible tool schema array.
     * Returns an array of tool definitions following OpenAI function calling spec.
     */
    fun generateToolSchemas(registry: ToolRegistry): ArrayNode {
        val tools = ArrayNode(JsonNodeFactory.instance)

        registry.listTools().forEach { tool ->
            tools.add(generateToolSchema(tool))
        }

        return tools
    }

    /**
     * Convert a single ToolDefinition to Open WebUI format.
     */
    private fun generateToolSchema(tool: ToolDefinition): ObjectNode {
        val schema = JsonNodeFactory.instance.objectNode()

        schema.put("name", tool.name)
        schema.put("description", tool.shortDescription)

        // Build parameters schema
        val parameters = buildParametersSchema(tool.parameters, tool.paramsSpec)
        schema.set<ObjectNode>("parameters", parameters)

        return schema
    }

    /**
     * Build the parameters schema object.
     * Attempts to parse paramsSpec if available, otherwise constructs from parameter list.
     */
    private fun buildParametersSchema(params: List<ToolParam>, paramsSpec: String): ObjectNode {
        // Try parsing paramsSpec first
        if (paramsSpec.isNotBlank()) {
            try {
                val parsed = Json.mapper.readTree(paramsSpec)
                if (parsed is ObjectNode) {
                    return parsed
                }
            } catch (e: Exception) {
                // Fall through to manual construction
            }
        }

        // Manually construct from parameter list
        val schema = JsonNodeFactory.instance.objectNode()
        schema.put("type", "object")

        val properties = JsonNodeFactory.instance.objectNode()
        val required = ArrayNode(JsonNodeFactory.instance)

        params.forEach { param ->
            val propSchema = JsonNodeFactory.instance.objectNode()
            propSchema.put("type", mapKotlinTypeToJsonType(param.type))
            if (param.description.isNotBlank()) {
                propSchema.put("description", param.description)
            }
            properties.set<ObjectNode>(param.name, propSchema)

            if (param.required) {
                required.add(param.name)
            }
        }

        schema.set<ObjectNode>("properties", properties)
        if (required.size() > 0) {
            schema.set<ArrayNode>("required", required)
        }

        return schema
    }

    /**
     * Map Kotlin/Java type strings to JSON Schema types.
     */
    private fun mapKotlinTypeToJsonType(kotlinType: String): String {
        return when (kotlinType.lowercase()) {
            "string" -> "string"
            "int", "integer", "long" -> "integer"
            "double", "float", "number" -> "number"
            "boolean", "bool" -> "boolean"
            "list", "array" -> "array"
            "map", "object" -> "object"
            else -> "string" // Default fallback
        }
    }

    /**
     * Generate full schema document with metadata.
     */
    fun generateFullSchema(registry: ToolRegistry): ObjectNode {
        val root = JsonNodeFactory.instance.objectNode()

        root.put("version", "1.0.0")
        root.put("generatedAt", java.time.Instant.now().toString())
        root.put("format", "openai-function-calling")
        root.put("description", "Agent tool schemas for Open WebUI integration")

        val tools = generateToolSchemas(registry)
        root.set<ArrayNode>("tools", tools)
        root.put("toolCount", tools.size())

        return root
    }
}
