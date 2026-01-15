package org.example.http

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.util.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach

@DisplayName("OpenWebUISchemaGenerator Tests")
class OpenWebUISchemaGeneratorTest {

    private lateinit var registry: ToolRegistry

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()
    }

    @Nested
    @DisplayName("Schema Generation Tests")
    inner class SchemaGenerationTests {

        @Test
        fun `generateToolSchemas returns array`() {
            val result = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertTrue(result is ArrayNode)
        }

        @Test
        fun `generates schema for single tool`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                shortDescription = "Test",
                longDescription = "A test tool",
                parameters = listOf(
                    ToolParam("input", "string", true, "Input parameter")
                ),
                paramsSpec = """{"type":"object","required":["input"],"properties":{"input":{"type":"string","description":"Input parameter"}}}""",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertEquals(1, schemas.size())

            val schema = schemas[0] as ObjectNode
            assertEquals("test_tool", schema.get("name").asText())
            assertEquals("Test", schema.get("description").asText())
        }

        @Test
        fun `generates schemas for multiple tools`() {
            val tool1 = ToolDefinition(
                name = "tool1",
                description = "Tool 1",
                shortDescription = "T1",
                longDescription = "Tool 1",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            val tool2 = ToolDefinition(
                name = "tool2",
                description = "Tool 2",
                shortDescription = "T2",
                longDescription = "Tool 2",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(tool1, ToolHandler { _, _ -> "result1" })
            registry.register(tool2, ToolHandler { _, _ -> "result2" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertEquals(2, schemas.size())
        }

        @Test
        fun `empty registry generates empty array`() {
            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertEquals(0, schemas.size())
        }
    }

    @Nested
    @DisplayName("OpenAI Function Format Tests")
    inner class OpenAIFormatTests {

        @Test
        fun `schema includes required fields`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "Test",
                shortDescription = "Test tool",
                longDescription = "A test tool",
                parameters = listOf(
                    ToolParam("param1", "string", true, "Parameter 1")
                ),
                paramsSpec = """{"type":"object","required":["param1"],"properties":{"param1":{"type":"string"}}}""",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            val schema = schemas[0] as ObjectNode

            assertTrue(schema.has("name"))
            assertTrue(schema.has("description"))
            assertTrue(schema.has("parameters"))
        }

        @Test
        fun `parameters field is object type`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = """{"type":"object","properties":{}}""",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            val schema = schemas[0] as ObjectNode
            val parameters = schema.get("parameters") as ObjectNode

            assertEquals("object", parameters.get("type").asText())
        }

        @Test
        fun `parameters include properties`() {
            val paramsSpec = """
            {
              "type": "object",
              "required": ["text"],
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Input text"
                }
              }
            }
            """

            val definition = ToolDefinition(
                name = "test_tool",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = listOf(
                    ToolParam("text", "string", true, "Input text")
                ),
                paramsSpec = paramsSpec.trim(),
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            val schema = schemas[0] as ObjectNode
            val parameters = schema.get("parameters") as ObjectNode

            assertTrue(parameters.has("properties"))
            val properties = parameters.get("properties") as ObjectNode
            assertTrue(properties.has("text"))
        }

        @Test
        fun `parameters include required array`() {
            val paramsSpec = """
            {
              "type": "object",
              "required": ["param1", "param2"],
              "properties": {
                "param1": {"type": "string"},
                "param2": {"type": "integer"}
              }
            }
            """

            val definition = ToolDefinition(
                name = "test_tool",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = listOf(
                    ToolParam("param1", "string", true),
                    ToolParam("param2", "integer", true)
                ),
                paramsSpec = paramsSpec.trim(),
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            val schema = schemas[0] as ObjectNode
            val parameters = schema.get("parameters") as ObjectNode

            assertTrue(parameters.has("required"))
            val required = parameters.get("required") as ArrayNode
            assertEquals(2, required.size())
        }
    }

    @Nested
    @DisplayName("Parameter Type Mapping Tests")
    inner class ParameterTypeMappingTests {

        @Test
        fun `maps string type correctly`() {
            val params = listOf(ToolParam("text", "string", true))
            assertParameterType(params, "text", "string")
        }

        @Test
        fun `maps integer types correctly`() {
            val intParams = listOf(ToolParam("num", "int", true))
            assertParameterType(intParams, "num", "integer")

            val integerParams = listOf(ToolParam("num", "integer", true))
            assertParameterType(integerParams, "num", "integer")

            val longParams = listOf(ToolParam("num", "long", true))
            assertParameterType(longParams, "num", "integer")
        }

        @Test
        fun `maps number types correctly`() {
            val doubleParams = listOf(ToolParam("num", "double", true))
            assertParameterType(doubleParams, "num", "number")

            val floatParams = listOf(ToolParam("num", "float", true))
            assertParameterType(floatParams, "num", "number")
        }

        @Test
        fun `maps boolean type correctly`() {
            val params = listOf(ToolParam("flag", "boolean", true))
            assertParameterType(params, "flag", "boolean")
        }

        @Test
        fun `maps array type correctly`() {
            val params = listOf(ToolParam("items", "array", true))
            assertParameterType(params, "items", "array")
        }

        @Test
        fun `maps object type correctly`() {
            val params = listOf(ToolParam("data", "object", true))
            assertParameterType(params, "data", "object")
        }

        private fun assertParameterType(params: List<ToolParam>, paramName: String, expectedType: String) {
            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = params,
                paramsSpec = buildParamsSpec(params),
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            val schema = schemas[0] as ObjectNode
            val parameters = schema.get("parameters") as ObjectNode
            val properties = parameters.get("properties") as ObjectNode
            val paramSchema = properties.get(paramName) as ObjectNode

            assertEquals(expectedType, paramSchema.get("type").asText())
        }

        private fun buildParamsSpec(params: List<ToolParam>): String {
            val props = params.joinToString(",") { param ->
                val mappedType = mapTypeToJsonSchema(param.type)
                """"${param.name}":{"type":"$mappedType"}"""
            }
            val required = params.filter { it.required }.joinToString(",") { """"${it.name}"""" }
            return """{"type":"object","required":[$required],"properties":{$props}}"""
        }

        private fun mapTypeToJsonSchema(type: String): String {
            return when (type.lowercase()) {
                "string" -> "string"
                "int", "integer", "long" -> "integer"
                "double", "float", "number" -> "number"
                "boolean", "bool" -> "boolean"
                "list", "array" -> "array"
                "map", "object" -> "object"
                else -> "string"
            }
        }
    }

    @Nested
    @DisplayName("Full Schema Document Tests")
    inner class FullSchemaDocumentTests {

        @Test
        fun `generateFullSchema returns object node`() {
            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            assertTrue(schema is ObjectNode)
        }

        @Test
        fun `full schema includes metadata fields`() {
            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)

            assertTrue(schema.has("version"))
            assertTrue(schema.has("generatedAt"))
            assertTrue(schema.has("format"))
            assertTrue(schema.has("description"))
            assertTrue(schema.has("tools"))
            assertTrue(schema.has("toolCount"))
        }

        @Test
        fun `full schema has correct format field`() {
            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            assertEquals("openai-function-calling", schema.get("format").asText())
        }

        @Test
        fun `full schema has correct version`() {
            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            assertEquals("1.0.0", schema.get("version").asText())
        }

        @Test
        fun `full schema includes tools array`() {
            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            val tools = schema.get("tools") as ArrayNode

            assertTrue(tools.size() > 0)
        }

        @Test
        fun `full schema has correct tool count`() {
            val tool1 = ToolDefinition(
                name = "tool1",
                description = "T1",
                shortDescription = "T1",
                longDescription = "T1",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            val tool2 = ToolDefinition(
                name = "tool2",
                description = "T2",
                shortDescription = "T2",
                longDescription = "T2",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(tool1, ToolHandler { _, _ -> "1" })
            registry.register(tool2, ToolHandler { _, _ -> "2" })

            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            assertEquals(2, schema.get("toolCount").asInt())
        }

        @Test
        fun `full schema includes generatedAt timestamp`() {
            val schema = OpenWebUISchemaGenerator.generateFullSchema(registry)
            val generatedAt = schema.get("generatedAt").asText()

            assertNotNull(generatedAt)
            assertTrue(generatedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")))
        }
    }

    @Nested
    @DisplayName("ParamsSpec Parsing Tests")
    inner class ParamsSpecParsingTests {

        @Test
        fun `parses valid JSON paramsSpec`() {
            val paramsSpec = """
            {
              "type": "object",
              "required": ["input"],
              "properties": {
                "input": {
                  "type": "string",
                  "description": "Input value"
                }
              }
            }
            """

            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = listOf(ToolParam("input", "string", true)),
                paramsSpec = paramsSpec.trim(),
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertEquals(1, schemas.size())

            val schema = schemas[0] as ObjectNode
            val parameters = schema.get("parameters") as ObjectNode

            assertEquals("object", parameters.get("type").asText())
            assertTrue(parameters.has("properties"))
            assertTrue(parameters.has("required"))
        }

        @Test
        fun `handles invalid paramsSpec gracefully`() {
            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = listOf(ToolParam("input", "string", true)),
                paramsSpec = "invalid json {",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            // Should fall back to generating from parameters list
            assertDoesNotThrow {
                OpenWebUISchemaGenerator.generateToolSchemas(registry)
            }
        }

        @Test
        fun `handles empty paramsSpec`() {
            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = "",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val schemas = OpenWebUISchemaGenerator.generateToolSchemas(registry)
            assertEquals(1, schemas.size())
        }
    }
}
