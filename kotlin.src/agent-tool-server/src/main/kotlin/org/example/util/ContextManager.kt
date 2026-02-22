package org.example.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.ToolDefinition
import java.io.File

/**
 * Context management utilities for agent orchestration.
 *
 * Handles conversation history management, token budgeting, and system prompt injection
 * for multi-iteration agent loops.
 */
object ContextManager {

    /**
     * Approximate token count based on character count.
     * Rule of thumb: 1 token ≈ 4 characters for English text.
     *
     * @param text The text to count tokens for
     * @return Approximate token count
     */
    fun estimateTokenCount(text: String): Int {
        return (text.length / 4.0).toInt()
    }

    /**
     * Estimate token count for a conversation message array.
     *
     * @param messages JSON array of message objects with role and content
     * @return Approximate total token count
     */
    fun estimateConversationTokens(messages: ArrayNode): Int {
        var total = 0
        messages.forEach { message ->
            val content = message.get("content")?.asText() ?: ""
            total += estimateTokenCount(content)

            // Tool calls add overhead
            val toolCalls = message.get("tool_calls")
            if (toolCalls != null && toolCalls.isArray) {
                toolCalls.forEach { toolCall ->
                    val function = toolCall.get("function")
                    val args = function?.get("arguments")?.asText() ?: ""
                    total += estimateTokenCount(args) + 10  // +10 for function call overhead
                }
            }
        }
        return total
    }

    /**
     * Truncate conversation to fit within token budget.
     *
     * Strategy: Keep system message (first) + first N user messages + last M messages
     * This preserves initial context and recent conversation while staying under budget.
     *
     * @param messages JSON array of message objects
     * @param maxTokens Maximum token budget
     * @return Truncated message array
     */
    fun truncateConversation(messages: ArrayNode, maxTokens: Int): ArrayNode {
        val result = Json.mapper.createArrayNode()

        if (messages.isEmpty) return result

        // Always keep system message if present
        val firstMsg = messages[0]
        if (firstMsg.get("role")?.asText() == "system") {
            result.add(firstMsg)
            val systemTokens = estimateTokenCount(firstMsg.get("content")?.asText() ?: "")

            if (systemTokens >= maxTokens) {
                // System message alone exceeds budget - truncate it
                val truncatedContent = truncateText(
                    firstMsg.get("content")?.asText() ?: "",
                    maxTokens * 4  // Convert tokens back to chars
                )
                val truncatedMsg = (firstMsg as ObjectNode).deepCopy()
                truncatedMsg.put("content", truncatedContent)
                result.removeAll()
                result.add(truncatedMsg)
                return result
            }
        }

        // Estimate current tokens
        var currentTokens = estimateConversationTokens(result)
        val remainingBudget = maxTokens - currentTokens

        if (remainingBudget <= 0) return result

        // Add messages from end (most recent) working backwards
        val messagesToAdd = mutableListOf<JsonNode>()
        val startIdx = if (firstMsg.get("role")?.asText() == "system") 1 else 0

        for (i in (messages.size() - 1) downTo startIdx) {
            val msg = messages[i]
            val msgTokens = estimateTokenCount(msg.get("content")?.asText() ?: "")

            if (currentTokens + msgTokens > maxTokens) {
                break  // Would exceed budget
            }

            messagesToAdd.add(0, msg)  // Add to front to maintain order
            currentTokens += msgTokens
        }

        messagesToAdd.forEach { result.add(it) }

        return result
    }

    /**
     * Truncate text to fit within character limit.
     *
     * @param text The text to truncate
     * @param maxChars Maximum character count
     * @return Truncated text with "..." suffix if truncated
     */
    fun truncateText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 3) + "..."
    }

    /**
     * Load system prompt from file.
     *
     * Looks for the prompt file in standard locations:
     * 1. /app/configs/system/{filename} (production container path)
     * 2. configs/system/{filename} (local development)
     * 3. dist/configs/system/{filename} (build output)
     *
     * @param filename The prompt filename (e.g., "stack-expert.txt")
     * @return The prompt content, or default fallback if file not found
     */
    fun loadSystemPrompt(filename: String): String {
        val searchPaths = listOf(
            "/app/configs/system/$filename",
            "configs/system/$filename",
            "dist/configs/system/$filename",
            "configs.templates/system/$filename"
        )

        for (path in searchPaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file.readText().trim()
            }
        }

        // Fallback if file not found
        return """
            You are a helpful AI assistant with access to various tools.
            Use the available tools when they can help answer user questions.
            Always provide accurate, helpful, and concise responses.
        """.trimIndent()
    }

    /**
     * Inject tool descriptions into system prompt.
     *
     * Appends a formatted list of available tools to the system prompt,
     * helping the LLM understand what tools are available.
     *
     * @param systemPrompt The base system prompt
     * @param tools List of tool definitions to inject
     * @return Enhanced system prompt with tool descriptions
     */
    fun injectToolDescriptions(systemPrompt: String, tools: List<ToolDefinition>): String {
        if (tools.isEmpty()) return systemPrompt

        val toolSection = buildString {
            appendLine()
            appendLine()
            appendLine("=" .repeat(80))
            appendLine("AVAILABLE TOOLS")
            appendLine("=" .repeat(80))
            appendLine()
            appendLine("You have access to the following tools:")
            appendLine()

            tools.forEach { tool ->
                appendLine("• ${tool.name}")
                appendLine("  ${tool.shortDescription}")

                if (tool.parameters.isNotEmpty()) {
                    appendLine("  Parameters:")
                    tool.parameters.forEach { param ->
                        val required = if (param.required) " (required)" else " (optional)"
                        appendLine("    - ${param.name}: ${param.type}$required")
                        if (param.description.isNotEmpty()) {
                            appendLine("      ${param.description}")
                        }
                    }
                }
                appendLine()
            }

            appendLine("=" .repeat(80))
        }

        return systemPrompt + toolSection
    }

    /**
     * Convert tool definitions to OpenAI function calling format.
     *
     * @param tools List of tool definitions
     * @return JSON array in OpenAI tools format
     */
    fun toolsToOpenAIFormat(tools: List<ToolDefinition>): ArrayNode {
        val openaiTools = Json.mapper.createArrayNode()

        tools.forEach { toolDef ->
            openaiTools.addObject().apply {
                put("type", "function")
                putObject("function").apply {
                    put("name", toolDef.name)
                    put("description", toolDef.description ?: toolDef.shortDescription)

                    // Parse JSON schema from tool definition
                    val paramsNode = try {
                        Json.mapper.readTree(toolDef.paramsSpec)
                    } catch (e: Exception) {
                        Json.mapper.createObjectNode().apply {
                            put("type", "object")
                            putObject("properties")
                        }
                    }
                    set<JsonNode>("parameters", paramsNode)
                }
            }
        }

        return openaiTools
    }

    /**
     * Create an agent trace entry for logging tool calls.
     *
     * @param iteration The current iteration number
     * @param toolName The name of the tool called
     * @param args The arguments passed to the tool
     * @param result The result returned by the tool
     * @return Formatted trace entry
     */
    fun createTraceEntry(iteration: Int, toolName: String, args: JsonNode, result: Any?): Map<String, Any?> {
        return mapOf(
            "iteration" to iteration,
            "tool" to toolName,
            "arguments" to args,
            "result" to result,
            "timestamp" to System.currentTimeMillis()
        )
    }
}
