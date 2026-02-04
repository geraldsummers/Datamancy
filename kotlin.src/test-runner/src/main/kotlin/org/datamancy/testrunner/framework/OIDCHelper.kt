package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*

/**
 * OIDC/OAuth2 Testing Helper
 *
 * Implements OAuth2 Authorization Code Flow for testing OIDC integration
 * with Authelia as the identity provider.
 */
class OIDCHelper(
    private val autheliaUrl: String,
    private val client: HttpClient,
    private val auth: AuthHelper
) {
    /**
     * Perform complete OIDC authorization code flow
     *
     * Steps:
     * 1. Login to get session
     * 2. Request authorization code
     * 3. Exchange code for tokens
     */
    suspend fun performFullFlow(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        scope: String = "openid profile email",
        user: TestUser
    ): OIDCTokens {
        // Step 1: Login to Authelia
        val authResult = auth.login(user.username, user.password)
        require(authResult is AuthResult.Success) { "Login failed for OIDC flow" }

        // Step 2: Get authorization code
        val authCode = getAuthorizationCode(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope
        )

        // Step 3: Exchange code for tokens
        return exchangeCodeForTokens(
            clientId = clientId,
            clientSecret = clientSecret,
            code = authCode,
            redirectUri = redirectUri
        )
    }

    /**
     * Request authorization code from Authelia
     * Requires active session (must be logged in first)
     */
    suspend fun getAuthorizationCode(
        clientId: String,
        redirectUri: String,
        scope: String = "openid profile email",
        state: String = UUID.randomUUID().toString()
    ): String {
        val authorizeUrl = "$autheliaUrl/api/oidc/authorization"

        val response = auth.authenticatedGet("$authorizeUrl?${buildString {
            append("client_id=$clientId")
            append("&redirect_uri=${redirectUri.encodeURLParameter()}")
            append("&response_type=code")
            append("&scope=${scope.encodeURLParameter()}")
            append("&state=$state")
        }}")

        // Authorization endpoint should redirect to callback with code
        val location = response.headers["Location"]
            ?: throw IllegalStateException("No redirect from authorization endpoint")

        // Extract code from callback URL
        val codeParam = location.substringAfter("code=").substringBefore("&")
        require(codeParam.isNotBlank()) { "No authorization code in redirect: $location" }

        return codeParam
    }

    /**
     * Exchange authorization code for access token, ID token, and refresh token
     */
    suspend fun exchangeCodeForTokens(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String
    ): OIDCTokens {
        val tokenUrl = "$autheliaUrl/api/oidc/token"

        val response = client.post(tokenUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(clientId, clientSecret)
            setBody(buildString {
                append("grant_type=authorization_code")
                append("&code=${code.encodeURLParameter()}")
                append("&redirect_uri=${redirectUri.encodeURLParameter()}")
            })
        }

        require(response.status == HttpStatusCode.OK) {
            "Token exchange failed: ${response.status} - ${response.bodyAsText()}"
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        return OIDCTokens(
            accessToken = json["access_token"]?.jsonPrimitive?.content,
            idToken = json["id_token"]?.jsonPrimitive?.content,
            refreshToken = json["refresh_token"]?.jsonPrimitive?.content,
            tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer",
            expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 3600
        )
    }

    /**
     * Use refresh token to obtain new access token
     */
    suspend fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): OIDCTokens {
        val tokenUrl = "$autheliaUrl/api/oidc/token"

        val response = client.post(tokenUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(clientId, clientSecret)
            setBody(buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=${refreshToken.encodeURLParameter()}")
            })
        }

        require(response.status == HttpStatusCode.OK) {
            "Token refresh failed: ${response.status} - ${response.bodyAsText()}"
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        return OIDCTokens(
            accessToken = json["access_token"]?.jsonPrimitive?.content,
            idToken = json["id_token"]?.jsonPrimitive?.content,
            refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: refreshToken,
            tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer",
            expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 3600
        )
    }

    /**
     * Decode JWT ID token and extract claims (no signature verification - testing only)
     */
    fun decodeIdToken(idToken: String): Map<String, Any?> {
        // JWT format: header.payload.signature
        val parts = idToken.split(".")
        require(parts.size == 3) { "Invalid JWT format" }

        // Decode base64url payload
        val payload = parts[1]
        val decodedBytes = Base64.getUrlDecoder().decode(payload)
        val payloadJson = String(decodedBytes)

        val json = Json.parseToJsonElement(payloadJson).jsonObject
        return json.entries.associate { (key, value) ->
            key to when {
                value is JsonPrimitive && value.isString -> value.content
                value is JsonPrimitive && !value.isString -> value.toString()
                else -> value.toString()
            }
        }
    }

    /**
     * Validate token by making userinfo request
     */
    suspend fun validateToken(accessToken: String): Boolean {
        val userInfoUrl = "$autheliaUrl/api/oidc/userinfo"

        return try {
            val response = client.get(userInfoUrl) {
                bearerAuth(accessToken)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get OIDC discovery document
     */
    suspend fun getDiscoveryDocument(): Map<String, Any?> {
        val discoveryUrl = "$autheliaUrl/.well-known/openid-configuration"
        val response = client.get(discoveryUrl)

        require(response.status == HttpStatusCode.OK) {
            "Discovery failed: ${response.status}"
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return json.entries.associate { (key, value) ->
            key to when {
                value is JsonPrimitive && value.isString -> value.content
                value is JsonPrimitive && !value.isString -> value.toString()
                value is JsonArray -> value.map { it.toString() }
                else -> value.toString()
            }
        }
    }

    /**
     * Create an expired token for testing (mock - just sets exp claim to past)
     * Note: This doesn't create a real signed token, just for testing expiry logic
     */
    fun createExpiredToken(username: String): String {
        // Create JWT with expired exp claim
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = """{
            "sub":"$username",
            "exp":${System.currentTimeMillis() / 1000 - 3600},
            "iss":"https://auth.example.com"
        }"""

        val encodedHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(header.toByteArray())
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray())

        // Fake signature (won't validate but good enough for testing expiry)
        return "$encodedHeader.$encodedPayload.fake_signature"
    }
}

@Serializable
data class OIDCTokens(
    val accessToken: String?,
    val idToken: String?,
    val refreshToken: String?,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 3600
)
