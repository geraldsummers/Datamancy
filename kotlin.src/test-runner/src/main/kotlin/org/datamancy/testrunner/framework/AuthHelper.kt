package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*


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


class AuthHelper(
    private val autheliaUrl: String,
    private val client: HttpClient,
    private val ldapHelper: LdapHelper? = null
) {
    private var sessionCookie: Cookie? = null
    private var ephemeralUser: TestUser? = null

    
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
            val response = client.get("$autheliaUrl/api/verify") {
                cookie(cookie.name, cookie.value)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    
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
