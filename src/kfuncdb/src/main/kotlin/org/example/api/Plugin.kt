package org.example.api

import org.example.manifest.PluginManifest
import org.example.host.ToolRegistry

/**
 * Plugin API exposed to plugin implementors.
 */
interface Plugin {
    /** Return the plugin manifest with metadata. */
    fun manifest(): PluginManifest

    /** Called once when the plugin is constructed and added to the host. */
    fun init(context: PluginContext)

    /** Called on host shutdown. */
    fun shutdown() {}

    /**
     * Return a list of tool container objects. Methods annotated with @LlmTool
     * will be discovered and exposed via the host.
     */
    fun tools(): List<Any> = emptyList()

    /**
     * Non-reflective tool registration hook. Implementors can register tools explicitly
     * via ToolRegistry without relying on annotations or reflection.
     */
    fun registerTools(registry: ToolRegistry) { /* default no-op */ }
}

data class PluginContext(
    val hostVersion: String,
    val apiVersion: String,
    val config: Map<String, Any?> = emptyMap()
)
