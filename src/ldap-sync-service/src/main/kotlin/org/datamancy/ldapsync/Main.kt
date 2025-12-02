package org.datamancy.ldapsync

import kotlinx.coroutines.runBlocking
import org.datamancy.ldapsync.core.LdapClient
import org.datamancy.ldapsync.core.SyncOrchestrator
import org.datamancy.ldapsync.plugins.MailuSyncPlugin
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) = runBlocking {
    log.info("Starting LDAP Sync Service")

    // Parse command line arguments
    val mode = args.getOrNull(0) ?: "sync"

    // Load configuration from environment
    val config = loadConfig()

    // Initialize LDAP client
    val ldapClient = LdapClient(
        host = config["LDAP_HOST"] ?: "ldap",
        port = config["LDAP_PORT"]?.toIntOrNull() ?: 389,
        bindDn = config["LDAP_BIND_DN"] ?: "cn=admin,dc=stack,dc=local",
        bindPassword = config["LDAP_BIND_PASSWORD"] ?: error("LDAP_BIND_PASSWORD required"),
        baseDn = config["LDAP_BASE_DN"] ?: "dc=stack,dc=local",
        usersOu = config["LDAP_USERS_OU"] ?: "ou=users",
        groupsOu = config["LDAP_GROUPS_OU"] ?: "ou=groups"
    )

    try {
        ldapClient.connect()

        // Test LDAP connection
        if (!ldapClient.testConnection()) {
            log.error("Failed to connect to LDAP server")
            exitProcess(1)
        }

        // Initialize plugins
        val plugins = initializePlugins(config)

        if (plugins.isEmpty()) {
            log.warn("No sync plugins configured")
            exitProcess(0)
        }

        // Create orchestrator
        val orchestrator = SyncOrchestrator(ldapClient, plugins)

        // Execute command
        when (mode) {
            "sync" -> {
                log.info("Performing full sync")
                val report = orchestrator.performFullSync()
                println(report.summarize())
            }

            "health" -> {
                log.info("Running health checks")
                val health = orchestrator.healthCheckAll()
                health.forEach { (plugin, healthy) ->
                    println("$plugin: ${if (healthy) "✓ OK" else "✗ FAILED"}")
                }
                if (health.values.any { !it }) {
                    exitProcess(1)
                }
            }

            "stats" -> {
                log.info("Getting statistics")
                val stats = orchestrator.getAllStats()
                stats.forEach { (plugin, pluginStats) ->
                    println("=== $plugin ===")
                    pluginStats.forEach { (key, value) ->
                        println("  $key: $value")
                    }
                }
            }

            "daemon" -> {
                log.info("Starting sync daemon (not yet implemented)")
                log.info("Use cron or systemd timer to run 'sync' command periodically")
                // TODO: Implement continuous sync with configurable interval
            }

            else -> {
                println("Unknown command: $mode")
                println("Usage: ldap-sync-service [sync|health|stats|daemon]")
                exitProcess(1)
            }
        }

        orchestrator.shutdown()

    } catch (e: Exception) {
        log.error("Fatal error", e)
        exitProcess(1)
    } finally {
        ldapClient.disconnect()
    }
}

/**
 * Load configuration from environment variables
 */
private fun loadConfig(): Map<String, String> {
    return System.getenv().toMap()
}

/**
 * Initialize sync plugins based on configuration
 */
private suspend fun initializePlugins(config: Map<String, String>): List<org.datamancy.ldapsync.api.SyncPlugin> {
    val plugins = mutableListOf<org.datamancy.ldapsync.api.SyncPlugin>()

    // Mailu plugin
    if (config["ENABLE_MAILU_SYNC"]?.toBoolean() == true) {
        log.info("Enabling Mailu sync plugin")
        val mailuPlugin = MailuSyncPlugin()
        mailuPlugin.init(
            mapOf(
                "mailu_admin_container" to (config["MAILU_ADMIN_CONTAINER"] ?: "mailu-admin"),
                "default_domain" to (config["MAIL_DOMAIN"] ?: error("MAIL_DOMAIN required for Mailu sync")),
                "default_quota_mb" to (config["MAILU_DEFAULT_QUOTA_MB"] ?: "5000"),
                "default_password" to (config["MAILU_DEFAULT_PASSWORD"] ?: "ChangeMe123!")
            )
        )
        plugins.add(mailuPlugin)
    }

    // Future plugins can be added here:
    // - Mastodon (if not using OIDC)
    // - Matrix/Synapse (for admin user creation)
    // - Custom LDAP-less services

    return plugins
}
