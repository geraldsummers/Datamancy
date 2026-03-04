package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Helper for interacting with HashiCorp Vault API
 *
 * Provides methods for:
 * - LDAP authentication
 * - KV v2 secret read/write operations
 * - Policy enforcement testing
 * - Health checks
 *
 * Used by VaultTests to validate multi-user secret isolation
 */
class VaultHelper(
    private val vaultUrl: String,
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Login to Vault using LDAP credentials
     *
     * @param username LDAP username (e.g., "sysadmin", "alice")
     * @param password LDAP password
     * @return Vault client token for subsequent API calls
     * @throws Exception if authentication fails
     */
    suspend fun loginWithLdap(username: String, password: String): String {
        val response = httpClient.post("$vaultUrl/v1/auth/ldap/login/$username") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$password"}""")
        }

        if (response.status != HttpStatusCode.OK) {
            throw Exception("LDAP login failed for user '$username': ${response.bodyAsText()}")
        }

        val body = json.decodeFromString<VaultLoginResponse>(response.bodyAsText())
        return body.auth.client_token
    }

    /**
     * Write a secret to Vault KV v2 engine
     *
     * @param token Vault token from login
     * @param path Secret path (e.g., "users/alice/api-keys/binance")
     * @param data Key-value pairs to store
     * @throws Exception if write fails (permission denied, etc.)
     */
    suspend fun writeSecret(token: String, path: String, data: Map<String, String>) {
        val jsonData = buildJsonObject {
            put("data", buildJsonObject {
                data.forEach { (k, v) -> put(k, v) }
            })
        }

        val response = httpClient.post("$vaultUrl/v1/secret/data/$path") {
            header("X-Vault-Token", token)
            contentType(ContentType.Application.Json)
            setBody(jsonData.toString())
        }

        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw Exception("Write secret failed for path '$path': $body")
        }
    }

    /**
     * Read a secret from Vault KV v2 engine
     *
     * @param token Vault token from login
     * @param path Secret path to read
     * @return Map of key-value pairs, or null if not found
     * @throws Exception if read fails due to permission denied
     */
    suspend fun readSecret(token: String, path: String): Map<String, String>? {
        val response = httpClient.get("$vaultUrl/v1/secret/data/$path") {
            header("X-Vault-Token", token)
        }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = json.decodeFromString<VaultReadResponse>(response.bodyAsText())
                body.data.data.mapValues { it.value.jsonPrimitive.content }
            }
            HttpStatusCode.NotFound -> null
            HttpStatusCode.Forbidden -> {
                throw Exception("Forbidden: No access to path '$path'")
            }
            else -> {
                throw Exception("Read secret failed for path '$path': ${response.bodyAsText()}")
            }
        }
    }

    /**
     * List secrets at a given path
     *
     * @param token Vault token from login
     * @param path Path to list (e.g., "users/alice")
     * @return List of secret names at this path
     */
    suspend fun listSecrets(token: String, path: String): List<String> {
        val response = httpClient.request("$vaultUrl/v1/secret/metadata/$path") {
            method = HttpMethod("LIST")
            header("X-Vault-Token", token)
        }

        if (response.status != HttpStatusCode.OK) {
            return emptyList()
        }

        val body = json.decodeFromString<VaultListResponse>(response.bodyAsText())
        return body.data.keys
    }

    /**
     * Delete a secret from Vault KV v2 engine
     *
     * @param token Vault token from login
     * @param path Secret path to delete
     */
    suspend fun deleteSecret(token: String, path: String) {
        val response = httpClient.delete("$vaultUrl/v1/secret/data/$path") {
            header("X-Vault-Token", token)
        }

        if (response.status.value !in 200..299 && response.status != HttpStatusCode.NotFound) {
            throw Exception("Delete secret failed for path '$path': ${response.bodyAsText()}")
        }
    }

    /**
     * Check if Vault is healthy, initialized, and unsealed
     *
     * @return VaultHealth status object
     */
    suspend fun healthCheck(): VaultHealth {
        val response = httpClient.get("$vaultUrl/v1/sys/health?standbyok=true&perfstandbyok=true&uninitcode=200&sealedcode=503")

        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = json.decodeFromString<VaultHealthResponse>(response.bodyAsText())
                VaultHealth(
                    initialized = body.initialized,
                    sealed = body.sealed,
                    standby = body.standby ?: false
                )
            }
            HttpStatusCode.ServiceUnavailable -> {
                // Vault is sealed
                val body = json.decodeFromString<VaultHealthResponse>(response.bodyAsText())
                VaultHealth(
                    initialized = body.initialized,
                    sealed = true,
                    standby = false
                )
            }
            else -> {
                throw Exception("Health check failed: ${response.status} - ${response.bodyAsText()}")
            }
        }
    }

    /**
     * Check if LDAP auth backend is enabled
     */
    suspend fun isLdapEnabled(token: String): Boolean {
        val response = httpClient.get("$vaultUrl/v1/sys/auth") {
            header("X-Vault-Token", token)
        }

        if (response.status != HttpStatusCode.OK) {
            return false
        }

        val body = response.bodyAsText()
        return body.contains("ldap/")
    }

    /**
     * Lookup own token info (useful for debugging)
     */
    suspend fun lookupSelf(token: String): TokenInfo {
        val response = httpClient.get("$vaultUrl/v1/auth/token/lookup-self") {
            header("X-Vault-Token", token)
        }

        if (response.status != HttpStatusCode.OK) {
            throw Exception("Token lookup failed: ${response.bodyAsText()}")
        }

        val body = json.decodeFromString<TokenLookupResponse>(response.bodyAsText())
        return TokenInfo(
            displayName = body.data.display_name,
            policies = body.data.policies,
            entityId = body.data.entity_id ?: "",
            ttl = body.data.ttl
        )
    }

    // Serialization classes
    @Serializable
    data class VaultLoginResponse(val auth: AuthData)

    @Serializable
    data class AuthData(val client_token: String, val policies: List<String> = emptyList())

    @Serializable
    data class VaultReadResponse(val data: DataWrapper)

    @Serializable
    data class DataWrapper(val data: Map<String, JsonElement>)

    @Serializable
    data class VaultListResponse(val data: KeysWrapper)

    @Serializable
    data class KeysWrapper(val keys: List<String>)

    @Serializable
    data class VaultHealthResponse(
        val initialized: Boolean,
        val sealed: Boolean,
        val standby: Boolean? = null
    )

    @Serializable
    data class TokenLookupResponse(val data: TokenData)

    @Serializable
    data class TokenData(
        val display_name: String,
        val policies: List<String>,
        val entity_id: String? = null,
        val ttl: Int
    )

    data class VaultHealth(
        val initialized: Boolean,
        val sealed: Boolean,
        val standby: Boolean
    )

    data class TokenInfo(
        val displayName: String,
        val policies: List<String>,
        val entityId: String,
        val ttl: Int
    )
}
