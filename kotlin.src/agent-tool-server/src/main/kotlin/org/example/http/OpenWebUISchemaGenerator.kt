package org.example.http

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.ToolDefinition
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.util.Json

/**
 * Generates OpenWebUI-compatible tool schemas for LLM function calling.
 *
 * **Why OpenWebUI format?**
 * OpenWebUI has adopted OpenAI's function calling specification as its standard for
 * tool definitions. This format has several key advantages:
 *
 * 1. **Industry standard**: OpenAI's format is the de facto standard for LLM function calling
 * 2. **Provider compatibility**: Works across OpenAI, Anthropic, Mistral, Cohere, local models
 * 3. **LiteLLM support**: LiteLLM transparently converts OpenAI format to provider-specific formats
 * 4. **Ecosystem adoption**: Most LLM frameworks and tools support OpenAI-compatible schemas
 *
 * **Schema structure:**
 * ```json
 * {
 *   "name": "semantic_search",
 *   "description": "Search documents using hybrid vector+BM25 search",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "query": {
 *         "type": "string",
 *         "description": "Search query"
 *       },
 *       "limit": {
 *         "type": "integer",
 *         "description": "Max results to return"
 *       }
 *     },
 *     "required": ["query"]
 *   }
 * }
 * ```
 *
 * The generated schemas are consumed by:
 * - OpenAIProxyHandler: Injects tools into LLM requests
 * - ToolsSchemaHandler: Exposes schemas via `/tools.json` for discovery
 * - External clients: Use schemas to understand available tools and parameters
 */
object OpenWebUISchemaGenerator {

    /**
     * Generates an array of tool schemas for all tools in the registry.
     *
     * Each tool is converted to OpenAI function calling format with:
     * - name: Tool identifier (used in function calls)
     * - description: Human-readable description (helps LLM decide when to use tool)
     * - parameters: JSON Schema defining expected arguments
     *
     * @param registry The ToolRegistry containing all registered plugin tools
     * @return ArrayNode containing schema objects for each tool
     */
    fun generateToolSchemas(registry: ToolRegistry): ArrayNode {
        val tools = ArrayNode(JsonNodeFactory.instance)

        registry.listTools().forEach { tool ->
            tools.add(generateToolSchema(tool))
        }

        return tools
    }

    /**
     * Generates a single tool schema from a ToolDefinition.
     *
     * Converts internal ToolDefinition format (from plugin manifests) to
     * OpenAI function calling format. The schema includes the tool name,
     * description, and parameter specifications.
     *
     * @param tool The tool definition to convert
     * @return ObjectNode containing the tool's OpenAI-compatible schema
     */
    private fun generateToolSchema(tool: ToolDefinition): ObjectNode {
        val schema = JsonNodeFactory.instance.objectNode()

        schema.put("name", tool.name)
        schema.put("description", tool.shortDescription)

        // Build JSON Schema for parameters from tool definition
        val parameters = buildParametersSchema(tool.parameters, tool.paramsSpec)
        schema.set<ObjectNode>("parameters", parameters)

        return schema
    }

    /**
     * Builds a JSON Schema for tool parameters.
     *
     * Attempts to use the pre-defined paramsSpec from the tool definition if available.
     * This allows plugins to provide rich schemas with validation rules, enums, etc.
     *
     * If paramsSpec is not available or invalid, falls back to generating a basic
     * schema from the tool's parameter list (name, type, required, description).
     *
     * The resulting schema follows JSON Schema Draft 7 specification and is compatible
     * with OpenAI's function calling format.
     *
     * @param params List of parameter definitions from the tool
     * @param paramsSpec JSON string containing a pre-defined JSON Schema (can be empty)
     * @return ObjectNode containing the JSON Schema for parameters
     */
    private fun buildParametersSchema(params: List<ToolParam>, paramsSpec: String): ObjectNode {
        // Prefer pre-defined JSON schema from plugin manifest if available
        // This allows plugins to provide richer schemas with validation, enums, etc.
        if (paramsSpec.isNotBlank()) {
            try {
                val parsed = Json.mapper.readTree(paramsSpec)
                if (parsed is ObjectNode) {
                    return parsed
                }
            } catch (e: Exception) {
                // Fall through to generated schema if parsing fails
            }
        }

        // Generate basic JSON Schema from parameter list
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
     * Maps Kotlin type names to JSON Schema types.
     *
     * Converts type names from plugin parameter definitions to JSON Schema type strings.
     * This enables LLMs to understand what type of data each parameter expects.
     *
     * Supported mappings:
     * - string → "string"
     * - int, integer, long → "integer"
     * - double, float, number → "number"
     * - boolean, bool → "boolean"
     * - list, array → "array"
     * - map, object → "object"
     * - unknown types → "string" (safe default)
     *
     * @param kotlinType The Kotlin type name (case-insensitive)
     * @return The corresponding JSON Schema type string
     */
    private fun mapKotlinTypeToJsonType(kotlinType: String): String {
        return when (kotlinType.lowercase()) {
            "string" -> "string"
            "int", "integer", "long" -> "integer"
            "double", "float", "number" -> "number"
            "boolean", "bool" -> "boolean"
            "list", "array" -> "array"
            "map", "object" -> "object"
            else -> "string"  // Default to string for unknown types
        }
    }

    /**
     * Generates a complete schema document with metadata.
     *
     * This is the top-level schema exposed via `/tools.json` endpoint.
     * Includes metadata about schema version, generation time, and format,
     * along with the array of tool schemas.
     *
     * Used by:
     * - OpenWebUI for tool discovery and configuration
     * - External clients to understand available tools
     * - Documentation generation
     *
     * Example output:
     * ```json
     * {
     *   "version": "1.0.0",
     *   "generatedAt": "2025-02-06T10:30:00Z",
     *   "format": "openai-function-calling",
     *   "description": "Agent tool schemas for Open WebUI integration",
     *   "tools": [...],
     *   "toolCount": 42
     * }
     * ```
     *
     * @param registry The ToolRegistry containing all registered plugin tools
     * @return ObjectNode containing the complete schema document
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
