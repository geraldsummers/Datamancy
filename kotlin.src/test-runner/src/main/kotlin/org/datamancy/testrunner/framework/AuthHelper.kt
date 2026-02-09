package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Validates username and password meet security requirements before authentication.
 *
 * This prevents tests from accidentally using weak credentials that might bypass
 * security checks or create invalid test users in LDAP.
 *
 * @param username Username (must be 3-64 characters, non-blank)
 * @param password Password (must be 8+ characters, not in weak password list)
 * @throws IllegalArgumentException if validation fails
 */
fun validateCredentials(username: String, password: String) {
    require(username.isNotBlank()) { "Username cannot be blank" }
    require(username.length in 3..64) { "Username must be 3-64 characters" }
    require(password.isNotBlank()) { "Password cannot be blank" }
    require(password.length >= 8) { "Password must be at least 8 characters" }

    
    val weakPasswords = setOf("password", "12345678", "admin123", "test1234")
    require(password.lowercase() !in weakPasswords) {
        "Password is too weak (common password detected)"
    }
}

/**
 * Authelia authentication helper for testing SSO and session management.
 *
 * AuthHelper manages the authentication cascade that protects all services in the
 * Datamancy stack. It validates the first-factor authentication flow and manages
 * session cookies that grant access to forward-auth protected services.
 *
 * ## Authentication Cascade
 * The AuthHelper orchestrates testing of this flow:
 * 1. **LDAP**: User credentials validated against OpenLDAP directory
 * 2. **Authelia**: Session cookie created upon successful LDAP bind
 * 3. **Caddy**: Forward-auth validates session cookie before proxying requests
 * 4. **Services**: Access granted to Grafana, BookStack, Mastodon, etc.
 *
 * ## Ephemeral User Management
 * Tests use ephemeral users to ensure isolation and repeatability:
 * - `loginWithEphemeralUser()`: Creates temporary LDAP user, authenticates, returns session
 * - `cleanupEphemeralUser()`: Deletes LDAP user and clears session
 * - **Why this matters**: Tests don't interfere with each other or pollute LDAP with test accounts
 *
 * ## Cross-Service Integration
 * Session cookies obtained here enable tests to:
 * - Access Grafana dashboards behind forward-auth
 * - Validate SSO works across multiple services (single login, multiple apps)
 * - Test OIDC flows that require existing Authelia sessions
 *
 * @property autheliaUrl Authelia API endpoint (e.g., "http://authelia:9091")
 * @property client Ktor HTTP client for making authentication requests
 * @property ldapHelper Optional LdapHelper for ephemeral user creation
 */
class AuthHelper(
    private val autheliaUrl: String,
    private val client: HttpClient,
    private val ldapHelper: LdapHelper? = null
) {
    private var sessionCookie: Cookie? = null
    private var ephemeralUser: TestUser? = null

    /**
     * Authenticates with Authelia using username and password (first-factor authentication).
     *
     * This tests the LDAP → Authelia authentication flow:
     * 1. Credentials validated before sending to prevent weak password usage
     * 2. POST to Authelia `/api/firstfactor` with username/password
     * 3. Authelia performs LDAP bind to validate credentials
     * 4. On success, Authelia returns `authelia_session` cookie
     * 5. Session cookie can be used for accessing forward-auth protected services
     *
     * Tests use this to:
     * - Validate admin credentials work
     * - Test ephemeral user authentication
     * - Obtain sessions for OIDC flow testing
     * - Verify SSO session creation
     *
     * @param username LDAP username (default: "admin")
     * @param password User password
     * @return AuthResult.Success with session cookie, or AuthResult.Error with details
     */
    suspend fun login(username: String = "admin", password: String): AuthResult {
        return try {
            
            try {
                validateCredentials(username, password)
            } catch (e: IllegalArgumentException) {
                return AuthResult.Error("Credential validation failed: ${e.message}")
            }
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
                    
                    val cookies = response.setCookie()
                    sessionCookie = cookies.find { it.name == "authelia_session" }

                    sessionCookie?.let {
                        AuthResult.Success(it)
                    } ?: AuthResult.Error("No session cookie in response")
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


    suspend fun authenticatedGet(url: String): HttpResponse {
        return client.get(url) {
            sessionCookie?.let { cookie(it.name, it.value) }
        }
    }


    suspend fun authenticatedPost(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.post(url) {
            sessionCookie?.let { cookie(it.name, it.value) }
            block()
        }
    }

    
    fun logout() {
        sessionCookie = null
    }

    
    fun isAuthenticated(): Boolean = sessionCookie != null

    
    suspend fun verifyAuth(): Boolean {
        val cookie = sessionCookie ?: return false

        return try {
            // Manually add cookie to ensure it's sent to /api/verify endpoint
            // Don't override domain/path - let HttpCookies plugin handle it
            val response = client.get("$autheliaUrl/api/verify") {
                cookie(cookie.name, cookie.value)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates an ephemeral LDAP user and authenticates with it in one operation.
     *
     * This is critical for test isolation. Each test can create its own temporary user,
     * preventing tests from sharing state or interfering with each other's authentication.
     *
     * ## Why Ephemeral Users Are Critical
     * - **Isolation**: Tests don't share users, avoiding race conditions and state pollution
     * - **Cleanup**: Users are deleted after tests, preventing LDAP accumulation
     * - **Repeatability**: Same test can run multiple times without conflicts
     * - **Security**: Tests with real passwords that get deleted, not hardcoded test accounts
     *
     * ## Integration Testing Use Cases
     * - Testing user-specific OIDC flows without admin privileges
     * - Validating group-based access control (pass different groups parameter)
     * - Testing user lifecycle (create → authenticate → use services → delete)
     * - Simulating multi-user scenarios with parallel ephemeral users
     *
     * @param groups LDAP groups to assign the user to (default: ["users"])
     * @return AuthResult.Success with session cookie, or AuthResult.Error if creation fails
     */
    suspend fun loginWithEphemeralUser(groups: List<String> = listOf("users")): AuthResult {
        if (ldapHelper == null) {
            return AuthResult.Error("LDAP helper not configured - cannot create ephemeral users")
        }

        
        val userResult = ldapHelper.createEphemeralUser(groups)
        if (userResult.isFailure) {
            return AuthResult.Error("Failed to create ephemeral user: ${userResult.exceptionOrNull()?.message}")
        }

        val user = userResult.getOrNull() ?: return AuthResult.Error("Failed to create ephemeral user")
        ephemeralUser = user

        
        return login(user.username, user.password)
    }

    
    fun cleanupEphemeralUser() {
        ephemeralUser?.let { user ->
            ldapHelper?.deleteTestUser(user.username)
            ephemeralUser = null
        }
        logout()
    }

    
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
