package org.example.host

data class CapabilityPolicy(
    val allowed: Set<String> = emptySet()
)

class CapabilityViolation(message: String) : RuntimeException(message)

fun enforceCapabilities(policy: CapabilityPolicy, pluginId: String, requested: List<String>) {
    if (policy.allowed.isEmpty()) return // no restrictions
    val disallowed = requested.filter { it !in policy.allowed }
    if (disallowed.isNotEmpty()) {
        throw CapabilityViolation("Plugin '$pluginId' requests disallowed capabilities: ${'$'}disallowed")
    }
}
