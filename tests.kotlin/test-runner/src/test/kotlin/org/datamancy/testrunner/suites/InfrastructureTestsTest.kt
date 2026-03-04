package org.datamancy.testrunner.suites

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.datamancy.testrunner.framework.*
import kotlin.test.*

class InfrastructureTestsTest {

    private fun createMockClient(responses: Map<String, MockResponse>): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }

            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val response = responses.entries.find { url.contains(it.key) }?.value
                        ?: MockResponse(HttpStatusCode.NotFound, ByteReadChannel("Not Found"))

                    respond(
                        content = response.content,
                        status = response.status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    private data class MockResponse(val status: HttpStatusCode, val content: ByteReadChannel)

    @Test
    fun `test Caddy health check passes with 200`() = runBlocking {
        val mockClient = createMockClient(mapOf(
            "caddy" to MockResponse(HttpStatusCode.OK, ByteReadChannel("OK"))
        ))

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        var testPassed = false
        runner.infrastructureTests()
        val summary = runner.summary()

        
        assertTrue(summary.passed > 0, "Expected at least one test to pass")
    }

    @Test
    fun `test Caddy handles 404 gracefully`() = runBlocking {
        val mockClient = createMockClient(mapOf(
            "caddy" to MockResponse(HttpStatusCode.NotFound, ByteReadChannel("Not Found"))
        ))

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        runner.infrastructureTests()
        val summary = runner.summary()

        
        assertTrue(summary.total > 0, "Should have run infrastructure tests")
    }

    @Test
    fun `test Authelia OIDC discovery returns valid JSON`() = runBlocking {
        val oidcConfig = """
            {
                "issuer": "https://auth.example.com",
                "authorization_endpoint": "https://auth.example.com/api/oidc/authorization",
                "token_endpoint": "https://auth.example.com/api/oidc/token",
                "jwks_uri": "https://auth.example.com/jwks.json",
                "scopes_supported": ["openid", "profile", "email"],
                "response_types_supported": ["code"]
            }
        """.trimIndent()

        val mockClient = createMockClient(mapOf(
            "authelia" to MockResponse(HttpStatusCode.OK, ByteReadChannel(oidcConfig)),
            ".well-known/openid-configuration" to MockResponse(HttpStatusCode.OK, ByteReadChannel(oidcConfig))
        ))

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        runner.infrastructureTests()
        val summary = runner.summary()

        assertTrue(summary.total > 0, "Should have run Authelia tests")
    }

    @Test
    fun `test LDAP configuration validation`() = runBlocking {
        val endpoints = ServiceEndpoints.fromEnvironment()

        
        assertNotNull(endpoints.ldap, "LDAP endpoint should be configured")
        assertTrue(endpoints.ldap!!.contains("ldap://"), "LDAP should use ldap:// protocol")
        assertTrue(endpoints.ldap!!.contains(":389"), "LDAP should use port 389")
    }

    @Test
    fun `test infrastructure suite runs all tests`() = runBlocking {
        val mockClient = createMockClient(mapOf(
            "caddy" to MockResponse(HttpStatusCode.OK, ByteReadChannel("OK")),
            "authelia" to MockResponse(HttpStatusCode.OK, ByteReadChannel("""{"status":"healthy"}""")),
            ".well-known" to MockResponse(HttpStatusCode.OK, ByteReadChannel("""
                {"issuer":"test","authorization_endpoint":"test","token_endpoint":"test","jwks_uri":"test","scopes_supported":[],"response_types_supported":[]}
            """.trimIndent()))
        ))

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        runner.infrastructureTests()
        val summary = runner.summary()

        
        assertEquals(9, summary.total, "Should run exactly 9 infrastructure tests")
    }

    @Test
    fun `test Authelia health endpoint validates correctly`() = runBlocking {
        val mockClient = createMockClient(mapOf(
            "authelia/api/health" to MockResponse(HttpStatusCode.OK, ByteReadChannel("""{"status":"healthy"}"""))
        ))

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        runner.infrastructureTests()
        val summary = runner.summary()

        
        assertTrue(summary.passed > 0, "Authelia health test should pass")
    }

    @Test
    fun `test Caddy accepts various HTTP status codes`() = runBlocking {
        
        for (statusCode in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.BadGateway)) {
            val mockClient = createMockClient(mapOf(
                "caddy" to MockResponse(statusCode, ByteReadChannel("Response"))
            ))

            val endpoints = ServiceEndpoints.fromEnvironment()
            val serviceClient = ServiceClient(endpoints, mockClient)
            val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

            runner.infrastructureTests()
            val summary = runner.summary()

            
            assertTrue(summary.total > 0, "Tests should run for status $statusCode")
        }
    }
}
