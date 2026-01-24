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

class UserInterfaceTestsTest {

    @Test
    fun `test OpenWebUI endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()
        assertEquals("http://open-webui:8080", endpoints.openWebUI)

        val localhostEndpoints = ServiceEndpoints.forLocalhost()
        assertEquals("http://localhost:8080", localhostEndpoints.openWebUI)
    }

    @Test
    fun `test JupyterHub endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()
        assertEquals("http://jupyterhub:8000", endpoints.jupyterhub)

        val localhostEndpoints = ServiceEndpoints.forLocalhost()
        assertEquals("http://localhost:8000", localhostEndpoints.jupyterhub)
    }

    @Test
    fun `test OpenWebUI health check response`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    when {
                        request.url.toString().contains("/health") ->
                            respond(
                                content = ByteReadChannel("true"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/plain")
                            )
                        else -> respondOk("<!DOCTYPE html><html></html>")
                    }
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.userInterfaceTests()
        val summary = runner.summary()

        assertTrue(summary.total > 0, "Should run user interface tests")
    }

    @Test
    fun `test UI test suite count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            engine {
                addHandler { respondOk("OK") }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.userInterfaceTests()
        val summary = runner.summary()

        // Should run 5 tests (3 OpenWebUI + 2 JupyterHub)
        assertEquals(5, summary.total, "Should have 5 user interface tests")
    }

    @Test
    fun `test HTML response validation`() = runBlocking {
        val htmlResponse = "<!DOCTYPE html><html><head><title>Test</title></head><body></body></html>"

        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(htmlResponse),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html")
                    )
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient)

        runner.userInterfaceTests()
        val summary = runner.summary()

        assertTrue(summary.passed > 0, "HTML validation tests should pass")
    }
}
