package org.example.host

data class HostConfig(
    val hostVersion: String,
    val apiVersion: String,
    val pluginsDir: String = "plugins",
    val capabilityPolicy: CapabilityPolicy = CapabilityPolicy(),
    val config: Map<String, Any?> = emptyMap()
)
