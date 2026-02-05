package org.datamancy.testrunner

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import org.datamancy.testrunner.framework.*
import kotlin.test.*

class AuthHelperTest {

    private fun createMockClient(responseContent: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel(responseContent),
                        status = statusCode,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            HttpHeaders.SetCookie to listOf("authelia_session=test-session-token; Path=/; HttpOnly")
                        )
                    )
                }
            }
            install(ContentNegotiation) {
                json()
            }
        }
    }

    @Test
    fun `login should return success with valid credentials`() = runTest {
        val mockClient = createMockClient("""{"status":"OK"}""")
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        val result = authHelper.login("testuser", "testpassword123")

        assertTrue(result is AuthResult.Success)
        assertTrue(authHelper.isAuthenticated())
    }

    @Test
    fun `login should return error with invalid credentials`() = runTest {
        val mockClient = createMockClient(
            """{"message":"Invalid credentials"}""",
            HttpStatusCode.Unauthorized
        )
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        val result = authHelper.login("testuser", "wrongpass123")

        assertTrue(result is AuthResult.Error)
        assertFalse(authHelper.isAuthenticated())
    }

    @Test
    fun `login should reject weak passwords`() = runTest {
        val mockClient = createMockClient("""{"status":"OK"}""")
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        val result = authHelper.login("testuser", "password")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).message.contains("weak"))
    }

    @Test
    fun `login should reject short passwords`() = runTest {
        val mockClient = createMockClient("""{"status":"OK"}""")
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        val result = authHelper.login("testuser", "short")

        assertTrue(result is AuthResult.Error)
        assertTrue(result.message.contains("at least 8 characters"))
    }

    @Test
    fun `login should reject blank username`() = runTest {
        val mockClient = createMockClient("""{"status":"OK"}""")
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        val result = authHelper.login("", "validpass123")

        assertTrue(result is AuthResult.Error)
        assertTrue(result.message.contains("blank"))
    }

    @Test
    fun `logout should clear session`() = runTest {
        val mockClient = createMockClient("""{"status":"OK"}""")
        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)

        authHelper.login("testuser", "testpass123")
        assertTrue(authHelper.isAuthenticated())

        authHelper.logout()
        assertFalse(authHelper.isAuthenticated())
    }

    @Test
    fun `authenticatedGet should include session cookie`() = runTest {
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val hasCookie = request.headers[HttpHeaders.Cookie]?.contains("authelia_session") == true

                    
                    if (url.contains("/api/firstfactor")) {
                        respond(
                            content = ByteReadChannel("""{"status":"OK"}"""),
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.SetCookie to listOf("authelia_session=test-session-token; Path=/; HttpOnly")
                            )
                        )
                    }
                    
                    else {
                        respond(
                            content = ByteReadChannel(if (hasCookie) """{"authenticated":true}""" else """{"error":"no cookie"}"""),
                            status = if (hasCookie) HttpStatusCode.OK else HttpStatusCode.Unauthorized,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())
                            )
                        )
                    }
                }
            }
            install(ContentNegotiation) {
                json()
            }
        }

        val authHelper = AuthHelper("http://authelia:9091", mockClient, null)
        authHelper.login("testuser", "testpass123")

        val response = authHelper.authenticatedGet("http://test-service/api")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
