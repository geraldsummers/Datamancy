package org.datamancy.ldapsync.plugins

import org.datamancy.ldapsync.api.LdapUser
import org.datamancy.ldapsync.api.SyncPlugin
import org.datamancy.ldapsync.api.SyncResult
import org.slf4j.LoggerFactory

/**
 * Plugin to sync LDAP users to SOGo groupware
 *
 * SOGo uses LDAP directly for authentication, but requires user accounts
 * to be initialized in its database for preferences, calendars, contacts, etc.
 *
 * This plugin uses docker exec with sogo-tool to create user accounts,
 * similar to the MailuSyncPlugin pattern.
 *
 * Note: SOGo is protected by Authelia forward_auth and authenticates against
 * the same LDAP directory, so users must exist in LDAP first.
 */
class SOGoSyncPlugin : SyncPlugin {
    override val pluginId = "sogo"
    override val pluginName = "SOGo Groupware"

    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var sogoContainer: String
    private lateinit var defaultDomain: String

    override suspend fun init(config: Map<String, String>) {
        sogoContainer = config["sogo_container"] ?: "sogo"
        defaultDomain = config["default_domain"] ?: error("default_domain is required")

        log.info("Initialized SOGo sync plugin:")
        log.info("  Container: $sogoContainer")
        log.info("  Domain: $defaultDomain")
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            // Check if SOGo container is running
            val result = execCommand(listOf("docker", "ps", "--filter", "name=$sogoContainer", "--format", "{{.Names}}"))
            result.trim() == sogoContainer
        } catch (e: Exception) {
            log.error("Health check failed: ${e.message}")
            false
        }
    }

    override suspend fun syncUser(user: LdapUser): SyncResult {
        return try {
            // Check if user already exists in SOGo database
            val userExists = checkUserExists(user.uid)

            if (userExists) {
                log.info("User ${user.uid} already exists in SOGo, skipping")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            // Create user via sogo-tool
            // sogo-tool creates the user's SOGo database tables and preferences
            val command = listOf(
                "docker", "exec", sogoContainer,
                "sogo-tool", "user-create",
                user.uid,
                "-p", "dummy",  // Password not used since SOGo auth is via LDAP
                "-e", user.email,
                "-n", user.displayName
            )

            val output = execCommand(command)

            log.info("Created SOGo user: ${user.uid}")
            log.debug("Output: $output")

            SyncResult.Created(user.uid, "Created SOGo groupware account")

        } catch (e: Exception) {
            // Check if error is because user already exists
            if (e.message?.contains("already exists") == true) {
                log.info("User ${user.uid} already exists in SOGo (detected via error)")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            log.error("Failed to sync user ${user.uid} to SOGo: ${e.message}", e)
            SyncResult.Failed(user.uid, "Failed to create SOGo account: ${e.message}", e)
        }
    }

    override suspend fun deleteUser(uid: String): Boolean {
        return try {
            val command = listOf(
                "docker", "exec", sogoContainer,
                "sogo-tool", "user-delete",
                uid
            )

            execCommand(command)
            log.info("Deleted SOGo user: $uid")
            true
        } catch (e: Exception) {
            log.error("Failed to delete user $uid from SOGo: ${e.message}")
            false
        }
    }

    override suspend fun getStats(): Map<String, Any> {
        return try {
            // Get user count via sogo-tool
            val command = listOf(
                "docker", "exec", sogoContainer,
                "sogo-tool", "user-list"
            )

            val output = execCommand(command)
            val userCount = output.lines().count { it.isNotBlank() }

            mapOf(
                "total_users" to userCount,
                "domain" to defaultDomain,
                "container" to sogoContainer
            )
        } catch (e: Exception) {
            log.error("Failed to get SOGo stats: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Check if a user exists in SOGo
     * Uses sogo-tool to verify user existence
     */
    private suspend fun checkUserExists(username: String): Boolean {
        return try {
            val command = listOf(
                "docker", "exec", sogoContainer,
                "sogo-tool", "user-list"
            )

            val output = execCommand(command)
            output.contains(username)
        } catch (e: Exception) {
            log.warn("Failed to check if user exists: ${e.message}")
            false
        }
    }

    /**
     * Execute a shell command and return stdout
     */
    private fun execCommand(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Command failed with exit code $exitCode: ${command.joinToString(" ")}\nOutput: $output")
        }

        return output
    }
}
