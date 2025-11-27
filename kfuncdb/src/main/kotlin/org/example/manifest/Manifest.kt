package org.example.manifest

data class PluginManifest(
    val id: String,
    val version: String,
    val apiVersion: String,
    val implementation: String,
    val capabilities: List<String> = emptyList(),
    val requires: Requires? = null
)

data class Requires(
    val host: String? = null,
    val api: String? = null
)
