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

/**
 * Unit tests for all new test suites (MEDIUM priority)
 */
class AllTestSuitesTest {

    private fun createMockClient(defaultResponse: String = "OK"): HttpClient {
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
                    val response = when {
                        url.contains("/health") -> """{"status":"ok"}"""
                        url.contains("/api/v1/version") -> """{"version":"1.0.0"}"""
                        url.contains("/api/v1/instance") -> """{"uri":"test"}"""
                        url.contains("/api/v1/streaming/health") -> """{"status":"ok"}"""
                        url.contains("/_matrix/client/versions") -> """{"versions":["r0.6.1"]}"""
                        url.contains("/api2/ping") -> "pong"
                        url.contains("/healthcheck") -> "true"
                        url.contains("/alive") -> "OK"
                        url.contains("/-/healthy") -> "OK"
                        url.contains("/api/v1/query") -> """{"status":"success"}"""
                        url.contains("/api/v1/targets") -> """{"status":"success"}"""
                        else -> "<!DOCTYPE html><html></html>"
                    }

                    respond(
                        content = ByteReadChannel(response),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
    }

    // ============================================================================
    // Communication Tests
    // ============================================================================

    @Test
    fun `test communication suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertEquals("mailserver:25", endpoints.mailserver)
        assertEquals("http://synapse:8008", endpoints.synapse)
        assertEquals("http://element:80", endpoints.element)
    }

    @Test
    fun `test communication suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.communicationTests()
        val summary = runner.summary()

        // Should run 9 tests (4 Mailserver + 3 Synapse + 2 Element)
        assertEquals(9, summary.total)
    }

    // ============================================================================
    // Collaboration Tests
    // ============================================================================

    @Test
    fun `test collaboration suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertEquals("http://mastodon-web:3000", endpoints.mastodon)
        assertEquals("http://mastodon-streaming:4000", endpoints.mastodonStreaming)
        assertEquals("http://roundcube:80", endpoints.roundcube)
    }

    @Test
    fun `test collaboration suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.collaborationTests()
        val summary = runner.summary()

        // Should run 6 tests (4 Mastodon + 2 Roundcube)
        assertEquals(6, summary.total)
    }

    // ============================================================================
    // Productivity Tests
    // ============================================================================

    @Test
    fun `test productivity suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertEquals("http://bookstack:80", endpoints.bookstack)
        assertEquals("http://forgejo:3000", endpoints.forgejo)
        assertEquals("http://planka:1337", endpoints.planka)
    }

    @Test
    fun `test productivity suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.productivityTests()
        val summary = runner.summary()

        // Should run 8 tests (3 BookStack + 3 Forgejo + 2 Planka)
        assertEquals(8, summary.total)
    }

    // ============================================================================
    // File Management Tests
    // ============================================================================

    @Test
    fun `test file management suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertEquals("http://seafile:80", endpoints.seafile)
        assertEquals("http://onlyoffice:80", endpoints.onlyoffice)
    }

    @Test
    fun `test file management suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.fileManagementTests()
        val summary = runner.summary()

        // Should run 5 tests (3 Seafile + 2 OnlyOffice)
        assertEquals(5, summary.total)
    }

    // ============================================================================
    // Security Tests
    // ============================================================================

    @Test
    fun `test security suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()
        assertEquals("http://vaultwarden:80", endpoints.vaultwarden)
    }

    @Test
    fun `test security suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.securityTests()
        val summary = runner.summary()

        // Should run 3 tests (Vaultwarden)
        assertEquals(3, summary.total)
    }

    // ============================================================================
    // Monitoring Tests
    // ============================================================================

    @Test
    fun `test monitoring suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertEquals("http://prometheus:9090", endpoints.prometheus)
        assertEquals("http://grafana:3000", endpoints.grafana)
    }

    @Test
    fun `test monitoring suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.monitoringTests()
        val summary = runner.summary()

        // Should run 5 tests (3 Prometheus + 2 Grafana)
        assertEquals(5, summary.total)
    }

    // ============================================================================
    // Backup Tests
    // ============================================================================

    @Test
    fun `test backup suite endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()
        assertEquals("http://kopia:51515", endpoints.kopia)
    }

    @Test
    fun `test backup suite runs all tests`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.backupTests()
        val summary = runner.summary()

        // Should run 3 tests (Kopia)
        assertEquals(3, summary.total)
    }

    // ============================================================================
    // Integration Tests
    // ============================================================================

    @Test
    fun `test all suites have correct test counts`() = runBlocking {
        val mockClient = createMockClient()
        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)

        val testCounts = mapOf(
            "communication" to 9,
            "collaboration" to 6,
            "productivity" to 8,
            "file-management" to 5,
            "security" to 3,
            "monitoring" to 5,
            "backup" to 3
        )

        testCounts.forEach { (suite, expectedCount) ->
            val runner = TestRunner(TestEnvironment.Container, serviceClient)

            when (suite) {
                "communication" -> runner.communicationTests()
                "collaboration" -> runner.collaborationTests()
                "productivity" -> runner.productivityTests()
                "file-management" -> runner.fileManagementTests()
                "security" -> runner.securityTests()
                "monitoring" -> runner.monitoringTests()
                "backup" -> runner.backupTests()
            }

            val summary = runner.summary()
            assertEquals(expectedCount, summary.total, "Suite '$suite' should have $expectedCount tests")
        }
    }

    @Test
    fun `test localhost endpoints are correctly configured`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        // Infrastructure
        assertEquals("http://localhost:80", endpoints.caddy)
        assertEquals("http://localhost:9091", endpoints.authelia)

        // User Interfaces
        assertEquals("http://localhost:8080", endpoints.openWebUI)
        assertEquals("http://localhost:8000", endpoints.jupyterhub)

        // Communication
        assertEquals("localhost:25", endpoints.mailserver)
        assertEquals("http://localhost:8008", endpoints.synapse)
        assertEquals("http://localhost:8009", endpoints.element)

        // Collaboration
        assertEquals("http://localhost:3000", endpoints.mastodon)
        assertEquals("http://localhost:4000", endpoints.mastodonStreaming)
        assertEquals("http://localhost:8010", endpoints.roundcube)

        // Productivity
        assertEquals("http://localhost:3001", endpoints.forgejo)
        assertEquals("http://localhost:1337", endpoints.planka)

        // File Management
        assertEquals("http://localhost:8011", endpoints.seafile)
        assertEquals("http://localhost:8012", endpoints.onlyoffice)

        // Security
        assertEquals("http://localhost:8013", endpoints.vaultwarden)

        // Monitoring
        assertEquals("http://localhost:9090", endpoints.prometheus)
        assertEquals("http://localhost:3002", endpoints.grafana)

        // Backup
        assertEquals("http://localhost:51515", endpoints.kopia)
    }
}
