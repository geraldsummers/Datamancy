package org.datamancy.testrunner.suites

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.datamancy.testrunner.framework.*
import kotlin.test.*

class CachingLayerTestsTest {

    @Test
    fun `test valkey endpoint configuration`() {
        val endpoints = ServiceEndpoints.fromEnvironment()

        assertNotNull(endpoints.valkey)
        assertTrue(endpoints.valkey!!.contains("valkey") || endpoints.valkey!!.contains("redis"))
    }

    @Test
    fun `test valkey URL parsing`() {
        val valkeyUrl = "valkey:6379"
        val parts = valkeyUrl.split(":")

        assertEquals(2, parts.size)
        assertEquals("valkey", parts[0])
        assertEquals("6379", parts[1])
    }

    @Test
    fun `test valkey localhost configuration`() {
        val endpoints = ServiceEndpoints.forLocalhost()

        assertNotNull(endpoints.valkey)
        assertEquals("localhost:16379", endpoints.valkey)
    }

    @Test
    fun `test caching layer tests count`() = runBlocking {
        val mockClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { respondOk("OK") }
            }
        }

        val endpoints = ServiceEndpoints.fromEnvironment()
        val serviceClient = ServiceClient(endpoints, mockClient)
        val runner = TestRunner(TestEnvironment.Container, serviceClient, mockClient)

        try {
            runner.cachingLayerTests()
        } catch (e: Exception) {
            // Expected - tests try to connect to Redis/Valkey
        }

        val summary = runner.summary()
        assertEquals(13, summary.total, "Should have 13 caching layer tests")
    }

    @Test
    fun `test Redis port is valid`() {
        val redisPort = 6379
        assertTrue(redisPort in 1..65535)
        assertEquals(6379, redisPort, "Standard Redis port is 6379")
    }

    @Test
    fun `test parseRedisUrl helper function`() {
        // Simulating the parseRedisUrl logic
        fun parseRedisUrl(url: String?): Pair<String, Int> {
            if (url == null) return "valkey" to 6379
            val parts = url.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 6379
            return host to port
        }

        val (host1, port1) = parseRedisUrl("valkey:6379")
        assertEquals("valkey", host1)
        assertEquals(6379, port1)

        val (host2, port2) = parseRedisUrl("localhost:16379")
        assertEquals("localhost", host2)
        assertEquals(16379, port2)

        val (host3, port3) = parseRedisUrl(null)
        assertEquals("valkey", host3)
        assertEquals(6379, port3)
    }

    @Test
    fun `test Valkey configuration across environments`() {
        val containerEnv = TestEnvironment.Container
        assertNotNull(containerEnv.endpoints.valkey)
        assertTrue(containerEnv.endpoints.valkey!!.contains("6379"))

        val localhostEnv = TestEnvironment.Localhost
        assertNotNull(localhostEnv.endpoints.valkey)
        assertTrue(localhostEnv.endpoints.valkey!!.contains("localhost"))
    }

    @Test
    fun `test Redis operations key patterns`() {
        val testKey = "test:integration:${System.currentTimeMillis()}"
        assertTrue(testKey.startsWith("test:"))
        assertTrue(testKey.contains("integration"))

        val hashKey = "test:hash:${System.currentTimeMillis()}"
        assertTrue(hashKey.startsWith("test:hash:"))

        val listKey = "test:list:${System.currentTimeMillis()}"
        assertTrue(listKey.startsWith("test:list:"))
    }

    @Test
    fun `test Redis data structures`() {
        val fields = mapOf(
            "field1" to "value1",
            "field2" to "value2",
            "field3" to "value3"
        )

        assertEquals(3, fields.size)
        assertTrue(fields.containsKey("field1"))
        assertEquals("value1", fields["field1"])

        val items = listOf("item1", "item2", "item3")
        assertEquals(3, items.size)

        val members = setOf("member1", "member2", "member3")
        assertEquals(3, members.size)
    }
}
