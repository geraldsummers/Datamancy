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
import org.example.plugins.DataSourceQueryPlugin
import org.example.plugins.DockerContainerPlugin
import org.example.host.HostConfig
import org.example.host.PluginFactories
import org.example.host.PluginManager

fun main() {
    val hostVersion = "1.0.0"
    val apiVersion = "1.0.0"

    val allowedCapsEnv = System.getenv("TOOLSERVER_ALLOW_CAPS")?.trim().orEmpty()
    val allowedCaps: Set<String> = if (allowedCapsEnv.isBlank()) emptySet() else allowedCapsEnv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    val capabilityPolicy = CapabilityPolicy(allowed = allowedCaps)

    
    PluginFactories.clear()
    PluginFactories.register(CoreToolsPlugin::class.qualifiedName!!) { CoreToolsPlugin() }
    PluginFactories.register(HostToolsPlugin::class.qualifiedName!!) { HostToolsPlugin() }
    PluginFactories.register(BrowserToolsPlugin::class.qualifiedName!!) { BrowserToolsPlugin() }
    PluginFactories.register(LlmCompletionPlugin::class.qualifiedName!!) { LlmCompletionPlugin() }
    PluginFactories.register(OpsSshPlugin::class.qualifiedName!!) { OpsSshPlugin() }
    PluginFactories.register(DataSourceQueryPlugin::class.qualifiedName!!) { DataSourceQueryPlugin() }
    PluginFactories.register(DockerContainerPlugin::class.qualifiedName!!) { DockerContainerPlugin() }

    
    
    val builtinPlugins: List<Plugin> = listOf(
        CoreToolsPlugin(),
        HostToolsPlugin(),
        BrowserToolsPlugin(),
        LlmCompletionPlugin(),
        OpsSshPlugin(),
        DataSourceQueryPlugin(),
        DockerContainerPlugin()
    )

    
    val ctx = PluginContext(hostVersion = hostVersion, apiVersion = apiVersion)
    val loadedPlugins = mutableListOf<Plugin>()

    builtinPlugins.forEach { plugin ->
        val manifest = plugin.manifest()

        
        val missingCaps = manifest.capabilities.filterNot { capabilityPolicy.allowed.isEmpty() || it in capabilityPolicy.allowed }
        if (missingCaps.isNotEmpty()) {
            println("[WARN] Plugin ${manifest.id} requires capabilities not granted: $missingCaps - skipping")
        } else {
            plugin.init(ctx)
            loadedPlugins.add(plugin)
            println("Loaded plugin: ${manifest.id} v${manifest.version}")
        }
    }

    
    val registry = ToolRegistry()
    loadedPlugins.forEach { plugin ->
        runCatching { plugin.registerTools(registry) }
            .onFailure { println("[WARN] Plugin ${'$'}{plugin.manifest().id} failed to register tools: ${'$'}it") }
    }

    
    val pluginsDir = System.getenv("TOOLSERVER_PLUGINS_DIR")?.takeIf { it.isNotBlank() } ?: "plugins"
    val hostConfig = HostConfig(
        hostVersion = hostVersion,
        apiVersion = apiVersion,
        pluginsDir = pluginsDir,
        capabilityPolicy = capabilityPolicy
    )
    val pluginManager = PluginManager(hostConfig)
    val external = pluginManager.loadAll()
    external.forEach { loaded ->
        
        runCatching { loaded.instance.registerTools(registry) }
            .onFailure { println("[WARN] External plugin ${'$'}{loaded.manifest.id} failed to register tools: ${'$'}it") }
        
        loadedPlugins.add(loaded.instance)
        println("Loaded external plugin: ${'$'}{loaded.manifest.id} v${'$'}{loaded.manifest.version}")
    }

    val port = System.getenv("TOOLSERVER_PORT")?.toIntOrNull() ?: 8081
    val server = LlmHttpServer(port = port, tools = registry)
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down host...")
        server.stop()
        loadedPlugins.forEach { it.shutdown() }
    })

    println("Host started. HTTP endpoints:\n - GET  http://agent-tool-server:$port/tools\n - POST http://agent-tool-server:$port/call-tool")
}