package org.example.api

import org.example.manifest.PluginManifest
import org.example.host.ToolRegistry


interface Plugin {
    
    fun manifest(): PluginManifest

    
    fun init(context: PluginContext)

    
    fun shutdown() {}

    
    fun tools(): List<Any> = emptyList()

    
    fun registerTools(registry: ToolRegistry) {  }
}

data class PluginContext(
    val hostVersion: String,
    val apiVersion: String,
    val config: Map<String, Any?> = emptyMap()
)
