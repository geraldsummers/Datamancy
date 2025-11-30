package org.example.host

import org.example.api.Plugin

/**
 * Registry for plugin factories to avoid dynamic class loading/reflection.
 * Key is typically the fully-qualified class name string placed into PluginManifest.implementation.
 */
object PluginFactories {
    private val factories = mutableMapOf<String, () -> Plugin>()

    fun register(implementation: String, factory: () -> Plugin) {
        factories[implementation] = factory
    }

    fun unregister(implementation: String) {
        factories.remove(implementation)
    }

    fun get(implementation: String): (() -> Plugin)? = factories[implementation]

    fun clear() = factories.clear()
}
