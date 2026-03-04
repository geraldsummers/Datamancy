package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*

/**
 * OAuth 2.0 / OpenID Connect flow helper for testing token-based authentication.
 *
 * OIDCHelper validates that Authelia correctly implements the OIDC standard for
 * services that integrate via OAuth instead of session-based SSO. This is critical
 * for services like Open-WebUI, Grafana, Mastodon, Forgejo, and BookStack that
 * use OIDC as their authentication mechanism.
 *
 * ## Authentication Cascade: OIDC Layer
 * OIDC sits atop the Authelia session layer:
 * 1. **LDAP → Authelia Session**: User authenticates with username/password
 * 2. **Authelia Session → Authorization Code**: User authorizes OIDC client
 * 3. **Authorization Code → Tokens**: Client exchanges code for access/ID/refresh tokens
 * 4. **Tokens → Service Access**: Client uses tokens to access protected resources
 *
 * ## Why OIDC Integration Matters
 * - **Standardized Authentication**: Services don't need custom Authelia integration
 * - **Token-Based Access**: Stateless authentication without session cookies
 * - **User Info Claims**: Services extract user details (email, groups) from ID token
 * - **SSO Experience**: Users already logged into Authelia skip re-authentication
 *
 * ## Cross-Service Testing
 * Tests validate:
 * - Authorization code flow works end-to-end
 * - Different OIDC clients (Open-WebUI, Grafana, etc.) can obtain tokens
 * - Token refresh works without re-authentication
 * - ID token contains correct user claims
 * - Expired tokens are rejected properly
 *
 * @property autheliaUrl Authelia endpoint for OIDC operations
 * @property client Ktor HTTP client for making requests
 * @property auth AuthHelper for establishing Authelia sessions (required for OIDC flows)
 */
class OIDCHelper(
    private val autheliaUrl: String,
    private val client: HttpClient,
    private val auth: AuthHelper
) {
    /**
     * Performs the complete OAuth 2.0 authorization code flow in one operation.
     *
     * This orchestrates the full OIDC flow that services like Open-WebUI, Grafana, and
     * Mastodon use for user authentication:
     * 1. User authenticates with Authelia (establishes session)
     * 2. Authorization request redirects to Authelia with client_id and redirect_uri
     * 3. Authelia validates session and returns authorization code
     * 4. Client exchanges code for tokens (access_token, id_token, refresh_token)
     *
     * Tests use this to validate:
     * - The entire flow works end-to-end without manual browser interaction
     * - Different OIDC clients can authenticate users
     * - Tokens contain expected claims and can access protected resources
     *
     * @param clientId OIDC client identifier (e.g., "open-webui", "grafana")
     * @param clientSecret OIDC client secret for token exchange
     * @param redirectUri OAuth redirect URI (must match registered client config)
     * @param scope OAuth scopes requested (default: "openid profile email")
     * @param user TestUser with credentials for authentication
     * @return OIDCTokens containing access_token, id_token, and refresh_token
     */
    suspend fun performFullFlow(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        scope: String = "openid profile email",
        user: TestUser
    ): OIDCTokens {
        
        val authResult = auth.login(user.username, user.password)
        require(authResult is AuthResult.Success) { "Login failed for OIDC flow" }

        
        val authCode = getAuthorizationCode(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope
        )

        
        return exchangeCodeForTokens(
            clientId = clientId,
            clientSecret = clientSecret,
            code = authCode,
            redirectUri = redirectUri
        )
    }

    
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

        
        val location = response.headers["Location"]
            ?: throw IllegalStateException("No redirect from authorization endpoint")

        
        val codeParam = location.substringAfter("code=").substringBefore("&")
        require(codeParam.isNotBlank()) { "No authorization code in redirect: $location" }

        return codeParam
    }

    
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

    
    fun decodeIdToken(idToken: String): Map<String, Any?> {
        
        val parts = idToken.split(".")
        require(parts.size == 3) { "Invalid JWT format" }

        
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

    
    fun createExpiredToken(username: String): String {
        
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
