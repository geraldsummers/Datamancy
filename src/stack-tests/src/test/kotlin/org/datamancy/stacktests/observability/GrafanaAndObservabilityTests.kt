package org.datamancy.stacktests.observability

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for Grafana and observability stack.
 *
 * Tests cover:
 * - Grafana health and API access
 * - Data source configuration
 * - Dashboard management
 * - User authentication
 * - API key management
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GrafanaAndObservabilityTests : BaseStackTest() {

    private val grafanaUrl = localhostPorts.httpUrl(localhostPorts.grafana)
    private val adminUser = getConfig("STACK_ADMIN_USER", "sysadmin")
    private val adminPassword = getConfig("STACK_ADMIN_PASSWORD", "admin")

    @Test
    @Order(1)
    fun `Grafana is accessible and healthy`() = runBlocking {
        val response = client.get("$grafanaUrl/api/health")

        assertEquals(HttpStatusCode.OK, response.status,
            "Grafana health endpoint should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val database = json["database"]?.jsonPrimitive?.content

        assertEquals("ok", database, "Database should be healthy")
        println("✓ Grafana is healthy")
    }

    @Test
    @Order(2)
    fun `can authenticate with Grafana admin credentials`() = runBlocking {
        val response = client.get("$grafanaUrl/api/org") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Authentication should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val orgName = json["name"]?.jsonPrimitive?.content

        assertNotNull(orgName, "Should have organization name")
        println("✓ Authenticated to organization: $orgName")
    }

    @Test
    @Order(3)
    fun `can list Grafana data sources`() = runBlocking {
        val response = client.get("$grafanaUrl/api/datasources") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Data sources list should succeed")

        val dataSources = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        println("✓ Grafana has ${dataSources.size} data source(s) configured")

        if (dataSources.size > 0) {
            dataSources.forEach { ds ->
                val name = ds.jsonObject["name"]?.jsonPrimitive?.content
                val type = ds.jsonObject["type"]?.jsonPrimitive?.content
                println("  - $name ($type)")
            }
        }
    }

    @Test
    @Order(4)
    fun `can check ClickHouse data source connectivity`() = runBlocking {
        // First, find ClickHouse data source
        val listResponse = client.get("$grafanaUrl/api/datasources") {
            basicAuth(adminUser, adminPassword)
        }

        val dataSources = Json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
        val clickhouseDs = dataSources.firstOrNull {
            val type = it.jsonObject["type"]?.jsonPrimitive?.content
            type?.contains("clickhouse", ignoreCase = true) == true
        }

        if (clickhouseDs != null) {
            val dsId = clickhouseDs.jsonObject["uid"]?.jsonPrimitive?.content
            assertNotNull(dsId, "ClickHouse data source should have UID")

            // Test data source connection
            val testResponse = client.get("$grafanaUrl/api/datasources/uid/$dsId/health") {
                basicAuth(adminUser, adminPassword)
            }

            if (testResponse.status == HttpStatusCode.OK) {
                println("✓ ClickHouse data source is connected")
            } else {
                println("⚠️  ClickHouse data source test returned ${testResponse.status}")
            }
        } else {
            println("⚠️  No ClickHouse data source configured yet")
        }
    }

    @Test
    @Order(5)
    fun `can list Grafana dashboards`() = runBlocking {
        val response = client.get("$grafanaUrl/api/search?type=dash-db") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Dashboard search should succeed")

        val dashboards = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        println("✓ Found ${dashboards.size} dashboard(s)")

        if (dashboards.size > 0) {
            dashboards.take(5).forEach { dash ->
                val title = dash.jsonObject["title"]?.jsonPrimitive?.content
                println("  - $title")
            }
        }
    }

    @Test
    @Order(6)
    fun `can retrieve Grafana user info`() = runBlocking {
        val response = client.get("$grafanaUrl/api/user") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "User info should be retrievable")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val login = json["login"]?.jsonPrimitive?.content
        val email = json["email"]?.jsonPrimitive?.content
        val isAdmin = json["isGrafanaAdmin"]?.jsonPrimitive?.boolean

        assertEquals(adminUser, login, "Login should match")
        assertTrue(isAdmin == true, "User should be admin")

        println("✓ User info: $login ($email), Admin: $isAdmin")
    }

    @Test
    @Order(7)
    fun `can list Grafana organizations`() = runBlocking {
        val response = client.get("$grafanaUrl/api/orgs") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Organizations list should succeed")

        val orgs = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        assertTrue(orgs.size > 0, "Should have at least one organization")
        println("✓ Grafana has ${orgs.size} organization(s)")
    }

    @Test
    @Order(8)
    fun `can retrieve Grafana settings`() = runBlocking {
        val response = client.get("$grafanaUrl/api/frontend/settings") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Settings should be retrievable")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertTrue(json.containsKey("defaultDatasource"), "Should have default datasource setting")
        println("✓ Grafana settings retrieved")
    }

    @Test
    @Order(9)
    fun `can check Grafana plugins`() = runBlocking {
        val response = client.get("$grafanaUrl/api/plugins") {
            basicAuth(adminUser, adminPassword)
        }

        assertEquals(HttpStatusCode.OK, response.status,
            "Plugins list should succeed")

        val plugins = Json.parseToJsonElement(response.bodyAsText()).jsonArray

        println("✓ Grafana has ${plugins.size} plugin(s) installed")

        // Check for important plugins
        val pluginIds = plugins.map {
            it.jsonObject["id"]?.jsonPrimitive?.content
        }

        val hasClickHouse = pluginIds.any { it?.contains("clickhouse") == true }
        println("  ClickHouse plugin: ${if (hasClickHouse) "✓ Installed" else "Not found"}")
    }

    @Test
    @Order(10)
    fun `can retrieve Grafana stats`() = runBlocking {
        val response = client.get("$grafanaUrl/api/admin/stats") {
            basicAuth(adminUser, adminPassword)
        }

        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            val dashboards = json["dashboards"]?.jsonPrimitive?.int
            val users = json["users"]?.jsonPrimitive?.int
            val orgs = json["orgs"]?.jsonPrimitive?.int

            println("✓ Grafana stats:")
            println("  Dashboards: $dashboards")
            println("  Users: $users")
            println("  Organizations: $orgs")
        } else {
            println("⚠️  Stats endpoint returned ${response.status}")
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║      Grafana & Observability Tests                ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
