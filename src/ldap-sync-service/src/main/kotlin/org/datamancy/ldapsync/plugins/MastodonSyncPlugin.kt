package org.datamancy.ldapsync.plugins

import org.datamancy.ldapsync.api.LdapUser
import org.datamancy.ldapsync.api.SyncPlugin
import org.datamancy.ldapsync.api.SyncResult
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Plugin to sync LDAP users to Mastodon social network
 *
 * Creates Mastodon accounts for LDAP users via tootctl CLI
 * Users are pre-confirmed and can log in via OIDC
 */
class MastodonSyncPlugin : SyncPlugin {
    override val pluginId = "mastodon"
    override val pluginName = "Mastodon Social Network"

    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var mastodonContainer: String
    private lateinit var defaultDomain: String
    private var autoConfirm: Boolean = true
    private var autoApprove: Boolean = true

    override suspend fun init(config: Map<String, String>) {
        mastodonContainer = config["mastodon_container"] ?: "mastodon-web"
        defaultDomain = config["default_domain"] ?: error("default_domain is required")
        autoConfirm = config["auto_confirm"]?.toBoolean() ?: true
        autoApprove = config["auto_approve"]?.toBoolean() ?: true

        log.info("Initialized Mastodon sync plugin:")
        log.info("  Container: $mastodonContainer")
        log.info("  Domain: $defaultDomain")
        log.info("  Auto-confirm: $autoConfirm")
        log.info("  Auto-approve: $autoApprove")
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            // Check if Mastodon container is running
            val result = execCommand(listOf("docker", "ps", "--filter", "name=$mastodonContainer", "--format", "{{.Names}}"))
            result.trim() == mastodonContainer
        } catch (e: Exception) {
            log.error("Health check failed: ${e.message}")
            false
        }
    }

    override suspend fun syncUser(user: LdapUser): SyncResult {
        val emailAddress = if (user.email.contains("@")) {
            user.email
        } else {
            "${user.uid}@$defaultDomain"
        }

        // Generate Mastodon username (alphanumeric + underscore only)
        val mastodonUsername = user.uid.replace(Regex("[^a-zA-Z0-9_]"), "_")

        return try {
            // Check if user already exists
            val userExists = checkUserExists(mastodonUsername)

            if (userExists) {
                log.info("User $mastodonUsername already exists in Mastodon, skipping")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            // Create user via tootctl
            val command = mutableListOf(
                "docker", "exec", mastodonContainer,
                "bin/tootctl", "accounts", "create",
                mastodonUsername,
                "--email=$emailAddress"
            )

            if (autoConfirm) {
                command.add("--confirmed")
            }

            if (autoApprove) {
                command.add("--approve")
            }

            val output = execCommand(command)

            log.info("Created Mastodon account: $mastodonUsername ($emailAddress)")
            log.debug("Output: $output")

            SyncResult.Created(user.uid, "Created Mastodon account: $mastodonUsername")

        } catch (e: Exception) {
            log.error("Failed to sync user ${user.uid} to Mastodon: ${e.message}", e)
            SyncResult.Failed(user.uid, "Failed to create Mastodon account: ${e.message}", e)
        }
    }

    override suspend fun deleteUser(uid: String): Boolean {
        return try {
            val mastodonUsername = uid.replace(Regex("[^a-zA-Z0-9_]"), "_")

            val command = listOf(
                "docker", "exec", mastodonContainer,
                "bin/tootctl", "accounts", "delete",
                mastodonUsername
            )

            execCommand(command)
            log.info("Deleted Mastodon account: $mastodonUsername")
            true
        } catch (e: Exception) {
            log.error("Failed to delete Mastodon account for $uid: ${e.message}")
            false
        }
    }

    override suspend fun getStats(): Map<String, Any> {
        return try {
            // Get user count from Mastodon
            val output = execCommand(listOf(
                "docker", "exec", mastodonContainer,
                "bundle", "exec", "rails", "runner",
                "puts User.count"
            ))

            val userCount = output.trim().toIntOrNull() ?: 0

            mapOf(
                "total_users" to userCount
            )
        } catch (e: Exception) {
            log.error("Failed to get Mastodon stats: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Check if a Mastodon user already exists
     */
    private fun checkUserExists(username: String): Boolean {
        return try {
            val output = execCommand(listOf(
                "docker", "exec", mastodonContainer,
                "bundle", "exec", "rails", "runner",
                "puts Account.exists?(username: '$username')"
            ))
            output.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute a shell command and return its output
     */
    private fun execCommand(command: List<String>): String {
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readText()
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output")
        }

        return output
    }
}
