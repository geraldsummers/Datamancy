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
import java.util.Base64

/**
 * Plugin to sync LDAP users to Kopia backup server
 *
 * Kopia doesn't support OIDC, but has a REST API for user management.
 * This plugin creates user accounts so they can access Kopia after
 * passing Authelia forward_auth.
 *
 * Users get a default password and should change it on first login.
 */
class KopiaSyncPlugin : SyncPlugin {
    override val pluginId = "kopia"
    override val pluginName = "Kopia Backup Server"

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
    }

    private lateinit var apiUrl: String
    private lateinit var serverPassword: String
    private lateinit var defaultUserPassword: String

    override suspend fun init(config: Map<String, String>) {
        apiUrl = config["api_url"] ?: "http://kopia:51515"
        serverPassword = config["server_password"] ?: error("server_password is required for Kopia API")
        defaultUserPassword = config["default_user_password"] ?: "ChangeMe123!"

        log.info("Initialized Kopia sync plugin:")
        log.info("  API URL: $apiUrl")
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val response = client.get("$apiUrl/api/v1/repo/status") {
                basicAuth("admin", serverPassword)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.error("Health check failed: ${e.message}")
            false
        }
    }

    override suspend fun syncUser(user: LdapUser): SyncResult {
        return try {
            // Check if user already exists
            val userExists = checkUserExists(user.uid)

            if (userExists) {
                log.info("User ${user.uid} already exists in Kopia, skipping")
                return SyncResult.Skipped(user.uid, "User already exists")
            }

            // Create user via Kopia API
            // Kopia API endpoint: POST /api/v1/users
            val response = client.post("$apiUrl/api/v1/users") {
                basicAuth("admin", serverPassword)
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "username" to user.uid,
                    "password" to defaultUserPassword,
                    "email" to user.email,
                    "displayName" to user.displayName
                ))
            }

            if (response.status.isSuccess()) {
                log.info("Created Kopia user: ${user.uid}")
                SyncResult.Created(user.uid, "Created Kopia backup account")
            } else {
                val body = response.bodyAsText()
                log.error("Failed to create Kopia user ${user.uid}: HTTP ${response.status.value} - $body")
                SyncResult.Failed(user.uid, "Failed to create: ${response.status.value}")
            }

        } catch (e: Exception) {
            log.error("Failed to sync user ${user.uid} to Kopia: ${e.message}", e)
            SyncResult.Failed(user.uid, "Failed to create Kopia account: ${e.message}", e)
        }
    }

    override suspend fun deleteUser(uid: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/api/v1/users/$uid") {
                basicAuth("admin", serverPassword)
            }
            if (response.status.isSuccess()) {
                log.info("Deleted Kopia user: $uid")
                true
            } else {
                log.warn("Failed to delete Kopia user $uid: ${response.status.value}")
                false
            }
        } catch (e: Exception) {
            log.error("Failed to delete user $uid from Kopia: ${e.message}")
            false
        }
    }

    override suspend fun getStats(): Map<String, Any> {
        return try {
            val response = client.get("$apiUrl/api/v1/users") {
                basicAuth("admin", serverPassword)
            }
            if (response.status.isSuccess()) {
                mapOf(
                    "api_url" to apiUrl,
                    "status" to "connected"
                )
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            log.error("Failed to get Kopia stats: ${e.message}")
            emptyMap()
        }
    }

    override suspend fun shutdown() {
        client.close()
    }

    /**
     * Check if a user exists in Kopia
     */
    private suspend fun checkUserExists(username: String): Boolean {
        return try {
            val response = client.get("$apiUrl/api/v1/users/$username") {
                basicAuth("admin", serverPassword)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.debug("User $username does not exist in Kopia")
            false
        }
    }

    /**
     * Helper to add basic auth header
     */
    private fun HttpRequestBuilder.basicAuth(username: String, password: String) {
        val credentials = "$username:$password"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
        header(HttpHeaders.Authorization, "Basic $encoded")
    }
}
