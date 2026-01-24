package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Helper for Authelia authentication in tests
 *
 * Authelia auth flow:
 * 1. POST to /api/firstfactor with username/password
 * 2. Get session cookie from response
 * 3. Use cookie for subsequent authenticated requests
 */
class AuthHelper(
    private val autheliaUrl: String,
    private val client: HttpClient,
    private val ldapHelper: LdapHelper? = null
) {
    private var sessionCookie: Cookie? = null
    private var ephemeralUser: TestUser? = null

    /**
     * Login to Authelia and store session cookie
     *
     * Default test credentials from LDAP:
     * - username: "admin"
     * - password: LDAP_ADMIN_PASSWORD from environment
     */
    suspend fun login(username: String = "admin", password: String): AuthResult {
        return try {
            val response = client.post("$autheliaUrl/api/firstfactor") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("username", username)
                    put("password", password)
                    put("keepMeLoggedIn", false)
                })
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    // Extract session cookie
                    val cookies = response.setCookie()
                    sessionCookie = cookies.find { it.name == "authelia_session" }

                    if (sessionCookie != null) {
                        AuthResult.Success(sessionCookie!!)
                    } else {
                        AuthResult.Error("No session cookie in response")
                    }
                }
                HttpStatusCode.Unauthorized -> {
                    val body = response.bodyAsText()
                    AuthResult.Error("Authentication failed: $body")
                }
                else -> {
                    AuthResult.Error("Unexpected status: ${response.status.value}")
                }
            }
        } catch (e: Exception) {
            AuthResult.Error("Login error: ${e.message}")
        }
    }

    /**
     * Make an authenticated request using stored session cookie
     */
    suspend fun authenticatedGet(url: String): HttpResponse {
        return client.get(url) {
            sessionCookie?.let { cookie(it.name, it.value) }
        }
    }

    /**
     * Make an authenticated POST request
     */
    suspend fun authenticatedPost(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.post(url) {
            sessionCookie?.let { cookie(it.name, it.value) }
            block()
        }
    }

    /**
     * Clear session (logout)
     */
    fun logout() {
        sessionCookie = null
    }

    /**
     * Check if currently authenticated
     */
    fun isAuthenticated(): Boolean = sessionCookie != null

    /**
     * Verify authentication is still valid
     */
    suspend fun verifyAuth(): Boolean {
        if (sessionCookie == null) return false

        return try {
            val response = client.get("$autheliaUrl/api/verify") {
                cookie(sessionCookie!!.name, sessionCookie!!.value)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create ephemeral test user and login
     *
     * This creates a temporary LDAP user, logs in, and returns the session.
     * Call cleanupEphemeralUser() when done to delete the user.
     */
    suspend fun loginWithEphemeralUser(groups: List<String> = listOf("users")): AuthResult {
        if (ldapHelper == null) {
            return AuthResult.Error("LDAP helper not configured - cannot create ephemeral users")
        }

        // Create ephemeral user
        val userResult = ldapHelper.createEphemeralUser(groups)
        if (userResult.isFailure) {
            return AuthResult.Error("Failed to create ephemeral user: ${userResult.exceptionOrNull()?.message}")
        }

        ephemeralUser = userResult.getOrNull()!!

        // Login with ephemeral user
        return login(ephemeralUser!!.username, ephemeralUser!!.password)
    }

    /**
     * Cleanup ephemeral test user
     */
    fun cleanupEphemeralUser() {
        ephemeralUser?.let { user ->
            ldapHelper?.deleteTestUser(user.username)
            ephemeralUser = null
        }
        logout()
    }

    /**
     * Get current ephemeral user (if any)
     */
    fun getEphemeralUser(): TestUser? = ephemeralUser
}

sealed class AuthResult {
    data class Success(val sessionCookie: Cookie) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
    val keepMeLoggedIn: Boolean = false
)
