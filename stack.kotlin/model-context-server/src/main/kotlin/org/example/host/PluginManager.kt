
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

/**
 * Container for a successfully loaded plugin with its metadata and instance.
 *
 * Created by PluginManager after successful validation and initialization.
 * Used by ToolRegistry to extract and register tools from plugin instances.
 *
 * @property manifest Plugin metadata from llm-plugin.json (id, version, capabilities)
 * @property classLoader ClassLoader that loaded the plugin JAR (for resource access)
 * @property instance Initialized plugin instance ready for tool registration
 *
 * @see PluginManager.loadAll
 */
data class LoadedPlugin(
    val manifest: PluginManifest,
    val classLoader: ClassLoader,
    val instance: Plugin
)

/**
 * Manages the complete plugin lifecycle: discovery, loading, validation, initialization, and shutdown.
 *
 * PluginManager is the bridge between JAR files in the plugins directory and running plugin instances.
 * It enforces security policies, version compatibility, and dependency constraints before allowing
 * plugins to integrate with the LLM tool system.
 *
 * ## Plugin Lifecycle (Load → Init → Register → Invoke → Shutdown)
 *
 * 1. **Load Phase** (`loadAll()` and `loadFromJar()`):
 *    - Scans plugins directory for *.jar files
 *    - Reads `llm-plugin.json` manifest from each JAR
 *    - Validates SemVer version constraints (host/API compatibility)
 *    - Enforces capability policy (security check)
 *    - Looks up factory in PluginFactories registry
 *    - Instantiates plugin via factory
 *
 * 2. **Init Phase** (`plugin.init(context)`):
 *    - Passes host configuration to plugin
 *    - Plugin establishes connections to external services
 *    - Plugin prepares for tool registration
 *
 * 3. **Register Phase** (handled by ToolRegistry, not PluginManager):
 *    - ToolRegistry calls `plugin.registerTools(registry)`
 *    - Plugin registers LLM-callable tools with OpenAI function definitions
 *
 * 4. **Invoke Phase** (handled by ToolRegistry):
 *    - LLMs call tools via HTTP `/call-tool` endpoint
 *    - ToolRegistry routes calls to plugin implementations
 *
 * 5. **Shutdown Phase** (`shutdownAll()`):
 *    - Called on graceful application termination
 *    - Plugin closes database connections, HTTP clients, etc.
 *
 * ## Version Compatibility Checks
 *
 * Plugins declare version constraints in manifest:
 * ```json
 * {
 *   "apiVersion": "1.0.0",
 *   "requires": {
 *     "host": "^1.0.0",
 *     "api": "^1.0.0"
 *   }
 * }
 * ```
 *
 * PluginManager enforces:
 * - **Exact API version match**: Plugin's apiVersion must equal host's apiVersion
 * - **SemVer constraints**: Plugin's required host/API versions must be compatible
 * - If any check fails, plugin loading is aborted with descriptive error
 *
 * ## Capability-Based Security
 *
 * Before initialization, PluginManager calls `enforceCapabilities()` to verify that
 * all plugin capabilities are allowed by host policy. This prevents untrusted plugins
 * from accessing write operations, SSH, VM provisioning, etc.
 *
 * Example:
 * ```kotlin
 * // Plugin manifest declares: ["datasource.postgres.read", "host.docker.write"]
 * // Host policy allows: ["datasource.postgres.read"]
 * // Result: Plugin loading fails with CapabilityViolation
 * ```
 *
 * ## Why Factory Pattern?
 *
 * Plugins register factories in PluginFactories before PluginManager runs.
 * This enables loading plugin classes from JARs without compile-time dependencies:
 *
 * ```kotlin
 * // Plugin JAR registers factory at class load time
 * companion object {
 *     init {
 *         PluginFactories.register("org.example.plugins.MyPlugin") { MyPlugin() }
 *     }
 * }
 *
 * // PluginManager instantiates plugin via factory lookup
 * val factory = PluginFactories.get(manifest.implementation)
 * val instance = factory.invoke()
 * ```
 *
 * Without factories, we'd need Class.forName() and complex classloader management.
 *
 * ## Integration with Stack
 *
 * PluginManager doesn't interact directly with external services. Instead, it:
 * - Passes service endpoints to plugins via PluginContext.config
 * - Enforces capability policy to control which services plugins can access
 * - Delegates tool registration to ToolRegistry
 *
 * Example configuration flow:
 * ```kotlin
 * val config = HostConfig(
 *     config = mapOf(
 *         "postgres.url" to "jdbc:postgresql://postgres:5432/datamancy",
 *         "search.url" to "http://search-service:8098"
 *     )
 * )
 * val manager = PluginManager(config)
 * manager.loadAll()
 * // Plugins now have access to service URLs via PluginContext
 * ```
 *
 * @property config Host configuration with version info, plugin directory, and security policy
 *
 * @see LoadedPlugin
 * @see HostConfig
 * @see CapabilityPolicy
 * @see PluginFactories
 * @see org.example.api.Plugin
 * @see org.example.host.ToolRegistry
 */
class PluginManager(private val config: HostConfig) {
    private val plugins = mutableListOf<LoadedPlugin>()

