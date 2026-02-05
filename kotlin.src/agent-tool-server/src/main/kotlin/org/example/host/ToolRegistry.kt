package org.example.host

import com.fasterxml.jackson.databind.JsonNode
import org.example.util.Json

/**
 * Parameter definition for a tool in OpenAI function calling format.
 *
 * Extracted from @LlmTool annotations and exposed to LLMs via tool listing endpoint.
 * LLMs use these definitions to understand what arguments to provide when calling tools.
 *
 * @property name Parameter name (e.g., "query", "database", "limit")
 * @property type JSON Schema type (e.g., "string", "integer", "boolean", "array", "object")
 * @property required Whether parameter is mandatory (affects "required" array in OpenAI spec)
 * @property description Human-readable explanation of parameter purpose and constraints
 *
 * @see ToolDefinition
 */
data class ToolParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String = ""
)

/**
 * Complete tool definition in OpenAI function calling format.
 *
 * This is the bridge between plugin implementations and LLM function calling.
 * ToolRegistry exposes these definitions via HTTP `/tools` endpoint, and LiteLLM
 * proxy injects them into LLM context as available functions.
 *
 * ## OpenAI Function Calling Format
 * When serialized for LLMs, ToolDefinition becomes:
 * ```json
 * {
 *   "name": "semantic_search",
 *   "description": "Search Datamancy knowledge base for relevant documents",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "query": {"type": "string", "description": "Search query"},
 *       "limit": {"type": "integer", "description": "Max results"}
 *     },
 *     "required": ["query"]
 *   }
 * }
 * ```
 *
 * ## Integration with LLMs
 * LLMs see these definitions and decide when to call tools:
 * - LLM receives user query: "Find Kubernetes documentation"
 * - LLM analyzes available tools and selects `semantic_search`
 * - LLM returns tool call: `{"name": "semantic_search", "arguments": "{\"query\":\"kubernetes\"}"}`
 * - Agent-tool-server invokes tool via ToolRegistry
 * - Result is injected back into conversation for LLM to synthesize answer
 *
 * @property name Unique tool identifier (e.g., "semantic_search", "query_postgres")
 * @property description Primary description for LLM (usually longDescription from @LlmTool)
 * @property shortDescription Brief one-line summary for tool listing UIs
 * @property longDescription Detailed explanation with usage examples and limitations
 * @property parameters List of ToolParam defining function arguments
 * @property paramsSpec Raw JSON Schema string (OpenAI format) for parameters
 * @property pluginId Plugin that provides this tool (for debugging and audit)
 *
 * @see ToolParam
 * @see ToolRegistry
 * @see org.example.api.LlmTool
 */
data class ToolDefinition(
    val name: String,

    val description: String,
    val shortDescription: String,
    val longDescription: String,
    val parameters: List<ToolParam>,

    val paramsSpec: String,
    val pluginId: String
)

/**
 * Functional interface for tool execution handlers.
 *
 * ToolHandler wraps the actual tool implementation, receiving parsed JSON arguments
 * and optional user context for multi-tenancy.
 *
 * ## User Context for Multi-Tenancy
 * The userContext parameter enables per-user isolation:
 * - HTTP header `X-User-Context` passed from LLM proxy
 * - Used to select shadow account for database queries (e.g., "alice" → "alice-agent")
 * - Enables user-specific rate limiting, audit logs, and access control
 *
 * ## Example Handler
 * ```kotlin
 * val handler = ToolHandler { args, userContext ->
 *     val query = args["query"].asText()
 *     val user = userContext ?: "anonymous"
 *     // Use user-specific shadow account for query
 *     val shadowAccount = "$user-agent"
 *     postgresClient.query(shadowAccount, query)
 * }
 * ```
 *
 * @see ToolRegistry.register
 * @see ToolRegistry.invoke
 */
