package org.example.http

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.ToolDefinition
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.util.Json


object OpenWebUISchemaGenerator {

    
    fun generateToolSchemas(registry: ToolRegistry): ArrayNode {
        val tools = ArrayNode(JsonNodeFactory.instance)

        registry.listTools().forEach { tool ->
            tools.add(generateToolSchema(tool))
        }

        return tools
    }

    
    private fun generateToolSchema(tool: ToolDefinition): ObjectNode {
        val schema = JsonNodeFactory.instance.objectNode()

        schema.put("name", tool.name)
        schema.put("description", tool.shortDescription)

        
        val parameters = buildParametersSchema(tool.parameters, tool.paramsSpec)
        schema.set<ObjectNode>("parameters", parameters)

        return schema
    }

    
    private fun buildParametersSchema(params: List<ToolParam>, paramsSpec: String): ObjectNode {
        
        if (paramsSpec.isNotBlank()) {
            try {
                val parsed = Json.mapper.readTree(paramsSpec)
                if (parsed is ObjectNode) {
                    return parsed
                }
            } catch (e: Exception) {
                
            }
        }

        
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

    
    private fun mapKotlinTypeToJsonType(kotlinType: String): String {
        return when (kotlinType.lowercase()) {
            "string" -> "string"
            "int", "integer", "long" -> "integer"
            "double", "float", "number" -> "number"
            "boolean", "bool" -> "boolean"
            "list", "array" -> "array"
            "map", "object" -> "object"
            else -> "string" 
        }
    }

    
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
