
package org.example.host

import com.fasterxml.jackson.module.kotlin.readValue
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.manifest.PluginManifest
import org.example.manifest.SemVer
import org.example.manifest.VersionConstraint
import org.example.util.Json
import java.io.File
import java.util.jar.JarFile

data class LoadedPlugin(
    val manifest: PluginManifest,
    val classLoader: ClassLoader,
    val instance: Plugin
)

class PluginManager(private val config: HostConfig) {
    private val plugins = mutableListOf<LoadedPlugin>()

    fun loadAll(): List<LoadedPlugin> {
        val dir = File(config.pluginsDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        dir.listFiles { f -> f.extension.equals("jar", ignoreCase = true) }?.forEach { jar ->
            runCatching { loadFromJar(jar) }
                .onFailure { println("[PluginManager] Failed to load ${'$'}jar: ${'$'}it") }
        }
        return plugins.toList()
    }

    private fun loadFromJar(jarFile: File) {
        JarFile(jarFile).use { jf ->
            val entry = jf.getJarEntry("llm-plugin.json")
                ?: throw IllegalArgumentException("No llm-plugin.json found in ${'$'}jarFile")
            val manifest: PluginManifest = jf.getInputStream(entry).use { Json.mapper.readValue(it) }

            // Version compatibility checks
            val hostV = SemVer.parse(config.hostVersion)
            val apiV = SemVer.parse(config.apiVersion)
            val requiresHost = VersionConstraint.parse(manifest.requires?.host)
            val requiresApi = VersionConstraint.parse(manifest.requires?.api)
            if (requiresHost != null && !requiresHost.matches(hostV)) {
                throw IllegalStateException("Plugin ${'$'}{manifest.id} requires host ${'$'}requiresHost, host=${'$'}hostV")
            }
            if (requiresApi != null && !requiresApi.matches(apiV)) {
                throw IllegalStateException("Plugin ${'$'}{manifest.id} requires api ${'$'}requiresApi, api=${'$'}apiV")
            }
            // Strict check: manifest apiVersion must equal host api version
            if (SemVer.parse(manifest.apiVersion) != apiV) {
                throw IllegalStateException("Plugin ${'$'}{manifest.id} apiVersion ${'$'}{manifest.apiVersion} != host api ${'$'}apiV")
            }

            // Capabilities
            enforceCapabilities(config.capabilityPolicy, manifest.id, manifest.capabilities)

            // Instantiate via factory registry (no dynamic classloading/reflection)
            val factory = PluginFactories.get(manifest.implementation)
                ?: throw IllegalStateException("No factory registered for implementation: ${manifest.implementation}")
            val instance = factory.invoke()

            val ctx = PluginContext(
                hostVersion = config.hostVersion,
                apiVersion = config.apiVersion,
                config = config.config
            )
            instance.init(ctx)
            val cl = this::class.java.classLoader
            plugins += LoadedPlugin(manifest, cl, instance)
        }
    }

    fun loaded(): List<LoadedPlugin> = plugins.toList()

    fun shutdownAll() {
        plugins.forEach { runCatching { it.instance.shutdown() } }
    }
}
