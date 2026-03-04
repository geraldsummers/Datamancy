package org.example.host

import org.example.api.Plugin

/**
 * Global registry mapping plugin implementation names to factory functions.
 *
 * ## Why Factory Pattern for JAR-based Plugins?
 * This pattern solves a critical problem: loading plugin classes from external JARs
 * without compile-time dependencies.
 *
 * Traditional approach (doesn't work for JARs):
 * ```kotlin
 * // Can't do this - class not known at compile time
 * val plugin = Class.forName("com.example.MyPlugin").newInstance()
 * ```
 *
 * Factory approach (enables JAR-based plugins):
 * ```kotlin
 * // 1. Plugin JAR registers its factory at startup (via ServiceLoader or static init)
 * PluginFactories.register("com.example.MyPlugin") {
 *     MyPlugin()
 * }
 *
 * // 2. PluginManager instantiates plugin without knowing its class
 * val factory = PluginFactories.get(manifest.implementation)
 * val instance = factory.invoke()
 * ```
 *
 * ## Registration Mechanism
 * Plugins register factories in one of two ways:
 *
 * 1. **Static Initializer** (current approach for built-in plugins):
 * ```kotlin
 * class CoreToolsPlugin : Plugin {
 *     companion object {
 *         init {
 *             PluginFactories.register("org.example.plugins.CoreToolsPlugin") {
 *                 CoreToolsPlugin()
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * 2. **Java ServiceLoader** (future approach for true hot-pluggable JARs):
 * - Plugin JAR includes `META-INF/services/org.example.api.PluginFactory`
 * - PluginManager uses ServiceLoader to discover and register factories
 * - Enables loading plugins without any code changes to host
 *
 * ## Lifecycle Integration
 * PluginManager uses this registry during `loadFromJar()`:
 * 1. Reads `llm-plugin.json` manifest from JAR
 * 2. Validates version constraints and capabilities
 * 3. Looks up factory: `PluginFactories.get(manifest.implementation)`
 * 4. Instantiates plugin: `factory.invoke()`
 * 5. Initializes plugin: `plugin.init(context)`
 *
 * This decouples plugin discovery (JAR scanning) from plugin instantiation (factory invocation).
 *
 * @see org.example.api.Plugin
 * @see org.example.host.PluginManager.loadFromJar
 */
object PluginFactories {
    private val factories = mutableMapOf<String, () -> Plugin>()

    /**
     * Registers a factory function for a plugin implementation.
     *
     * Called by plugins (typically in companion object init block) to make themselves
     * discoverable by PluginManager. The implementation name should match the fully
     * qualified class name in the plugin manifest.
     *
     * @param implementation Fully qualified plugin class name (e.g., "org.example.plugins.CoreToolsPlugin")
     * @param factory Lambda that instantiates the plugin (e.g., `{ CoreToolsPlugin() }`)
     */
    fun register(implementation: String, factory: () -> Plugin) {
        factories[implementation] = factory
    }

    /**
     * Removes a factory registration.
     *
     * Used for testing or dynamic plugin unloading scenarios.
     *
     * @param implementation Fully qualified plugin class name to unregister
     */
    fun unregister(implementation: String) {
        factories.remove(implementation)
    }

    /**
     * Retrieves a factory function for a plugin implementation.
     *
     * Called by PluginManager during JAR loading. Returns null if no factory is registered
     * for the implementation name, which causes plugin loading to fail with descriptive error.
     *
     * @param implementation Fully qualified plugin class name
     * @return Factory lambda or null if not registered
     */
    fun get(implementation: String): (() -> Plugin)? = factories[implementation]

    /**
     * Clears all factory registrations.
     *
     * Used for testing to reset registry state between test cases.
     */
    fun clear() = factories.clear()
}
