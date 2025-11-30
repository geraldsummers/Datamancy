package org.example

import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolRegistry
import org.example.host.CapabilityPolicy
import org.example.http.LlmHttpServer
import org.example.plugins.CoreToolsPlugin
import org.example.plugins.HostToolsPlugin
import org.example.plugins.BrowserToolsPlugin
import org.example.plugins.LlmCompletionPlugin
import org.example.plugins.OpsSshPlugin
import org.example.host.HostConfig
import org.example.host.PluginFactories
import org.example.host.PluginManager

fun main() {
    val hostVersion = "1.0.0"
    val apiVersion = "1.0.0"

    val allowedCapsEnv = System.getenv("KFUNCDB_ALLOW_CAPS")?.trim().orEmpty()
    val allowedCaps: Set<String> = if (allowedCapsEnv.isBlank()) emptySet() else allowedCapsEnv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    val capabilityPolicy = CapabilityPolicy(allowed = allowedCaps)

    // Register factory functions for built-in plugins to avoid any reflection-based instantiation
    PluginFactories.clear()
    PluginFactories.register(CoreToolsPlugin::class.qualifiedName!!) { CoreToolsPlugin() }
    PluginFactories.register(HostToolsPlugin::class.qualifiedName!!) { HostToolsPlugin() }
    PluginFactories.register(BrowserToolsPlugin::class.qualifiedName!!) { BrowserToolsPlugin() }
    PluginFactories.register(LlmCompletionPlugin::class.qualifiedName!!) { LlmCompletionPlugin() }
    PluginFactories.register(OpsSshPlugin::class.qualifiedName!!) { OpsSshPlugin() }

    // Instantiate all built-in plugins directly via constructors (no reflection),
    // while also supporting external plugins loaded via PluginManager using the factory registry.
    val builtinPlugins: List<Plugin> = listOf(
        CoreToolsPlugin(),
        HostToolsPlugin(),
        BrowserToolsPlugin(),
        LlmCompletionPlugin(),
        OpsSshPlugin()
    )

    // Initialize plugins and check capabilities
    val ctx = PluginContext(hostVersion = hostVersion, apiVersion = apiVersion)
    val loadedPlugins = mutableListOf<Plugin>()

    builtinPlugins.forEach { plugin ->
        val manifest = plugin.manifest()

        // Check capabilities
        val missingCaps = manifest.capabilities.filterNot { capabilityPolicy.allowed.isEmpty() || it in capabilityPolicy.allowed }
        if (missingCaps.isNotEmpty()) {
            println("[WARN] Plugin ${manifest.id} requires capabilities not granted: $missingCaps - skipping")
        } else {
            plugin.init(ctx)
            loadedPlugins.add(plugin)
            println("Loaded plugin: ${manifest.id} v${manifest.version}")
        }
    }

    // Register tools from all loaded plugins (non-reflective)
    val registry = ToolRegistry()
    builtinPlugins.forEach { plugin ->
        runCatching { plugin.registerTools(registry) }
            .onFailure { println("[WARN] Plugin ${'$'}{plugin.manifest().id} failed to register tools: ${'$'}it") }
    }

    // Additionally load external plugins from pluginsDir using PluginManager (factory-based, no reflection for instantiation)
    val pluginsDir = System.getenv("KFUNCDB_PLUGINS_DIR")?.takeIf { it.isNotBlank() } ?: "plugins"
    val hostConfig = HostConfig(
        hostVersion = hostVersion,
        apiVersion = apiVersion,
        pluginsDir = pluginsDir,
        capabilityPolicy = capabilityPolicy
    )
    val pluginManager = PluginManager(hostConfig)
    val external = pluginManager.loadAll()
    external.forEach { loaded ->
        // Allow external plugin to register tools explicitly (non-reflective)
        runCatching { loaded.instance.registerTools(registry) }
            .onFailure { println("[WARN] External plugin ${'$'}{loaded.manifest.id} failed to register tools: ${'$'}it") }
        // Keep the plugin instance for clean shutdown
        loadedPlugins.add(loaded.instance)
        println("Loaded external plugin: ${'$'}{loaded.manifest.id} v${'$'}{loaded.manifest.version}")
    }

    val port = System.getenv("KFUNCDB_PORT")?.toIntOrNull() ?: 8081
    val server = LlmHttpServer(port = port, tools = registry)
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down host...")
        server.stop()
        loadedPlugins.forEach { it.shutdown() }
    })

    println("Host started. HTTP endpoints:\n - GET  http://kfuncdb:$port/tools\n - POST http://kfuncdb:$port/call-tool")
}