package org.datamancy.controlpanel.services

import org.datamancy.controlpanel.models.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseServiceTest {

    private lateinit var db: DatabaseService
    private val jdbcUrl = "jdbc:h2:mem:test_control_panel;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
    private val username = "sa"
    private val password = ""

    @Before
    fun setup() {
        db = DatabaseService(jdbcUrl, username, password)
        db.ensureSchema()
    }

    @After
    fun cleanup() {
        DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP ALL OBJECTS")
            }
        }
        db.close()
    }

    @Test
    fun `test ensureSchema creates tables`() {
        DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            val meta = conn.metaData
            val tables = mutableListOf<String>()
            meta.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").lowercase())
                }
            }
            assertTrue(tables.contains("control_panel_config"))
            assertTrue(tables.contains("source_status"))
            assertTrue(tables.contains("system_events"))
        }
    }

    @Test
    fun `test getSourceConfigs returns list`() {
        val configs = db.getSourceConfigs()
        assertNotNull(configs)
        assertTrue(configs.isNotEmpty())
        assertTrue(configs.any { it.name == "wiki" })
        assertTrue(configs.any { it.name == "rss" })
    }

    @Test
    fun `test updateSourceConfig enables and disables source`() {
        val update = SourceConfigUpdate(enabled = false, scheduleInterval = "12h")
        val result = db.updateSourceConfig("wiki", update)
        assertTrue(result)

        val configs = db.getSourceConfigs()
        val wikiConfig = configs.find { it.name == "wiki" }
        assertNotNull(wikiConfig)
        assertEquals(false, wikiConfig.enabled)
        assertEquals("12h", wikiConfig.scheduleInterval)
    }

    @Test
    fun `test updateSourceConfig with only enabled field`() {
        val update = SourceConfigUpdate(enabled = false)
        val result = db.updateSourceConfig("rss", update)
        assertTrue(result)

        val configs = db.getSourceConfigs()
        val rssConfig = configs.find { it.name == "rss" }
        assertNotNull(rssConfig)
        assertEquals(false, rssConfig.enabled)
    }

    @Test
    fun `test updateSourceConfig with only scheduleInterval field`() {
        val update = SourceConfigUpdate(scheduleInterval = "8h")
        val result = db.updateSourceConfig("market_data", update)
        assertTrue(result)

        val configs = db.getSourceConfigs()
        val marketConfig = configs.find { it.name == "market_data" }
        assertNotNull(marketConfig)
        assertEquals("8h", marketConfig.scheduleInterval)
    }

    @Test
    fun `test getStorageStats returns stats`() {
        val stats = db.getStorageStats()
        assertNotNull(stats)
        assertNotNull(stats.postgres)
        assertNotNull(stats.clickhouse)
        assertNotNull(stats.qdrant)
    }

    @Test
    fun `test logEvent and getRecentEvents`() {
        db.logEvent("test_event", "test_service", "Test message", mapOf("key" to "value"))
        db.logEvent("another_event", "another_service", "Another message")

        val events = db.getRecentEvents(limit = 10)
        assertNotNull(events)
        assertTrue(events.size >= 2)

        val testEvent = events.find { it.eventType == "test_event" }
        assertNotNull(testEvent)
        assertEquals("test_service", testEvent.serviceName)
        assertEquals("Test message", testEvent.message)
    }

    @Test
    fun `test getRecentEvents respects limit`() {
        for (i in 1..15) {
            db.logEvent("event_$i", "service", "Message $i")
        }

        val events = db.getRecentEvents(limit = 5)
        assertEquals(5, events.size)
    }

    @Test
    fun `test getRecentEvents returns most recent first`() {
        db.logEvent("old_event", "service", "Old message")
        Thread.sleep(10)
        db.logEvent("new_event", "service", "New message")

        val events = db.getRecentEvents(limit = 10)
        assertTrue(events.isNotEmpty())
        assertEquals("new_event", events.first().eventType)
    }

    @Test
    fun `test close does not throw exception`() {
        db.close()
    }

    @Test
    fun `test logEvent with empty metadata`() {
        db.logEvent("simple_event", "service", "Simple message", emptyMap())

        val events = db.getRecentEvents(limit = 1)
        assertNotNull(events)
        assertTrue(events.isNotEmpty())
        assertEquals("simple_event", events.first().eventType)
    }
}
