package org.example.api

/**
 * Annotation for documenting individual tool parameters in LLM function calls.
 *
 * Used within @LlmTool annotations to provide human-readable parameter documentation
 * that supplements the JSON Schema definition in paramsSpec.
 *
 * @property name Parameter name (must match JSON Schema property name)
 * @property description Human-readable explanation of parameter purpose and constraints
 *
 * @see LlmTool
 */
annotation class LlmToolParamDoc(
    val name: String,
    val description: String
)

/**
 * Annotation system for exposing Kotlin functions as LLM-callable tools via OpenAI function calling.
 *
 * This annotation bridges the gap between plugin implementations and LLM function calling format.
 * The ToolRegistry extracts these annotations at runtime to generate OpenAI-compatible tool
 * definitions that LLMs can invoke via HTTP `/call-tool` endpoint.
 *
 * ## OpenAI Function Calling Integration
 * LLMs (via LiteLLM proxy) receive tool definitions in this format:
 * ```json
 * {
 *   "name": "semantic_search",
 *   "description": "Search Datamancy knowledge base for documents...",
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
 * When the LLM decides to call a tool, it returns:
 * ```json
 * {
 *   "role": "assistant",
 *   "tool_calls": [{
 *     "function": {"name": "semantic_search", "arguments": "{\"query\":\"k8s\",\"limit\":5}"}
 *   }]
 * }
 * ```
 *
 * The agent-tool-server's OpenAIProxyHandler intercepts this, calls `/call-tool`, and
 * injects the result back into the conversation as a "tool" role message.
 *
 * ## Parameter Schema Format
 * The `paramsSpec` property must be valid JSON Schema (OpenAI function calling format):
 * - Use "type": "object" as root
 * - Define "properties" with typed fields (string, integer, boolean, array, object)
 * - Specify "required" array for mandatory parameters
 * - Add "description" to each property for LLM guidance
 *
 * ## Example Usage
 * ```kotlin
 * @LlmTool(
 *     name = "query_postgres",
 *     shortDescription = "Execute read-only SQL query on PostgreSQL",
 *     longDescription = "Queries PostgreSQL using shadow accounts for security isolation...",
 *     paramsSpec = """
 *     {
 *       "type": "object",
 *       "properties": {
 *         "query": {"type": "string", "description": "SELECT statement"},
 *         "database": {"type": "string", "description": "Target database"}
 *       },
 *       "required": ["query", "database"]
 *     }
 *     """,
 *     params = [
 *         LlmToolParamDoc("query", "SQL SELECT statement (no mutations allowed)"),
 *         LlmToolParamDoc("database", "Database name: grafana, planka, mastodon, forgejo")
 *     ]
 * )
 * fun queryPostgres(args: JsonNode): String {
 *     // Implementation with shadow account and query validation
 * }
 * ```
 *
 * @property name Tool name (unique identifier, defaults to function name if empty)
 * @property shortDescription Brief one-line description for tool listing
 * @property longDescription Detailed explanation for LLM context (usage, limitations, examples)
 * @property paramsSpec JSON Schema defining parameters (OpenAI function calling format)
 * @property params Array of LlmToolParamDoc for human-readable parameter documentation
 *
 * @see LlmToolParamDoc
 * @see org.example.host.ToolRegistry
 * @see org.example.host.ToolDefinition
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmTool(
    val name: String = "",

    val shortDescription: String,

    val longDescription: String,

    val paramsSpec: String,

    val params: Array<LlmToolParamDoc> = []
)
