package org.example.host

import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.manifest.PluginManifest
import org.example.testplugins.TestPlugin
import org.example.util.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolRegistryTest {
    private fun loadedPlugin(pluginId: String = "tp1"): LoadedPlugin {
        val manifest = PluginManifest(
            id = pluginId,
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = emptyList(),
            requires = null
        )
        return LoadedPlugin(manifest, this::class.java.classLoader, TestPlugin())
    }

    @Test
    fun `discovers tools and metadata`() {
        val reg = ToolRegistry()
        reg.registerFrom(loadedPlugin())
        val names = reg.listTools().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("sum", "greet", "echoDto")))
        val def = reg.listTools().first { it.name == "sum" }
        assertEquals("tp1", def.pluginId)
        assertEquals(2, def.parameters.size)
    }

    @Test
    fun `invocation with multiple params`() {
        val reg = ToolRegistry()
        reg.registerFrom(loadedPlugin())
        val args: ObjectNode = Json.mapper.createObjectNode().put("a", 2).put("b", 3)
        val result = reg.invoke("sum", args)
        assertEquals(5, result)
    }

    @Test
    fun `invocation with DTO param`() {
        val reg = ToolRegistry()
        reg.registerFrom(loadedPlugin())
        val args: ObjectNode = Json.mapper.createObjectNode().put("x", 7).put("ok", true)
        val result = reg.invoke("echoDto", args)
        assertEquals("7:true", result)
    }

    @Test
    fun `optional param uses default when missing`() {
        val reg = ToolRegistry()
        reg.registerFrom(loadedPlugin())
        val args: ObjectNode = Json.mapper.createObjectNode() // no name provided
        val result = reg.invoke("greet", args)
        assertEquals("Hello, world", result)
    }

    @Test
    fun `missing required param throws`() {
        val reg = ToolRegistry()
        reg.registerFrom(loadedPlugin())
        val args: ObjectNode = Json.mapper.createObjectNode().put("a", 1)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            reg.invoke("sum", args)
        }
        assertTrue(ex.message!!.contains("Missing required parameter"))
    }
}
