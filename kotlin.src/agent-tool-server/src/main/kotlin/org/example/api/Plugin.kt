package org.example.api

import org.example.manifest.PluginManifest
import org.example.host.ToolRegistry

/**
 * Core plugin interface enabling JAR-based extensibility for the agent-tool-server.
 *
 * Plugins expose LLM-callable tools that integrate with the broader Datamancy stack
 * (PostgreSQL, Qdrant, Search-Service, Docker, etc.) via OpenAI function calling format.
 *
 * ## Plugin Lifecycle
 * 1. **Load**: PluginManager discovers JARs in plugins directory
 * 2. **Init**: `init()` called with host context (version, config)
 * 3. **Register**: `registerTools()` publishes tools to ToolRegistry
 * 4. **Invoke**: LLMs call tools via HTTP `/call-tool` endpoint
 * 5. **Shutdown**: `shutdown()` called on graceful termination
 *
 * ## Why Factory Pattern?
 * Plugins are loaded from external JARs at runtime. The factory pattern (PluginFactories)
 * allows the host to instantiate plugin classes without compile-time dependencies,
 * enabling true hot-pluggable architecture.
 *
 * ## Example Plugin Categories
 * - **CoreToolsPlugin**: String/JSON/math utilities (no external access)
 * - **DataSourceQueryPlugin**: Read-only database queries with shadow accounts
 * - **BrowserToolsPlugin**: Headless browser automation via Playwright
 * - **LlmCompletionPlugin**: LLM chat and embedding generation via LiteLLM
 *
 * @see PluginContext
 * @see org.example.host.PluginManager
 * @see org.example.host.ToolRegistry
 */
interface Plugin {
    /**
     * Returns the plugin's manifest defining metadata, capabilities, and version constraints.
     *
     * Manifest is read from `llm-plugin.json` in the plugin JAR and validated against
     * host version and capability policy before plugin initialization.
     *
     * @return PluginManifest with id, version, capabilities, and version constraints
     */
    fun manifest(): PluginManifest

    /**
     * Initializes the plugin with host context.
     *
     * Called once after manifest validation and capability enforcement, before tool registration.
     * Plugins should establish connections to external services (databases, APIs) here.
     *
     * @param context Host version, API version, and configuration map
     */
    fun init(context: PluginContext)

    /**
     * Gracefully shuts down the plugin and releases resources.
     *
     * Called when the host terminates or unloads the plugin. Plugins should close
     * database connections, HTTP clients, and other resources here.
     *
     * Default implementation does nothing (no-op for stateless plugins).
     */
    fun shutdown() {}

    /**
     * Returns tool instances for introspection (optional).
     *
     * Legacy method for returning tool objects. Modern plugins should use
     * `registerTools()` instead to directly register tools with ToolRegistry.
     *
     * @return List of tool instances (typically empty)
     */
    fun tools(): List<Any> = emptyList()

    /**
     * Registers LLM-callable tools with the host's ToolRegistry.
     *
     * This is where plugins expose functionality to LLMs via OpenAI function calling.
     * Tools are annotated with @LlmTool to define name, description, and parameters
     * in a format compatible with OpenAI's function calling API.
     *
     * ## Example Registration
     * ```kotlin
     * override fun registerTools(registry: ToolRegistry) {
     *     registry.register(
     *         definition = ToolDefinition(
     *             name = "semantic_search",
     *             description = "Search Datamancy knowledge base",
     *             parameters = listOf(...)
     *         ),
     *         handler = ToolHandler { args, userContext ->
     *             // Call Search-Service HTTP API
     *             searchService.search(args["query"].asText())
     *         }
     *     )
     * }
     * ```
     *
     * @param registry ToolRegistry that bridges LLMs and tool implementations
     */
    fun registerTools(registry: ToolRegistry) {  }
}

/**
 * Context passed to plugins during initialization.
 *
 * Provides version information for compatibility checking and configuration
 * for external service endpoints (database URLs, API keys, etc.).
 *
 * @property hostVersion SemVer version of agent-tool-server host (e.g., "1.0.0")
 * @property apiVersion SemVer version of Plugin API (e.g., "1.0.0")
 * @property config Configuration map from host (database URLs, service endpoints, etc.)
 */
data class PluginContext(
    val hostVersion: String,
    val apiVersion: String,
    val config: Map<String, Any?> = emptyMap()
)
