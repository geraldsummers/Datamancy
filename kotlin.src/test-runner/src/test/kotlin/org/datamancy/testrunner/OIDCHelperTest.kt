package org.datamancy.testrunner

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import org.datamancy.testrunner.framework.*
import java.util.Base64
import kotlin.test.*

class OIDCHelperTest {

    // =========================================================================
    // JWT Decoding Tests
    // =========================================================================

    @Test
    fun `decodeIdToken should extract claims from valid JWT`() {
        // Create a valid JWT with known claims
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = """{
            "sub":"user123",
            "iss":"https://auth.example.com",
            "aud":"my-client",
            "exp":${System.currentTimeMillis() / 1000 + 3600},
            "iat":${System.currentTimeMillis() / 1000},
            "email":"user@example.com",
            "preferred_username":"testuser"
        }"""

        val encodedHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(header.toByteArray())
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray())
        val jwt = "$encodedHeader.$encodedPayload.fake_signature"

        // Create mocks
        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        // Decode token
        val claims = oidcHelper.decodeIdToken(jwt)

        // Verify claims
        assertEquals("user123", claims["sub"])
        assertEquals("https://auth.example.com", claims["iss"])
        assertEquals("my-client", claims["aud"])
        assertEquals("user@example.com", claims["email"])
        assertEquals("testuser", claims["preferred_username"])
    }

    @Test
    fun `decodeIdToken should handle numeric claims`() {
        val payload = """{
            "sub":"user123",
            "exp":1234567890,
            "iat":1234567800,
            "nbf":1234567800
        }"""

        val encodedHeader = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256"}""".toByteArray())
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray())
        val jwt = "$encodedHeader.$encodedPayload.sig"

        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        val claims = oidcHelper.decodeIdToken(jwt)

        assertTrue(claims.containsKey("exp"))
        assertTrue(claims.containsKey("iat"))
        assertTrue(claims.containsKey("nbf"))
    }

    @Test
    fun `decodeIdToken should fail on malformed JWT`() {
        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        // Invalid JWT with only 2 parts
        assertFailsWith<IllegalArgumentException> {
            oidcHelper.decodeIdToken("header.payload")
        }

        // Invalid JWT with 4 parts
        assertFailsWith<IllegalArgumentException> {
            oidcHelper.decodeIdToken("header.payload.signature.extra")
        }
    }

    @Test
    fun `decodeIdToken should fail on invalid base64`() {
        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        // Invalid base64 in payload
        assertFails {
            oidcHelper.decodeIdToken("valid_header.invalid!!!base64.signature")
        }
    }

    // =========================================================================
    // Expired Token Creation Tests
    // =========================================================================

    @Test
    fun `createExpiredToken should create JWT with past expiry`() {
        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        val expiredToken = oidcHelper.createExpiredToken("testuser")

        // Verify it's a valid JWT structure
        val parts = expiredToken.split(".")
        assertEquals(3, parts.size)

        // Decode and verify expiry is in the past
        val claims = oidcHelper.decodeIdToken(expiredToken)
        assertEquals("testuser", claims["sub"])

        val exp = claims["exp"]?.toString()?.toLongOrNull()
        assertNotNull(exp)
        val currentTime = System.currentTimeMillis() / 1000
        assertTrue(exp < currentTime, "Token should be expired (exp: $exp, now: $currentTime)")
    }

    @Test
    fun `createExpiredToken should include required claims`() {
        val mockHttpClient = createMockHttpClient()
        val mockAuthHelper = createMockAuthHelper(mockHttpClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockHttpClient, mockAuthHelper)

        val expiredToken = oidcHelper.createExpiredToken("alice")
        val claims = oidcHelper.decodeIdToken(expiredToken)

        // Verify required claims
        assertTrue(claims.containsKey("sub"))
        assertTrue(claims.containsKey("exp"))
        assertTrue(claims.containsKey("iss"))
        assertEquals("alice", claims["sub"])
    }

    // =========================================================================
    // Discovery Document Tests
    // =========================================================================

    @Test
    fun `getDiscoveryDocument should parse OIDC discovery response`() = runTest {
        val discoveryJson = """{
            "issuer": "https://auth.example.com",
            "authorization_endpoint": "https://auth.example.com/api/oidc/authorization",
            "token_endpoint": "https://auth.example.com/api/oidc/token",
            "userinfo_endpoint": "https://auth.example.com/api/oidc/userinfo",
            "jwks_uri": "https://auth.example.com/api/oidc/jwks",
            "scopes_supported": ["openid", "profile", "email"],
            "response_types_supported": ["code", "id_token", "token"],
            "grant_types_supported": ["authorization_code", "refresh_token"]
        }"""

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel(discoveryJson),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val discovery = oidcHelper.getDiscoveryDocument()

        // Verify key endpoints
        assertEquals("https://auth.example.com", discovery["issuer"])
        assertEquals("https://auth.example.com/api/oidc/authorization", discovery["authorization_endpoint"])
        assertEquals("https://auth.example.com/api/oidc/token", discovery["token_endpoint"])
        assertEquals("https://auth.example.com/api/oidc/userinfo", discovery["userinfo_endpoint"])
        assertEquals("https://auth.example.com/api/oidc/jwks", discovery["jwks_uri"])
    }

    @Test
    fun `getDiscoveryDocument should handle arrays in response`() = runTest {
        val discoveryJson = """{
            "issuer": "https://auth.example.com",
            "scopes_supported": ["openid", "profile", "email", "groups"],
            "response_types_supported": ["code"]
        }"""

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel(discoveryJson),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val discovery = oidcHelper.getDiscoveryDocument()

        assertTrue(discovery.containsKey("scopes_supported"))
        assertTrue(discovery.containsKey("response_types_supported"))
    }

    @Test
    fun `getDiscoveryDocument should fail on non-200 response`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("""{"error":"not found"}"""),
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        assertFailsWith<IllegalArgumentException> {
            oidcHelper.getDiscoveryDocument()
        }
    }

    // =========================================================================
    // Token Validation Tests
    // =========================================================================

    @Test
    fun `validateToken should return true for valid token`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val hasAuthHeader = request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true

                    respond(
                        content = ByteReadChannel(if (hasAuthHeader) """{"sub":"user123"}""" else """{"error":"unauthorized"}"""),
                        status = if (hasAuthHeader) HttpStatusCode.OK else HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val isValid = oidcHelper.validateToken("valid-access-token")
        assertTrue(isValid)
    }

    @Test
    fun `validateToken should return false for invalid token`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("""{"error":"invalid token"}"""),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val isValid = oidcHelper.validateToken("invalid-token")
        assertFalse(isValid)
    }

    @Test
    fun `validateToken should return false on network error`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    throw Exception("Network error")
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val isValid = oidcHelper.validateToken("any-token")
        assertFalse(isValid)
    }

    // =========================================================================
    // Token Exchange Tests
    // =========================================================================

    @Test
    fun `exchangeCodeForTokens should parse token response`() = runTest {
        val tokenResponse = """{
            "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
            "token_type": "Bearer",
            "expires_in": 3600,
            "refresh_token": "refresh_token_value",
            "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
        }"""

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    // Verify it's a POST to token endpoint
                    if (request.method == HttpMethod.Post && request.url.toString().contains("/token")) {
                        respond(
                            content = ByteReadChannel(tokenResponse),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("""{"error":"invalid request"}"""),
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                        )
                    }
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val tokens = oidcHelper.exchangeCodeForTokens(
            clientId = "test-client",
            clientSecret = "test-secret",
            code = "auth-code",
            redirectUri = "https://app.example.com/callback"
        )

        assertNotNull(tokens.accessToken)
        assertNotNull(tokens.idToken)
        assertNotNull(tokens.refreshToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(3600, tokens.expiresIn)
    }

    @Test
    fun `exchangeCodeForTokens should fail on error response`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("""{"error":"invalid_grant","error_description":"Code expired"}"""),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        assertFailsWith<IllegalArgumentException> {
            oidcHelper.exchangeCodeForTokens(
                clientId = "test-client",
                clientSecret = "test-secret",
                code = "expired-code",
                redirectUri = "https://app.example.com/callback"
            )
        }
    }

    // =========================================================================
    // Refresh Token Tests
    // =========================================================================

    @Test
    fun `refreshAccessToken should get new tokens`() = runTest {
        val refreshResponse = """{
            "access_token": "new_access_token",
            "token_type": "Bearer",
            "expires_in": 3600
        }"""

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel(refreshResponse),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val tokens = oidcHelper.refreshAccessToken(
            clientId = "test-client",
            clientSecret = "test-secret",
            refreshToken = "old_refresh_token"
        )

        assertEquals("new_access_token", tokens.accessToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(3600, tokens.expiresIn)
    }

    @Test
    fun `refreshAccessToken should preserve refresh token if not in response`() = runTest {
        val refreshResponse = """{
            "access_token": "new_access_token",
            "token_type": "Bearer",
            "expires_in": 3600
        }"""

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel(refreshResponse),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }

        val mockAuthHelper = createMockAuthHelper(mockClient)
        val oidcHelper = OIDCHelper("http://authelia:9091", mockClient, mockAuthHelper)

        val originalRefreshToken = "original_refresh_token"
        val tokens = oidcHelper.refreshAccessToken(
            clientId = "test-client",
            clientSecret = "test-secret",
            refreshToken = originalRefreshToken
        )

        // Should preserve original refresh token
        assertEquals(originalRefreshToken, tokens.refreshToken)
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun createMockHttpClient(): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("""{"status":"OK"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                    )
                }
            }
            install(ContentNegotiation) { json() }
        }
    }

    private fun createMockAuthHelper(mockClient: HttpClient): AuthHelper {
        return AuthHelper("http://authelia:9091", mockClient, null)
    }
}
