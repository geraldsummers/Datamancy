package org.datamancy.ldapsync.plugins

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
 * Plugin to sync LDAP admins to Dockge container management UI
 *
 * Dockge doesn't support OIDC, but has a simple user management system.
 * This plugin creates admin accounts for users in the 'admins' LDAP group.
 *
 * Note: Dockge is protected by Authelia forward_auth, so users must pass
 * Authelia authentication before reaching Dockge. This plugin ensures they
 * have a Dockge account once authenticated.
 */
class DockgeSyncPlugin : SyncPlugin {
    override val pluginId = "dockge"
    override val pluginName = "Dockge Container Manager"

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
    }

    private lateinit var apiUrl: String
    private lateinit var defaultPassword: String

    override suspend fun init(config: Map<String, String>) {
        apiUrl = config["api_url"] ?: "http://dockge:5001"
        defaultPassword = config["default_password"] ?: "ChangeMe123!"

        log.info("Initialized Dockge sync plugin:")
        log.info("  API URL: $apiUrl")
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val response = client.get("$apiUrl/info")
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error("Health check failed: ${e.message}")
            false
        }
    }

    override suspend fun syncUser(user: LdapUser): SyncResult {
        // Only sync users in the admins group
        if ("admins" !in user.groups) {
            log.debug("User ${user.uid} not in admins group, skipping Dockge sync")
            return SyncResult.Skipped(user.uid, "Not in admins group")
        }

        return try {
            // Check if user already exists
            val userExists = checkUserExists(user.uid)

            if (userExists) {
                log.info("User ${user.uid} already exists in Dockge, skipping")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            // Create user via Dockge API
            val response = client.post("$apiUrl/api/user") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "username" to user.uid,
                    "password" to defaultPassword,
                    "name" to user.displayName,
                    "email" to user.email,
                    "isAdmin" to true
                ))
            }

            if (response.status.isSuccess()) {
                log.info("Created Dockge admin user: ${user.uid}")
                SyncResult.Created(user.uid, "Created Dockge admin account")
            } else {
                val body = response.bodyAsText()
                log.error("Failed to create Dockge user ${user.uid}: HTTP ${response.status.value} - $body")
                SyncResult.Failed(user.uid, "Failed to create: ${response.status.value}")
            }

        } catch (e: Exception) {
            log.error("Failed to sync user ${user.uid} to Dockge: ${e.message}", e)
            SyncResult.Failed(user.uid, "Failed to create Dockge account: ${e.message}", e)
        }
    }

    override suspend fun deleteUser(uid: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/api/user/$uid")
            if (response.status.isSuccess()) {
                log.info("Deleted Dockge user: $uid")
                true
            } else {
                log.warn("Failed to delete Dockge user $uid: ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            log.error("Failed to delete user $uid from Dockge: ${e.message}")
            false
        }
    }

    override suspend fun getStats(): Map<String, Any> {
        return try {
            val response = client.get("$apiUrl/api/users")
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                // Parse user count from response
                mapOf(
                    "api_url" to apiUrl,
                    "status" to "connected"
                )
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            log.error("Failed to get Dockge stats: ${e.message}")
            emptyMap()
        }
    }

    override suspend fun shutdown() {
        client.close()
    }

    /**
     * Check if a user exists in Dockge
     */
    private suspend fun checkUserExists(username: String): Boolean {
        return try {
            val response = client.get("$apiUrl/api/user/$username")
            response.status.isSuccess()
        } catch (e: Exception) {
            log.debug("User $username does not exist in Dockge")
            false
        }
    }
}
