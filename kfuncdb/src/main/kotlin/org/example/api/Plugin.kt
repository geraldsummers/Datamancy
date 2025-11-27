package org.example.api

/**
 * Plugin API exposed to plugin implementors.
 */
interface Plugin {
    /** Called once when the plugin is constructed and added to the host. */
    fun init(context: PluginContext)

    /** Called on host shutdown. */
    fun shutdown() {}

    /**
     * Return a list of tool container objects. Methods annotated with @LlmTool
     * will be discovered and exposed via the host.
     */
    fun tools(): List<Any> = emptyList()
}

data class PluginContext(
    val hostVersion: String,
    val apiVersion: String,
    val config: Map<String, Any?> = emptyMap()
)
