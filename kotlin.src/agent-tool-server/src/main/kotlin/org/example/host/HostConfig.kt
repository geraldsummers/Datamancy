package org.example.host

/**
 * Configuration for the agent-tool-server host managing plugin lifecycle and security.
 *
 * This configuration defines how plugins are discovered, loaded, and secured via
 * capability-based access control. It's instantiated at startup and passed to
 * PluginManager for plugin initialization.
 *
 * ## Version Compatibility
 * The hostVersion and apiVersion fields enable semantic versioning compatibility checks.
 * Plugins declare version constraints in their manifest (e.g., "requires.api": "^1.0.0"),
 * and PluginManager rejects incompatible plugins before initialization.
 *
 * ## Configuration Propagation
 * The `config` map is passed to plugins during init() via PluginContext. This enables
 * plugins to access external service endpoints without hardcoding:
 * - Database connection URLs (PostgreSQL, MariaDB)
 * - HTTP service endpoints (Search-Service, Qdrant, LiteLLM, Playwright)
 * - Shadow account credentials for database queries
 * - Docker daemon socket path
 *
 * ## Example
 * ```kotlin
 * val hostConfig = HostConfig(
 *     hostVersion = "1.0.0",
 *     apiVersion = "1.0.0",
 *     pluginsDir = "/app/plugins",
 *     capabilityPolicy = CapabilityPolicy(
 *         allowed = setOf(
 *             "host.docker.read",
 *             "datasource.postgres.read",
 *             "network.http.read"
 *         )
 *     ),
 *     config = mapOf(
 *         "postgres.url" to "jdbc:postgresql://postgres:5432/datamancy",
 *         "search.url" to "http://search-service:8098",
 *         "litellm.url" to "http://litellm:4000"
 *     )
 * )
 * ```
 *
 * @property hostVersion SemVer version of agent-tool-server (used for plugin compatibility)
 * @property apiVersion SemVer version of Plugin API (must match plugin's apiVersion exactly)
 * @property pluginsDir Directory path for plugin JAR discovery (default: "plugins")
 * @property capabilityPolicy Security policy defining allowed capabilities (default: allow-all)
 * @property config Configuration map propagated to plugins (service URLs, credentials, etc.)
 *
 * @see CapabilityPolicy
 * @see org.example.api.PluginContext
 * @see org.example.host.PluginManager
 */
data class HostConfig(
    val hostVersion: String,
    val apiVersion: String,
    val pluginsDir: String = "plugins",
    val capabilityPolicy: CapabilityPolicy = CapabilityPolicy(),
    val config: Map<String, Any?> = emptyMap()
)
