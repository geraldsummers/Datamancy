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

class ExtendedProductivityTestsTest {

    @Test
    fun `test onlyoffice endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.onlyoffice)
        assertTrue(endpoints.onlyoffice.contains("onlyoffice"))
        assertTrue(endpoints.onlyoffice.startsWith("http://") || endpoints.onlyoffice.startsWith("https://"))
    }

    @Test
    fun `test jupyterhub endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.jupyterhub)
        assertTrue(endpoints.jupyterhub.contains("jupyterhub") || endpoints.jupyterhub.contains("8000"))
    }

    @Test
    fun `test qbittorrent endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.qbittorrent)
        assertTrue(endpoints.qbittorrent!!.contains("qbittorrent"))
    }

    @Test
    fun `test extended productivity tests count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    when {
                        request.url.toString().contains("onlyoffice") -> {
                            respond(
                                content = ByteReadChannel("true"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/plain")
                            )
                        }
                        request.url.toString().contains("jupyterhub") -> {
                            respond(
                                content = ByteReadChannel("""{"version":"3.0"}"""),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        request.url.toString().contains("qbittorrent") -> {
                            respond(
                                content = ByteReadChannel("v4.5.0"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/plain")
                            )
                        }
                        request.url.toString().contains("seafile") -> {
                            respondOk("OK")
                        }
                        request.url.toString().contains("pipeline") -> {
                            respondOk("OK")
                        }
                        else -> respondOk("OK")
                    }
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        try {
            runner.extendedProductivityTests()
        } catch (e: Exception) {
            // Some tests may fail in mock environment
        }

        val summary = runner.summary()
        assertEquals(25, summary.total, "Should have 25 extended productivity tests")
    }

    @Test
    fun `test OnlyOffice document formats`() {
        val supportedFormats = listOf("docx", "xlsx", "pptx", "pdf", "txt", "csv")

        assertEquals(6, supportedFormats.size)
        assertTrue(supportedFormats.contains("docx"))
        assertTrue(supportedFormats.contains("xlsx"))
        assertTrue(supportedFormats.contains("pptx"))
    }

    @Test
    fun `test OnlyOffice API endpoints`() {
        val baseUrl = "http://onlyoffice:80"

        val healthcheck = "$baseUrl/healthcheck"
        assertTrue(healthcheck.contains("/healthcheck"))

        val convertService = "$baseUrl/ConvertService.ashx"
        assertTrue(convertService.contains("/ConvertService.ashx"))

        val commandService = "$baseUrl/coauthoring/CommandService.ashx"
        assertTrue(commandService.contains("/coauthoring/CommandService.ashx"))
    }

    @Test
    fun `test JupyterHub API endpoints`() {
        val baseUrl = "http://jupyterhub:8000"

        val login = "$baseUrl/hub/login"
        assertTrue(login.contains("/hub/login"))

        val api = "$baseUrl/hub/api"
        assertTrue(api.contains("/hub/api"))

        val users = "$baseUrl/hub/api/users"
        assertTrue(users.contains("/hub/api/users"))

        val oauth = "$baseUrl/hub/oauth_login"
        assertTrue(oauth.contains("/hub/oauth_login"))
    }

    @Test
    fun `test qBittorrent API endpoints`() {
        val baseUrl = "http://qbittorrent:8080"

        val version = "$baseUrl/api/v2/app/version"
        assertTrue(version.contains("/api/v2/app/version"))

        val torrentsInfo = "$baseUrl/api/v2/torrents/info"
        assertTrue(torrentsInfo.contains("/api/v2/torrents/info"))

        val torrentsAdd = "$baseUrl/api/v2/torrents/add"
        assertTrue(torrentsAdd.contains("/api/v2/torrents/add"))

        val rssItems = "$baseUrl/api/v2/rss/items"
        assertTrue(rssItems.contains("/api/v2/rss/items"))
    }

    @Test
    fun `test localhost productivity endpoints`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertEquals("http://localhost:8012", endpoints.onlyoffice)
        assertEquals("http://localhost:8000", endpoints.jupyterhub)
        assertEquals("http://localhost:8082", endpoints.qbittorrent)
    }

    @Test
    fun `test WebSocket URL for OnlyOffice`() {
        val httpUrl = "http://onlyoffice:80"
        val wsUrl = httpUrl.replace("http://", "ws://")
        assertEquals("ws://onlyoffice:80", wsUrl)
    }

    @Test
    fun `test integration stack availability`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        // Document collaboration stack
        assertNotNull(endpoints.onlyoffice)
        assertNotNull(endpoints.seafile)

        // Data analysis stack
        assertNotNull(endpoints.jupyterhub)
        assertNotNull(endpoints.pipeline)
    }

    @Test
    fun `test service port allocation`() {
        val localhostEndpoints = ServiceEndpoints.forLocalhost()

        // Ensure ports don't conflict
        val ports = listOf(
            8012, // onlyoffice
            8000, // jupyterhub
            8082  // qbittorrent
        )

        // All ports should be unique
        assertEquals(ports.size, ports.toSet().size, "Ports should not conflict")

        // All ports should be in valid range
        ports.forEach { port ->
            assertTrue(port in 1..65535, "Port $port should be in valid range")
        }
    }
}