fun interface ToolHandler {
    /**
     * Executes the tool with provided arguments and user context.
     *
     * @param args Parsed JSON arguments from LLM (JsonNode for flexible access)
     * @param userContext Optional user identifier for multi-tenancy (from X-User-Context header)
     * @return Tool result (String, Map, List, or any JSON-serializable type)
     */
    fun call(args: JsonNode, userContext: String?): Any?
}

/**
 * Central registry bridging LLMs and plugin tool implementations.
 *
 * ToolRegistry is the heart of the agent-tool-server's function calling system.
 * It receives tool registrations from plugins during initialization, exposes tool
 * definitions to LLMs via HTTP API, and routes tool invocations to plugin handlers.
 *
 * ## Role in Plugin Architecture
 *
 * ToolRegistry sits between three layers:
 * 1. **Plugins** (tool providers): Register tools during `plugin.registerTools(registry)`
 * 2. **HTTP API** (tool exposure): Exposes tools via `/tools` endpoint for LLM discovery
 * 3. **LLMs** (tool consumers): Call tools via `/call-tool` endpoint
 *
 * ```
 * ┌─────────────────┐
 * │   Plugins       │ (DataSourceQueryPlugin, BrowserToolsPlugin, etc.)
 * └────────┬────────┘
 *          │ plugin.registerTools(registry)
 *          ▼
 * ┌─────────────────┐
 * │  ToolRegistry   │ (this class)
 * └────┬───────┬────┘
 *      │       │
 *      │       └──────── GET /tools → listTools()
 *      │
 *      └──────────────── POST /call-tool → invoke()
 *                               ▲
 *                               │
 *                        ┌──────┴──────┐
 *                        │   LiteLLM   │ (LLM proxy with function calling)
 *                        └─────────────┘
 * ```
 *
 * ## OpenAI Function Calling Integration
 *
 * The flow for LLM tool usage:
 *
 * 1. **Tool Discovery**:
 *    - LiteLLM calls `GET /tools`
 *    - ToolRegistry returns `listTools()` in OpenAI format
 *    - LiteLLM injects tools into LLM context
 *
 * 2. **Tool Invocation**:
 *    - LLM returns: `{"tool_calls": [{"function": {"name": "semantic_search", "arguments": "..."}}]}`
 *    - LiteLLM calls `POST /call-tool {"name": "semantic_search", "args": {...}}`
 *    - ToolRegistry routes to plugin handler via `invoke()`
 *
 * 3. **Result Injection**:
 *    - Handler returns result (e.g., search results from Datamancy knowledge base)
 *    - LiteLLM injects as "tool" role message
 *    - LLM synthesizes final answer from tool results
 *
 * ## Multi-Tenancy via User Context
 *
 * Tools can be user-specific by leveraging userContext parameter:
 * ```kotlin
 * // HTTP request includes X-User-Context header
 * POST /call-tool
 * X-User-Context: alice
 * {"name": "query_postgres", "args": {"query": "SELECT ..."}}
 *
 * // ToolRegistry passes userContext to handler
 * handler.call(args, userContext = "alice")
 *
 * // Handler uses user-specific shadow account
 * val shadowAccount = "alice-agent"  // Limited permissions
 * postgresClient.connect(shadowAccount, shadowPassword)
 * ```
 *
 * This prevents privilege escalation and enables per-user audit trails.
 *
 * ## Integration with Datamancy Stack
 *
 * ToolRegistry doesn't interact directly with external services. Instead, it:
 * - Routes calls to plugin handlers
 * - Plugins interact with stack services (PostgreSQL, Qdrant, Search-Service, Docker, etc.)
 * - Handlers receive service URLs from PluginContext during plugin init
 *
 * Example tool calling stack service:
 * ```kotlin
 * // Plugin registers tool during init
 * registry.register(
 *     definition = ToolDefinition(name = "semantic_search", ...),
 *     handler = ToolHandler { args, userContext ->
 *         // Handler calls Search-Service HTTP API
 *         val searchUrl = config["search.url"]  // From PluginContext
 *         httpClient.post("$searchUrl/search", args)
 *     }
 * )
 * ```
 *
 * @see ToolDefinition
 * @see ToolHandler
 * @see org.example.api.Plugin.registerTools
 * @see org.example.host.PluginManager
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, ToolHandler>()
    private val defs = mutableListOf<ToolDefinition>()

    /**
     * Registers a tool with its definition and execution handler.
     *
     * Called by plugins during `plugin.registerTools(registry)` phase of plugin lifecycle.
     * Tools must have unique names (duplicate names overwrite previous registrations).
     *
     * ## Example Registration
     * ```kotlin
     * override fun registerTools(registry: ToolRegistry) {
     *     registry.register(
     *         definition = ToolDefinition(
     *             name = "semantic_search",
     *             description = "Search Datamancy knowledge base",
     *             shortDescription = "Search knowledge base",
     *             longDescription = "Performs hybrid vector+BM25 search...",
     *             parameters = listOf(
     *                 ToolParam("query", "string", required = true, "Search query"),
     *                 ToolParam("limit", "integer", required = false, "Max results")
     *             ),
     *             paramsSpec = """{"type":"object","properties":{...}}""",
     *             pluginId = manifest().id
     *         ),
     *         handler = ToolHandler { args, userContext ->
     *             searchService.search(args["query"].asText(), args["limit"]?.asInt() ?: 10)
     *         }
     *     )
     * }
     * ```
     *
     * @param definition OpenAI-compatible tool definition for LLM discovery
     * @param handler Execution function that receives JSON args and user context
     */
    fun register(definition: ToolDefinition, handler: ToolHandler) {
        defs += definition
        tools[definition.name] = handler
    }

    /**
     * Returns all registered tool definitions in OpenAI function calling format.
     *
     * Exposed via HTTP `GET /tools` endpoint for LLM discovery. LiteLLM proxy
     * calls this endpoint and injects tools into LLM context.
     *
     * ## Example HTTP Response
     * ```json
     * [
     *   {
     *     "name": "semantic_search",
     *     "description": "Search Datamancy knowledge base",
     *     "parameters": {"type": "object", "properties": {...}}
     *   },
     *   {
     *     "name": "query_postgres",
     *     "description": "Execute read-only SQL on PostgreSQL",
     *     "parameters": {"type": "object", "properties": {...}}
     *   }
     * ]
     * ```
     *
     * @return Immutable list of all tool definitions
     */
    fun listTools(): List<ToolDefinition> = defs.toList()

    /**
     * Invokes a tool by name with provided arguments and optional user context.
     *
     * Called by HTTP `POST /call-tool` endpoint when LLMs decide to use a tool.
     * Routes the call to the registered handler and returns the result.
     *
     * ## Example Invocation Flow
     * ```
     * 1. LLM returns: {"tool_calls": [{"function": {"name": "semantic_search", ...}}]}
     * 2. LiteLLM calls: POST /call-tool {"name": "semantic_search", "args": {"query": "k8s"}}
     * 3. HTTP handler calls: registry.invoke("semantic_search", argsNode, "alice")
     * 4. ToolRegistry routes to plugin handler
     * 5. Handler calls Search-Service HTTP API
     * 6. Result returned to LiteLLM: ["doc1", "doc2", "doc3"]
     * 7. LiteLLM injects as tool message for LLM to synthesize answer
     * ```
     *
     * ## User Context for Multi-Tenancy
     * The userContext parameter enables user-specific behavior:
     * - Shadow account selection for database queries
     * - Per-user rate limiting
     * - Audit logging with user attribution
     *
     * @param name Tool name to invoke (must be registered)
     * @param args JSON arguments from LLM (parsed from "arguments" field)
     * @param userContext Optional user identifier from X-User-Context header
     * @return Tool execution result (String, Map, List, or any JSON-serializable type)
     * @throws NoSuchElementException if tool name is not registered
     */
    fun invoke(name: String, args: JsonNode, userContext: String? = null): Any? {
        val handler = tools[name] ?: throw NoSuchElementException("Tool not found: $name")
        return handler.call(args, userContext)
    }
}
