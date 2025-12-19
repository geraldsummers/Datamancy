package org.datamancy.stacktests.discovery

import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.stacktests.models.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Scans Kotlin source files to discover Ktor HTTP routes.
 *
 * Detects patterns like:
 * - get("/path") { ... }
 * - post("/path") { ... }
 * - route("/prefix") { get("/subpath") { ... } }
 */
class KtorRouteScanner(private val projectRoot: File) {

    private val routePattern = Regex("""(get|post|put|delete|patch|head|options)\s*\(\s*"([^"]+)"\s*\)""")
    private val routeBlockPattern = Regex("""route\s*\(\s*"([^"]+)"\s*\)\s*\{""")
    private val routeExtensionFunctionPattern = Regex("""fun\s+Route\.(\w+)\s*\(""")
    private val routeCallPattern = Regex("""route\s*\(\s*"([^"]+)"\s*\)\s*\{\s*(\w+)\s*\(""")

    /**
     * Scan a Kotlin service directory for route definitions.
     */
    fun scanService(
        serviceName: String,
        serviceDir: File,
        baseUrl: String
    ): ServiceSpec {
        logger.info { "Scanning $serviceName for Ktor routes..." }

        if (!serviceDir.exists()) {
            logger.warn { "Service directory not found: $serviceDir" }
            return ServiceSpec(
                name = serviceName,
                containerName = serviceName,
                baseUrl = baseUrl,
                type = ServiceType.KOTLIN_KTOR,
                endpoints = emptyList()
            )
        }

        val kotlinFiles = serviceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()

        // First pass: build map of Route extension functions to their defining files
        val functionToFileMap = buildFunctionFileMap(kotlinFiles)

        // Second pass: find route prefixes for functions in Main.kt
        val functionPrefixes = extractFunctionPrefixes(kotlinFiles)

        logger.debug { "Found function prefixes: $functionPrefixes" }

        val endpoints = mutableListOf<EndpointSpec>()

        for (file in kotlinFiles) {
            val discoveredEndpoints = scanFile(file, baseUrl, functionToFileMap, functionPrefixes)
            endpoints.addAll(discoveredEndpoints)
        }

        logger.info { "Found ${endpoints.size} endpoints in $serviceName" }

        return ServiceSpec(
            name = serviceName,
            containerName = serviceName,
            baseUrl = baseUrl,
            type = ServiceType.KOTLIN_KTOR,
            endpoints = endpoints
        )
    }

    /**
     * Build a map of Route extension function names to their files.
     */
    private fun buildFunctionFileMap(files: List<File>): Map<String, File> {
        val map = mutableMapOf<String, File>()
        for (file in files) {
            val content = file.readText()
            routeExtensionFunctionPattern.findAll(content).forEach { match ->
                val functionName = match.groupValues[1]
                map[functionName] = file
            }
        }
        return map
    }

    /**
     * Extract route prefixes for Route extension function calls.
     * Looks for patterns like: route("/api/fetcher") { configureFetcherApi(...) }
     */
    private fun extractFunctionPrefixes(files: List<File>): Map<String, String> {
        val prefixes = mutableMapOf<String, String>()

        for (file in files) {
            val content = file.readText()

            // Match route("/prefix") { functionName( across lines
            val multiLinePattern = Regex("""route\s*\(\s*"([^"]+)"\s*\)\s*\{\s*(\w+)\s*\(""")
            val matches = multiLinePattern.findAll(content).toList()

            matches.forEach { match ->
                val prefix = match.groupValues[1]
                val functionName = match.groupValues[2]
                prefixes[functionName] = prefix
                logger.debug { "Mapped function $functionName to prefix $prefix in ${file.name}" }
            }
        }

        return prefixes
    }

