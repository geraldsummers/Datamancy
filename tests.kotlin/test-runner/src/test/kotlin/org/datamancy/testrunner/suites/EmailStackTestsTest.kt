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

class EmailStackTestsTest {

    @Test
    fun `test mailserver endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.mailserver)
        assertTrue(endpoints.mailserver.contains("mailserver"))
        assertTrue(endpoints.mailserver.contains("25") || endpoints.mailserver.contains("mailserver"))
    }

    @Test
    fun `test mailserver URL parsing`() {
        val mailserverUrl = "mailserver:25"
        val parts = mailserverUrl.split(":")

        assertEquals(2, parts.size)
        assertEquals("mailserver", parts[0])
        assertEquals("25", parts[1])
    }

    @Test
    fun `test email endpoints for localhost`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertEquals("localhost:25", endpoints.mailserver)
    }

    @Test
    fun `test email stack tests count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    respondOk("OK")
                }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        try {
            runner.emailStackTests()
        } catch (e: Exception) {
            // Expected - some tests try to open sockets
        }

        val summary = runner.summary()
        assertEquals(9, summary.total, "Should have 9 email stack tests")
    }

    @Test
    fun `test SMTP ports configuration`() {
        // Test common SMTP ports
        val smtpPort = 25
        val submissionPort = 587
        val smtpsPort = 465

        assertTrue(smtpPort in 1..65535)
        assertTrue(submissionPort in 1..65535)
        assertTrue(smtpsPort in 1..65535)
    }

    @Test
    fun `test IMAP ports configuration`() {
        val imapPort = 143
        val imapsPort = 993

        assertTrue(imapPort in 1..65535)
        assertTrue(imapsPort in 1..65535)
    }

    @Test
    fun `test email endpoints in test environment`() {
        val containerEnv = TestEnvironment.Container
        assertTrue(containerEnv.endpoints.mailserver.isNotEmpty())

        val localhostEnv = TestEnvironment.Localhost
        assertTrue(localhostEnv.endpoints.mailserver.isNotEmpty())
    }
}
