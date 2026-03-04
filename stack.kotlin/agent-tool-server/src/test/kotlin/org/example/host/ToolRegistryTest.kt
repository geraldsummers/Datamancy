package org.example.host

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertFailsWith

@DisplayName("ToolRegistry Tests")
class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()
    }

    @Nested
    @DisplayName("Tool Registration")
    inner class ToolRegistrationTests {

        @Test
        fun `can register a tool`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                shortDescription = "Test",
                longDescription = "A longer test description",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            val handler = ToolHandler { _, _ -> "result" }

            assertDoesNotThrow {
                registry.register(definition, handler)
            }
        }

        @Test
        fun `can register multiple tools`() {
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

            val tools = registry.listTools()
            assertEquals(2, tools.size)
        }

        @Test
        fun `registered tools appear in list`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test tool",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val tools = registry.listTools()
            assertEquals(1, tools.size)
            assertEquals("test_tool", tools[0].name)
        }
    }

    @Nested
    @DisplayName("Tool Invocation")
    inner class ToolInvocationTests {

        @Test
        fun `can invoke registered tool`() {
            val definition = ToolDefinition(
                name = "echo",
                description = "Echo",
                shortDescription = "Echo",
                longDescription = "Echo tool",
                parameters = listOf(
                    ToolParam("message", "string", true, "Message to echo")
                ),
                paramsSpec = """{"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}""",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { args, _ ->
                args.get("message")?.asText() ?: "no message"
            })

            val args = JsonNodeFactory.instance.objectNode()
            args.put("message", "Hello")

            val result = registry.invoke("echo", args)
            assertEquals("Hello", result)
        }

        @Test
        fun `throws on non-existent tool`() {
            val args = JsonNodeFactory.instance.objectNode()

            assertFailsWith<NoSuchElementException> {
                registry.invoke("nonexistent", args)
            }
        }

        @Test
        fun `supports user context parameter`() {
            val definition = ToolDefinition(
                name = "context_test",
                description = "Test user context",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, userContext ->
                "User: ${userContext ?: "anonymous"}"
            })

            val args = JsonNodeFactory.instance.objectNode()

            val result1 = registry.invoke("context_test", args, null)
            assertEquals("User: anonymous", result1)

            val result2 = registry.invoke("context_test", args, "user123")
            assertEquals("User: user123", result2)
        }
    }

    @Nested
    @DisplayName("Tool Parameters")
    inner class ToolParametersTests {

        @Test
        fun `tool definition includes parameters`() {
            val params = listOf(
                ToolParam("name", "string", true, "User name"),
                ToolParam("age", "integer", false, "User age")
            )

            val definition = ToolDefinition(
                name = "create_user",
                description = "Create user",
                shortDescription = "Create",
                longDescription = "Create a user",
                parameters = params,
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"},"age":{"type":"integer"}}}""",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "created" })

            val tools = registry.listTools()
            val tool = tools.find { it.name == "create_user" }

            assertNotNull(tool)
            assertEquals(2, tool!!.parameters.size)
            assertTrue(tool.parameters[0].required)
            assertFalse(tool.parameters[1].required)
        }

        @Test
        fun `paramsSpec is included in definition`() {
            val paramsSpec = """{"type":"object","properties":{"input":{"type":"string"}}}"""

            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = paramsSpec,
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ -> "ok" })

            val tools = registry.listTools()
            assertEquals(paramsSpec, tools[0].paramsSpec)
        }
    }

    @Nested
    @DisplayName("Tool Metadata")
    inner class ToolMetadataTests {

        @Test
        fun `tool definition includes all metadata fields`() {
            val definition = ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                shortDescription = "Test",
                longDescription = "A longer description of the test tool",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "org.example.test"
            )

            registry.register(definition, ToolHandler { _, _ -> "result" })

            val tools = registry.listTools()
            val tool = tools[0]

            assertEquals("test_tool", tool.name)
            assertEquals("A test tool", tool.description)
            assertEquals("Test", tool.shortDescription)
            assertEquals("A longer description of the test tool", tool.longDescription)
            assertEquals("org.example.test", tool.pluginId)
        }
    }

    @Nested
    @DisplayName("Tool Handler Execution")
    inner class ToolHandlerExecutionTests {

        @Test
        fun `handler receives correct arguments`() {
            var receivedArgs: com.fasterxml.jackson.databind.JsonNode? = null

            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { args, _ ->
                receivedArgs = args
                "ok"
            })

            val args = JsonNodeFactory.instance.objectNode()
            args.put("key", "value")

            registry.invoke("test", args)

            assertNotNull(receivedArgs)
            assertEquals("value", receivedArgs!!.get("key").asText())
        }

        @Test
        fun `handler receives user context`() {
            var receivedContext: String? = null

            val definition = ToolDefinition(
                name = "test",
                description = "Test",
                shortDescription = "Test",
                longDescription = "Test",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, userContext ->
                receivedContext = userContext
                "ok"
            })

            val args = JsonNodeFactory.instance.objectNode()
            registry.invoke("test", args, "user123")

            assertEquals("user123", receivedContext)
        }

        @Test
        fun `handler can return various types`() {
            val stringDef = ToolDefinition(
                name = "return_string",
                description = "Returns string",
                shortDescription = "String",
                longDescription = "Returns a string",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )
            registry.register(stringDef, ToolHandler { _, _ -> "text" })

            val intDef = ToolDefinition(
                name = "return_int",
                description = "Returns int",
                shortDescription = "Int",
                longDescription = "Returns an integer",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )
            registry.register(intDef, ToolHandler { _, _ -> 42 })

            val mapDef = ToolDefinition(
                name = "return_map",
                description = "Returns map",
                shortDescription = "Map",
                longDescription = "Returns a map",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )
            registry.register(mapDef, ToolHandler { _, _ -> mapOf("key" to "value") })

            val args = JsonNodeFactory.instance.objectNode()

            assertEquals("text", registry.invoke("return_string", args))
            assertEquals(42, registry.invoke("return_int", args))
            assertEquals(mapOf("key" to "value"), registry.invoke("return_map", args))
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `handler exceptions are propagated`() {
            val definition = ToolDefinition(
                name = "throwing_tool",
                description = "Throws exception",
                shortDescription = "Throws",
                longDescription = "Throws an exception",
                parameters = emptyList(),
                paramsSpec = "{}",
                pluginId = "test.plugin"
            )

            registry.register(definition, ToolHandler { _, _ ->
                throw IllegalStateException("Test error")
            })

            val args = JsonNodeFactory.instance.objectNode()

            val exception = assertThrows(IllegalStateException::class.java) {
                registry.invoke("throwing_tool", args)
            }

            assertEquals("Test error", exception.message)
        }
    }

    @Nested
    @DisplayName("Empty Registry")
    inner class EmptyRegistryTests {

        @Test
        fun `listTools returns empty list for new registry`() {
            val emptyRegistry = ToolRegistry()
            val tools = emptyRegistry.listTools()
            assertEquals(0, tools.size)
        }

        @Test
        fun `invoke throws on empty registry`() {
            val emptyRegistry = ToolRegistry()
            val args = JsonNodeFactory.instance.objectNode()

            assertFailsWith<NoSuchElementException> {
                emptyRegistry.invoke("any_tool", args)
            }
        }
    }
}