    /**
     * Scan a single Kotlin file for route definitions.
     */
    private fun scanFile(
        file: File,
        baseUrl: String,
        functionToFileMap: Map<String, File>,
        functionPrefixes: Map<String, String>
    ): List<EndpointSpec> {
        val endpoints = mutableListOf<EndpointSpec>()
        val content = file.readText()
        val lines = content.lines()

        // Check if this file defines a Route extension function with a known prefix
        var functionPrefix = ""
        routeExtensionFunctionPattern.find(content)?.let { match ->
            val functionName = match.groupValues[1]
            if (functionPrefixes.containsKey(functionName)) {
                functionPrefix = functionPrefixes[functionName]!!
                logger.debug { "File ${file.name} defines function $functionName with prefix $functionPrefix" }
            }
        }

        // Track route prefixes from route { } blocks
        val routePrefixes = mutableListOf<String>()
        if (functionPrefix.isNotEmpty()) {
            routePrefixes.add(functionPrefix)
        }
        var currentIndent = 0

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmed = line.trim()

            // Detect route block: route("/api") {
            val routeBlockMatch = routeBlockPattern.find(line)
            if (routeBlockMatch != null) {
                val prefix = routeBlockMatch.groupValues[1]
                routePrefixes.add(prefix)
                currentIndent = line.takeWhile { it.isWhitespace() }.length
                logger.debug { "Found route prefix: $prefix at line $lineNumber" }
            }

            // Detect closing brace for route blocks (simplified)
            if (trimmed == "}" && routePrefixes.size > (if (functionPrefix.isNotEmpty()) 1 else 0)) {
                val indent = line.takeWhile { it.isWhitespace() }.length
                if (indent <= currentIndent) {
                    routePrefixes.removeLastOrNull()
                    currentIndent = if (routePrefixes.size > (if (functionPrefix.isNotEmpty()) 1 else 0)) indent else 0
                }
            }

            // Detect HTTP method routes: get("/path") {
            val routeMatch = routePattern.find(line)
            if (routeMatch != null) {
                val method = routeMatch.groupValues[1].uppercase()
                val path = routeMatch.groupValues[2]

                // Combine with any route prefix (but avoid double /api/ prefixes)
                val fullPath = if (routePrefixes.isNotEmpty()) {
                    val prefixPath = routePrefixes.joinToString("")
                    // If path already starts with /api/, don't add another prefix
                    if (path.startsWith("/api/")) {
                        path
                    } else {
                        prefixPath + path
                    }
                } else {
                    path
                }

                // Filter out non-HTTP paths (must start with / or be a full URL for external paths)
                // This excludes JSON accessors like get("name"), get("url"), etc.
                if (!fullPath.startsWith("/") && !fullPath.startsWith("http")) {
                    logger.debug { "Skipping non-HTTP path: $method $fullPath in ${file.name}:$lineNumber" }
                    return@forEachIndexed
                }

                // Extract path parameters: {id}, :id
                val parameters = extractPathParameters(fullPath)

                // Determine expected response type based on context
                val responseType = inferResponseType(content, lineNumber)

                endpoints.add(
                    EndpointSpec(
                        method = HttpMethod.valueOf(method),
                        path = fullPath,
                        serviceUrl = baseUrl,
                        sourceFile = file.relativeTo(projectRoot).path,
                        lineNumber = lineNumber,
                        parameters = parameters,
                        expectedResponseType = responseType
                    )
                )

                logger.debug { "Found endpoint: $method $fullPath in ${file.name}:$lineNumber" }
            }
        }

        return endpoints
    }

    /**
     * Extract path parameters from a route path.
     * Handles both {param} and :param styles.
     */
    private fun extractPathParameters(path: String): List<PathParameter> {
        val parameters = mutableListOf<PathParameter>()

        // Match {param} style
        val curlyBracePattern = Regex("""\{([^}]+)\}""")
        curlyBracePattern.findAll(path).forEach { match ->
            parameters.add(PathParameter(name = match.groupValues[1]))
        }

        // Match :param style (less common in Ktor but possible)
        val colonPattern = Regex(""":(\w+)""")
        colonPattern.findAll(path).forEach { match ->
            parameters.add(PathParameter(name = match.groupValues[1]))
        }

        return parameters
    }

    /**
     * Infer response type from surrounding code.
     */
    private fun inferResponseType(content: String, lineNumber: Int): ResponseType {
        val lines = content.lines()
        val searchStart = maxOf(0, lineNumber - 1)
        val searchEnd = minOf(lines.size, lineNumber + 10)

        val contextLines = lines.subList(searchStart, searchEnd).joinToString("\n")

        return when {
            contextLines.contains("call.respond") && contextLines.contains("[]") -> ResponseType.JSON_ARRAY
            contextLines.contains("call.respond") && contextLines.contains("listOf") -> ResponseType.JSON_ARRAY
            contextLines.contains("call.respond") && contextLines.contains("emptyList") -> ResponseType.JSON_ARRAY
            contextLines.contains("call.respondText") -> ResponseType.TEXT
            contextLines.contains("call.respondHtml") -> ResponseType.HTML
            contextLines.contains("HttpStatusCode.NoContent") -> ResponseType.EMPTY
            else -> ResponseType.JSON
        }
    }
}