    /**
     * Discovers and loads all plugins from the plugins directory.
     *
     * Scans for *.jar files, validates each plugin, and initializes successfully loaded plugins.
     * Failed plugins are logged but don't abort the entire loading process (fault isolation).
     *
     * ## Loading Process
     * 1. Check if plugins directory exists
     * 2. Find all *.jar files in directory
     * 3. For each JAR:
     *    - Call `loadFromJar()` to validate and initialize
     *    - Catch exceptions and log failures (don't propagate)
     * 4. Return list of successfully loaded plugins
     *
     * ## Error Handling
     * Individual plugin failures don't prevent other plugins from loading:
     * ```
     * [PluginManager] Failed to load evil-plugin.jar: CapabilityViolation(...)
     * [PluginManager] Successfully loaded 7 plugins
     * ```
     *
     * @return List of LoadedPlugin instances ready for tool registration
     */
    fun loadAll(): List<LoadedPlugin> {
        val dir = File(config.pluginsDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        dir.listFiles { f -> f.extension.equals("jar", ignoreCase = true) }?.forEach { jar ->
            runCatching { loadFromJar(jar) }
                .onFailure { println("[PluginManager] Failed to load $jar: $it") }
        }
        return plugins.toList()
    }

    /**
     * Loads a single plugin from a JAR file with full validation and initialization.
     *
     * This is the core of the plugin loading system, enforcing all security and compatibility
     * constraints before allowing a plugin to run.
     *
     * ## Loading Steps
     * 1. **Manifest Parsing**: Read and parse `llm-plugin.json` from JAR
     * 2. **Version Validation**: Check SemVer constraints (host/API compatibility)
     * 3. **Capability Enforcement**: Verify all requested capabilities are allowed
     * 4. **Factory Lookup**: Find plugin factory in PluginFactories registry
     * 5. **Instantiation**: Call factory to create plugin instance
     * 6. **Initialization**: Call `plugin.init(context)` with host config
     * 7. **Registration**: Add to loaded plugins list
     *
     * ## Version Validation Logic
     * ```kotlin
     * // Plugin manifest:
     * {
     *   "apiVersion": "1.0.0",      // Must match host API version exactly
     *   "requires": {
     *     "host": "^1.0.0",         // SemVer constraint (1.x.x compatible)
     *     "api": "^1.0.0"           // SemVer constraint (1.x.x compatible)
     *   }
     * }
     *
     * // Host:
     * hostVersion = "1.2.3"  // Compatible with ^1.0.0
     * apiVersion = "1.0.0"   // Exact match required
     * ```
     *
     * ## Security Enforcement
     * Before instantiation, `enforceCapabilities()` checks that all plugin capabilities
     * are in the host's allowed set. This prevents untrusted plugins from bypassing
     * security boundaries even if they make it into the plugins directory.
     *
     * @param jarFile JAR file to load plugin from
     * @throws IllegalArgumentException if llm-plugin.json is missing
     * @throws IllegalStateException if version constraints or capabilities fail validation
     * @throws IllegalStateException if no factory is registered for plugin implementation
     */
    private fun loadFromJar(jarFile: File) {
        JarFile(jarFile).use { jf ->
            // 1. Read and parse manifest from JAR
            val entry = jf.getJarEntry("llm-plugin.json")
                ?: throw IllegalArgumentException("No llm-plugin.json found in $jarFile")
            val manifest: PluginManifest = jf.getInputStream(entry).use { Json.mapper.readValue(it) }

            // 2. Validate version constraints (SemVer compatibility)
            val hostV = SemVer.parse(config.hostVersion)
            val apiV = SemVer.parse(config.apiVersion)
            val requiresHost = VersionConstraint.parse(manifest.requires?.host)
            val requiresApi = VersionConstraint.parse(manifest.requires?.api)
            if (requiresHost != null && !requiresHost.matches(hostV)) {
                throw IllegalStateException("Plugin ${manifest.id} requires host $requiresHost, host=$hostV")
            }
            if (requiresApi != null && !requiresApi.matches(apiV)) {
                throw IllegalStateException("Plugin ${manifest.id} requires api $requiresApi, api=$apiV")
            }
            // 3. Enforce exact API version match (prevents subtle incompatibilities)
            if (SemVer.parse(manifest.apiVersion) != apiV) {
                throw IllegalStateException("Plugin ${manifest.id} apiVersion ${manifest.apiVersion} != host api $apiV")
            }

            // 4. Enforce capability policy (security check before instantiation)
            enforceCapabilities(config.capabilityPolicy, manifest.id, manifest.capabilities)

            // 5. Look up factory in PluginFactories registry (factory pattern for JAR loading)
            val factory = PluginFactories.get(manifest.implementation)
                ?: throw IllegalStateException("No factory registered for implementation: ${manifest.implementation}")
            val instance = factory.invoke()

            // 6. Initialize plugin with host context (service URLs, credentials, etc.)
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

    /**
     * Returns a copy of all successfully loaded plugins.
     *
     * Used by ToolRegistry to extract and register tools from plugin instances.
     *
     * @return Immutable list of LoadedPlugin instances
     */
    fun loaded(): List<LoadedPlugin> = plugins.toList()

    /**
     * Gracefully shuts down all loaded plugins.
     *
     * Called on application termination to release resources (database connections,
     * HTTP clients, etc.). Individual plugin shutdown failures are caught and don't
     * prevent other plugins from shutting down cleanly.
     *
     * ## Shutdown Order
     * Plugins are shut down in load order (not reversed). If shutdown order matters,
     * plugins should implement idempotent shutdown logic.
     */
    fun shutdownAll() {
        plugins.forEach { runCatching { it.instance.shutdown() } }
    }
}
