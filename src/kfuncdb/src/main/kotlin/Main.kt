package org.example

import org.example.host.HostConfig
import org.example.host.PluginManager
import org.example.host.ToolRegistry
import org.example.host.CapabilityPolicy
import org.example.http.LlmHttpServer
import java.io.File

fun main() {
    // Basic host configuration with environment overrides.
    val hostVersion = "1.0.0"
    val apiVersion = "1.0.0"

    val pluginsDir = System.getenv("KFUNCDB_PLUGINS_DIR")
        ?: File("plugins").apply { if (!exists()) mkdirs() }.path

    val allowedCapsEnv = System.getenv("KFUNCDB_ALLOW_CAPS")?.trim().orEmpty()
    val allowedCaps: Set<String> = if (allowedCapsEnv.isBlank()) emptySet() else allowedCapsEnv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    val config = HostConfig(
        hostVersion = hostVersion,
        apiVersion = apiVersion,
        pluginsDir = pluginsDir,
        capabilityPolicy = CapabilityPolicy(allowed = allowedCaps)
    )

    val manager = PluginManager(config)
    val loaded = manager.loadAll()
    println("Loaded plugins: ${loaded.map { it.manifest.id to it.manifest.version }}")

    val registry = ToolRegistry()
    loaded.forEach { registry.registerFrom(it) }

    val port = System.getenv("KFUNCDB_PORT")?.toIntOrNull() ?: 8081
    val server = LlmHttpServer(port = port, tools = registry)
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down host...")
        server.stop()
        manager.shutdownAll()
    })

    println("Host started. HTTP endpoints:\n - GET  http://kfuncdb:${'$'}port/tools\n - POST http://kfuncdb:${'$'}port/call-tool")
}