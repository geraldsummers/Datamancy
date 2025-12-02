package org.datamancy.ldapsync.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import org.datamancy.ldapsync.api.LdapUser
import org.datamancy.ldapsync.api.SyncPlugin
import org.datamancy.ldapsync.api.SyncResult
import org.slf4j.LoggerFactory

/**
 * Plugin to sync LDAP users to Mailu email server
 *
 * Uses Mailu CLI via docker exec to create/manage users
 * since Mailu doesn't have a REST API for user management
 */
class MailuSyncPlugin : SyncPlugin {
    override val pluginId = "mailu"
    override val pluginName = "Mailu Email Server"

    private val log = LoggerFactory.getLogger(javaClass)
    private val json: ObjectMapper = jacksonObjectMapper()

    private lateinit var mailuAdminContainer: String
    private lateinit var defaultDomain: String
    private lateinit var defaultQuotaMb: String
    private var defaultPassword: String = "ChangeMe123!" // Will be overridden by user on first login

    override suspend fun init(config: Map<String, String>) {
        mailuAdminContainer = config["mailu_admin_container"] ?: "mailu-admin"
        defaultDomain = config["default_domain"] ?: error("default_domain is required")
        defaultQuotaMb = config["default_quota_mb"] ?: "5000"
        defaultPassword = config["default_password"] ?: defaultPassword

        log.info("Initialized Mailu sync plugin:")
        log.info("  Container: $mailuAdminContainer")
        log.info("  Domain: $defaultDomain")
        log.info("  Default quota: ${defaultQuotaMb}MB")
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            // Check if Mailu admin container is running
            val result = execCommand(listOf("docker", "ps", "--filter", "name=$mailuAdminContainer", "--format", "{{.Names}}"))
            result.trim() == mailuAdminContainer
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

        return try {
            // Check if user already exists
            val userExists = checkUserExists(emailAddress)

            if (userExists) {
                log.info("User $emailAddress already exists in Mailu, skipping")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            // Create user via Mailu CLI
            val command = listOf(
                "docker", "exec", mailuAdminContainer,
                "flask", "mailu", "user", "create",
                emailAddress,
                defaultPassword,
                "--quota", defaultQuotaMb
            )

            val output = execCommand(command)

            log.info("Created Mailu user: $emailAddress")
            log.debug("Output: $output")

            SyncResult.Created(user.uid, "Created email account: $emailAddress")

        } catch (e: Exception) {
            log.error("Failed to sync user ${user.uid} to Mailu: ${e.message}", e)
            SyncResult.Failed(user.uid, "Failed to create Mailu account: ${e.message}", e)
        }
    }

    override suspend fun deleteUser(uid: String): Boolean {
        return try {
            val emailAddress = "$uid@$defaultDomain"

            val command = listOf(
                "docker", "exec", mailuAdminContainer,
                "flask", "mailu", "user", "delete",
                emailAddress
            )

            execCommand(command)
            log.info("Deleted Mailu user: $emailAddress")
            true
        } catch (e: Exception) {
            log.error("Failed to delete user $uid from Mailu: ${e.message}")
            false
        }
    }

    override suspend fun getStats(): Map<String, Any> {
        return try {
            // Get user count via CLI
            val command = listOf(
                "docker", "exec", mailuAdminContainer,
                "flask", "mailu", "user", "list"
            )

            val output = execCommand(command)
            val userCount = output.lines().count { it.contains("@") }

            mapOf(
                "total_users" to userCount,
                "domain" to defaultDomain
            )
        } catch (e: Exception) {
            log.error("Failed to get Mailu stats: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Check if a user exists in Mailu
     */
    private suspend fun checkUserExists(email: String): Boolean {
        return try {
            val command = listOf(
                "docker", "exec", mailuAdminContainer,
                "flask", "mailu", "user", "list"
            )

            val output = execCommand(command)
            output.contains(email)
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
