package org.example.host

/**
 * Capability-based security policy enforcing fine-grained access control for plugins.
 *
 * This policy defines which capabilities plugins are allowed to request. Plugins declare
 * required capabilities in their manifest (e.g., "host.docker.write", "datasource.postgres.read"),
 * and PluginManager enforces this policy before plugin initialization.
 *
 * ## Security Model
 * Agent tools have access to virtually every service in the Datamancy stack:
 * - Docker daemon (container management)
 * - PostgreSQL/MariaDB (database queries)
 * - SSH to remote hosts (command execution)
 * - Qdrant/Search-Service (semantic search)
 * - LiteLLM (LLM completions)
 * - Playwright (browser automation)
 *
 * Capability enforcement prevents:
 * - **Privilege Escalation**: Untrusted plugins cannot access write capabilities
 * - **Lateral Movement**: Plugins cannot access services beyond their declared needs
 * - **DoS Attacks**: Resource-intensive capabilities (VM provisioning) can be restricted
 *
 * ## Capability Naming Convention
 * Capabilities use dot-separated hierarchical names:
 * - `host.docker.read` - Read Docker container/image info
 * - `host.docker.write` - Create/modify/delete containers
 * - `datasource.postgres.read` - Execute SELECT queries
 * - `datasource.postgres.write` - Execute INSERT/UPDATE/DELETE (typically disallowed)
 * - `network.http.read` - HTTP GET requests
 * - `network.http.write` - HTTP POST/PUT/DELETE requests
 * - `vm.provision` - Create/manage VMs (libvirt)
 * - `browser.automation` - Playwright automation
 *
 * ## Example Policies
 * ```kotlin
 * // Development: Allow everything (empty set = no enforcement)
 * val devPolicy = CapabilityPolicy(allowed = emptySet())
 *
 * // Production: Read-only access to data sources
 * val prodPolicy = CapabilityPolicy(
 *     allowed = setOf(
 *         "host.docker.read",
 *         "datasource.postgres.read",
 *         "datasource.mariadb.read",
 *         "datasource.qdrant.read",
 *         "network.http.read",
 *         "browser.automation"
 *     )
 * )
 * ```
 *
 * @property allowed Set of capability identifiers plugins are allowed to request.
 *                   Empty set disables enforcement (allow-all mode for development).
 *
 * @see enforceCapabilities
 * @see CapabilityViolation
 * @see org.example.host.PluginManager
 * @see org.example.manifest.PluginManifest
 */
data class CapabilityPolicy(
    val allowed: Set<String> = emptySet()
)

/**
 * Exception thrown when a plugin requests capabilities disallowed by policy.
 *
 * This prevents plugin initialization, ensuring untrusted plugins cannot bypass
 * security boundaries even if loaded into the host.
 *
 * @see enforceCapabilities
 */
class CapabilityViolation(message: String) : RuntimeException(message)

/**
 * Enforces capability policy against plugin's requested capabilities.
 *
 * Called by PluginManager during plugin loading, before plugin initialization.
 * If any requested capability is not in the allowed set, throws CapabilityViolation
 * and prevents plugin from loading.
 *
 * ## Enforcement Logic
 * - If policy.allowed is empty, enforcement is disabled (allow-all for development)
 * - Otherwise, every plugin capability must be in policy.allowed
 * - Violation aborts plugin loading with descriptive error message
 *
 * ## Example
 * ```kotlin
 * val policy = CapabilityPolicy(allowed = setOf("datasource.postgres.read"))
 * val manifest = PluginManifest(
 *     id = "evil-plugin",
 *     capabilities = listOf("datasource.postgres.read", "host.docker.write")
 * )
 *
 * // Throws: "Plugin 'evil-plugin' requests disallowed capabilities: [host.docker.write]"
 * enforceCapabilities(policy, manifest.id, manifest.capabilities)
 * ```
 *
 * @param policy CapabilityPolicy defining allowed capabilities
 * @param pluginId Plugin identifier for error messages
 * @param requested List of capabilities requested by plugin manifest
 * @throws CapabilityViolation if any requested capability is disallowed
 *
 * @see CapabilityPolicy
 * @see CapabilityViolation
 * @see org.example.host.PluginManager.loadFromJar
 */
fun enforceCapabilities(policy: CapabilityPolicy, pluginId: String, requested: List<String>) {
    // Empty allowed set = no enforcement (development mode)
    if (policy.allowed.isEmpty()) return
    val disallowed = requested.filter { it !in policy.allowed }
    if (disallowed.isNotEmpty()) {
        throw CapabilityViolation("Plugin '$pluginId' requests disallowed capabilities: $disallowed")
    }
}
