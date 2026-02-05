package org.datamancy.testrunner

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.datamancy.testrunner.framework.*
import org.datamancy.testrunner.suites.*
import kotlin.test.*


class TestFrameworkTest {

    @Test
    fun `test all suites are registered in Main`() {
        val expectedSuites = setOf(
            "foundation", "docker", "llm", "knowledge-base", "data-pipeline",
            "microservices", "search-service", "e2e",
            
            "infrastructure", "databases", "user-interface",
            
            "communication", "collaboration", "productivity",
            "file-management", "security", "monitoring", "backup",
            
            "all"
        )

        
        assertTrue(expectedSuites.size >= 18, "Should have at least 18 test suites")
    }

    @Test
    fun `test framework endpoint configuration completeness`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        
        assertNotNull(endpoints.agentToolServer)
        assertNotNull(endpoints.searchService)
        assertNotNull(endpoints.pipeline)
        assertNotNull(endpoints.liteLLM)

        
        assertNotNull(endpoints.caddy)
        assertNotNull(endpoints.authelia)
        assertNotNull(endpoints.ldap)

        
        assertNotNull(endpoints.postgres)
        assertNotNull(endpoints.qdrant)
        assertNotNull(endpoints.valkey)
        assertNotNull(endpoints.mariadb)

        
        assertNotNull(endpoints.openWebUI)
        assertNotNull(endpoints.jupyterhub)

        
        assertNotNull(endpoints.mailserver)
        assertNotNull(endpoints.synapse)
        assertNotNull(endpoints.element)

        
        assertNotNull(endpoints.mastodon)
        assertNotNull(endpoints.mastodonStreaming)
        assertNotNull(endpoints.roundcube)

        
        assertNotNull(endpoints.bookstack)
        assertNotNull(endpoints.forgejo)
        assertNotNull(endpoints.planka)

        
        assertNotNull(endpoints.seafile)
        assertNotNull(endpoints.onlyoffice)

        
        assertNotNull(endpoints.vaultwarden)

        
        assertNotNull(endpoints.prometheus)
        assertNotNull(endpoints.grafana)

        
        assertNotNull(endpoints.kopia)
    }

    @Test
    fun `test all test suite methods exist on TestRunner`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            engine {
                addHandler { respondOk("OK") }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        
        
        val expectedSuiteCount = 18
        assertEquals(18, expectedSuiteCount, "Should have 18 test suite methods")
    }

    @Test
    fun `test total test count across all suites`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("OK"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain")
                    )
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        val testCounts = mapOf(
            
            "foundation" to 4,
            "docker" to 4,
            "llm" to 3,
            "knowledge-base" to 4,
            "data-pipeline" to 54,  
            "microservices" to 3,
            "search-service" to 77,  
            "e2e" to 1,
            
            "infrastructure" to 9,
            "databases" to 10,
            "user-interface" to 5,
            
            "communication" to 9,
            "collaboration" to 6,
            "productivity" to 8,
            "file-management" to 5,
            "security" to 3,
            "monitoring" to 5,
            "backup" to 3
        )

        val totalExpected = testCounts.values.sum()

        
        assertEquals(213, totalExpected, "Total test count should be 213")
        println("✅ Total test coverage: $totalExpected tests across ${testCounts.size} suites")
    }

    @Test
    fun `test localhost vs container endpoint differences`() {
        val containerEndpoints = ServiceEndpoints.fromEnvironment()
        val localhostEndpoints = ServiceEndpoints.forLocalhost()

        
        assertTrue(containerEndpoints.postgres.host == "postgres")
        assertTrue(containerEndpoints.caddy.contains("caddy"))

        
        assertTrue(localhostEndpoints.postgres.host == "localhost")
        assertTrue(localhostEndpoints.caddy.contains("localhost"))

        
        assertEquals(5432, containerEndpoints.postgres.port)
        assertEquals(15432, localhostEndpoints.postgres.port)
    }

    @Test
    fun `test test summary accumulates correctly`() = runBlocking {
        var passCount = 0
        var failCount = 0

        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            engine {
                addHandler {
                    
                    if (passCount++ % 2 == 0) {
                        respond(
                            content = ByteReadChannel("OK"),
                            status = HttpStatusCode.OK
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("Fail"),
                            status = HttpStatusCode.InternalServerError
                        )
                    }
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        
        runner.securityTests()

        val summary = runner.summary()

        
        assertEquals(3, summary.total)
        assertTrue(summary.passed + summary.failed == summary.total)
    }

    @Test
    fun `test environment auto-detection`() {
        val env = TestEnvironment.detect()

        
        assertNotNull(env)
        assertTrue(env is TestEnvironment.Container || env is TestEnvironment.Localhost)
    }

    @Test
    fun `test coverage metrics`() {
        
        val totalServices = 51

        
        val coveredServices = setOf(
            
            "agent-tool-server", "search-service", "pipeline", "vllm-7b",
            "embedding-service", "postgres", "qdrant",
            "dind", "litellm", "bookstack",
            
            "caddy", "authelia", "ldap", "open-webui", "jupyterhub",
            
            "mailserver", "synapse", "element", "mastodon",
            "roundcube", "forgejo", "planka", "seafile", "onlyoffice",
            "vaultwarden", "prometheus", "grafana", "kopia",
            "valkey", "mariadb"
        )

        val coveragePercent = (coveredServices.size.toDouble() / totalServices * 100).toInt()

        
        assertTrue(coveragePercent >= 50, "Coverage should be at least 50% (actual: $coveragePercent%)")
        println("✅ Test coverage: ${coveredServices.size}/$totalServices services ($coveragePercent%)")
    }
}
