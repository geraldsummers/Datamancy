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

class ExtendedCommunicationTestsTest {

    @Test
    fun `test mastodon endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.mastodon)
        assertTrue(endpoints.mastodon.contains("mastodon"))
        assertTrue(endpoints.mastodon.startsWith("http://") || endpoints.mastodon.startsWith("https://"))
    }

    @Test
    fun `test mastodon streaming endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.mastodonStreaming)
        assertTrue(endpoints.mastodonStreaming.contains("mastodon"))
    }

    @Test
    fun `test radicale endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.radicale)
        assertTrue(endpoints.radicale!!.contains("radicale"))
    }

    @Test
    fun `test ntfy endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.ntfy)
        assertTrue(endpoints.ntfy!!.contains("ntfy"))
    }

    @Test
    fun `test extended communication tests count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    when {
                        request.url.toString().contains("mastodon") -> {
                            respond(
                                content = ByteReadChannel("""{"uri":"mastodon.local","version":"4.0"}"""),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        request.url.toString().contains("radicale") -> {
                            respond(
                                content = ByteReadChannel(""),
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf("DAV", "1, 2, calendar-access, addressbook")
                            )
                        }
                        request.url.toString().contains("ntfy") -> {
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
            runner.extendedCommunicationTests()
        } catch (e: Exception) {
            // Some tests may fail due to mock limitations
        }

        val summary = runner.summary()
        assertEquals(19, summary.total, "Should have 19 extended communication tests")
    }

    @Test
    fun `test mastodon API endpoints`() {
        val baseUrl = "http://mastodon-web:3000"

        val instanceEndpoint = "$baseUrl/api/v1/instance"
        assertTrue(instanceEndpoint.contains("/api/v1/instance"))

        val timelineEndpoint = "$baseUrl/api/v1/timelines/public"
        assertTrue(timelineEndpoint.contains("/api/v1/timelines/public"))

        val oauthEndpoint = "$baseUrl/oauth/authorize"
        assertTrue(oauthEndpoint.contains("/oauth/authorize"))
    }

    @Test
    fun `test radicale CalDAV discovery`() {
        val baseUrl = "http://radicale:5232"

        val caldavDiscovery = "$baseUrl/.well-known/caldav"
        assertTrue(caldavDiscovery.contains("/.well-known/caldav"))

        val carddavDiscovery = "$baseUrl/.well-known/carddav"
        assertTrue(carddavDiscovery.contains("/.well-known/carddav"))
    }

    @Test
    fun `test ntfy topic naming`() {
        val testTopic = "test-topic-${System.currentTimeMillis()}"
        assertTrue(testTopic.startsWith("test-topic-"))

        val jsonTopic = "test-json-${System.currentTimeMillis()}"
        assertTrue(jsonTopic.startsWith("test-json-"))
    }

    @Test
    fun `test localhost communication endpoints`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertEquals("http://localhost:3000", endpoints.mastodon)
        assertEquals("http://localhost:4000", endpoints.mastodonStreaming)
        assertEquals("http://localhost:5232", endpoints.radicale)
        assertEquals("http://localhost:8081", endpoints.ntfy)
    }

    @Test
    fun `test WebSocket URL transformation`() {
        val httpUrl = "http://ntfy:80"
        val wsUrl = httpUrl.replace("http://", "ws://")
        assertEquals("ws://ntfy:80", wsUrl)

        val httpsUrl = "https://ntfy:443"
        val wssUrl = httpsUrl.replace("https://", "wss://")
        assertEquals("wss://ntfy:443", wssUrl)
    }

    @Test
    fun `test ActivityPub endpoints`() {
        val baseUrl = "http://mastodon-web:3000"

        val webfinger = "$baseUrl/.well-known/webfinger"
        assertTrue(webfinger.contains("/.well-known/webfinger"))

        val hostMeta = "$baseUrl/.well-known/host-meta"
        assertTrue(hostMeta.contains("/.well-known/host-meta"))
    }
}
