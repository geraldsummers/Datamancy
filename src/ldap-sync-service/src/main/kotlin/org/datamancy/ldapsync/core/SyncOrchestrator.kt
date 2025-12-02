package org.datamancy.ldapsync.core

import kotlinx.coroutines.*
import org.datamancy.ldapsync.api.SyncPlugin
import org.datamancy.ldapsync.api.SyncResult
import org.slf4j.LoggerFactory

/**
 * Orchestrates LDAP sync across multiple plugins
 */
class SyncOrchestrator(
    private val ldapClient: LdapClient,
    private val plugins: List<SyncPlugin>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Perform a full sync across all registered plugins
     */
    suspend fun performFullSync(): SyncReport = coroutineScope {
        log.info("Starting full LDAP sync with ${plugins.size} plugins")

        // Get all LDAP users
        val users = ldapClient.getAllUsers()
        log.info("Retrieved ${users.size} users from LDAP")

        val results = mutableMapOf<String, List<SyncResult>>()

        // Sync to each plugin in parallel
        plugins.forEach { plugin ->
            log.info("Syncing to plugin: ${plugin.pluginName} (${plugin.pluginId})")

            val pluginResults = users.map { user ->
                async {
                    try {
                        plugin.syncUser(user)
                    } catch (e: Exception) {
                        log.error("Unexpected error syncing user ${user.uid} to ${plugin.pluginId}", e)
                        SyncResult.Failed(user.uid, "Unexpected error: ${e.message}", e)
                    }
                }
            }.awaitAll()

            results[plugin.pluginId] = pluginResults

            // Log summary for this plugin
            val created = pluginResults.count { it is SyncResult.Created }
            val updated = pluginResults.count { it is SyncResult.Updated }
            val skipped = pluginResults.count { it is SyncResult.Skipped }
            val failed = pluginResults.count { it is SyncResult.Failed }

            log.info("Plugin ${plugin.pluginId} sync complete: $created created, $updated updated, $skipped skipped, $failed failed")
        }

        SyncReport(
            totalUsers = users.size,
            pluginResults = results,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Test connectivity to all plugins
     */
    suspend fun healthCheckAll(): Map<String, Boolean> {
        log.info("Running health checks on ${plugins.size} plugins")

        return plugins.associate { plugin ->
            val healthy = try {
                plugin.healthCheck()
            } catch (e: Exception) {
                log.error("Health check failed for ${plugin.pluginId}: ${e.message}")
                false
            }

            log.info("Plugin ${plugin.pluginId} health: ${if (healthy) "OK" else "FAILED"}")
            plugin.pluginId to healthy
        }
    }

    /**
     * Get statistics from all plugins
     */
    suspend fun getAllStats(): Map<String, Map<String, Any>> {
        return plugins.associate { plugin ->
            val stats = try {
                plugin.getStats()
            } catch (e: Exception) {
                log.error("Failed to get stats from ${plugin.pluginId}: ${e.message}")
                mapOf("error" to e.message.toString())
            }
            plugin.pluginId to stats
        }
    }

    /**
     * Shutdown all plugins gracefully
     */
    suspend fun shutdown() {
        log.info("Shutting down sync orchestrator")
        plugins.forEach { plugin ->
            try {
                plugin.shutdown()
            } catch (e: Exception) {
                log.error("Error shutting down plugin ${plugin.pluginId}: ${e.message}")
            }
        }
        ldapClient.disconnect()
    }
}

/**
 * Report of a sync operation
 */
data class SyncReport(
    val totalUsers: Int,
    val pluginResults: Map<String, List<SyncResult>>,
    val timestamp: Long
) {
    fun summarize(): String {
        val builder = StringBuilder()
        builder.appendLine("=== LDAP Sync Report ===")
        builder.appendLine("Time: ${java.time.Instant.ofEpochMilli(timestamp)}")
        builder.appendLine("Total LDAP Users: $totalUsers")
        builder.appendLine()

        pluginResults.forEach { (pluginId, results) ->
            val created = results.count { it is SyncResult.Created }
            val updated = results.count { it is SyncResult.Updated }
            val skipped = results.count { it is SyncResult.Skipped }
            val failed = results.count { it is SyncResult.Failed }

            builder.appendLine("Plugin: $pluginId")
            builder.appendLine("  Created: $created")
            builder.appendLine("  Updated: $updated")
            builder.appendLine("  Skipped: $skipped")
            builder.appendLine("  Failed: $failed")

            // Show failed users
            val failures = results.filterIsInstance<SyncResult.Failed>()
            if (failures.isNotEmpty()) {
                builder.appendLine("  Failed users:")
                failures.forEach { failure ->
                    builder.appendLine("    - ${failure.userId}: ${failure.error}")
                }
            }
            builder.appendLine()
        }

        return builder.toString()
    }
}
