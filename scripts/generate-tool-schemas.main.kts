#!/usr/bin/env kotlin

/**
 * Generate OpenAI-compatible tool schemas from agent-tool-server @LlmTool annotations
 */

import java.io.File
import java.time.Instant

val pluginsDir = File("src/agent-tool-server/src/main/kotlin/org/example/plugins")
if (!pluginsDir.exists()) {
    println("ERROR: Plugin directory not found: ${pluginsDir.absolutePath}")
    System.exit(1)
}

data class ToolDef(
    val name: String,
    val description: String,
    val paramsSchema: String
)

val tools = mutableListOf<ToolDef>()

// Parse each plugin file
pluginsDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
    val content = file.readText()

    // Find @LlmTool annotations - match multiline with """ blocks
    val annotationStart = """@LlmTool\s*\("""
    val matches = Regex(annotationStart).findAll(content)

    matches.forEach { startMatch ->
        val fromAnnotation = content.substring(startMatch.range.first)

        // Extract shortDescription
        val descMatch = Regex("""shortDescription\s*=\s*"([^"]+)"""").find(fromAnnotation)
        val description = descMatch?.groupValues?.get(1) ?: "No description"

        // Extract paramsSpec - try triple-quoted first, then single-line
        val paramsSpec = when {
            // Triple-quoted (multiline)
            fromAnnotation.contains("""paramsSpec\s*=\s*"{3}""".toRegex()) -> {
                Regex("""paramsSpec\s*=\s*"{3}(.*?){3}""", RegexOption.DOT_MATCHES_ALL)
                    .find(fromAnnotation)?.groupValues?.get(1)?.trim() ?: "{}"
            }
            // Single-quoted (inline JSON string)
            else -> {
                Regex("""paramsSpec\s*=\s*"([^"]+)"""").find(fromAnnotation)
                    ?.groupValues?.get(1)
                    ?.replace("\\", "")  // Unescape JSON
                    ?: "{}"
            }
        }

        // Extract function name
        val funcMatch = Regex("""fun\s+(\w+)\s*\(""").find(fromAnnotation)
        val functionName = funcMatch?.groupValues?.get(1) ?: return@forEach

        tools.add(ToolDef(
            name = functionName,
            description = description,
            paramsSchema = paramsSpec
        ))
    }
}

// Generate OpenAI function calling format
val toolsJson = buildString {
    appendLine("{")
    appendLine("""  "tools": [""")

    tools.forEachIndexed { index, tool ->
        appendLine("    {")
        appendLine("""      "type": "function",""")
        appendLine("""      "function": {""")
        appendLine("""        "name": "${tool.name}",""")
        appendLine("""        "description": "${tool.description}",""")
        appendLine("""        "parameters": ${tool.paramsSchema}""")
        appendLine("      }")
        append("    }")
        if (index < tools.size - 1) appendLine(",")
        else appendLine()
    }

    appendLine("  ],")
    appendLine("""  "generated_at": "${Instant.now()}",""")
    appendLine("""  "source": "agent-tool-server @LlmTool annotations",""")
    appendLine("""  "tool_count": ${tools.size}""")
    append("}")
}

println(toolsJson)
System.err.println("# Parsed ${tools.size} tools from agent-tool-server")
