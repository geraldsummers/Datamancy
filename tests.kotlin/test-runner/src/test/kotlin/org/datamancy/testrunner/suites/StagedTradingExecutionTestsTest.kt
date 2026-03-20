package org.datamancy.testrunner.suites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.datamancy.testrunner.framework.TestRunner
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StagedTradingExecutionTestsTest {

    @Test
    fun `staged trading fixtures are available on classpath`() {
        val fixturePaths = listOf(
            "fixtures/trading-staged/paper_full_fill.json",
            "fixtures/trading-staged/paper_partial_fill.json",
            "fixtures/trading-staged/worker_degraded.json"
        )

        fixturePaths.forEach { path ->
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            assertNotNull(stream, "Fixture missing on classpath: $path")

            val json = stream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(json).jsonObject
            assertTrue(root.containsKey("name"), "Fixture '$path' missing 'name'")
            assertTrue(root.containsKey("sampleResponse"), "Fixture '$path' missing 'sampleResponse'")
        }
    }

    @Test
    fun `staged trading suite compiles and is callable`() {
        val testFunction: suspend TestRunner.() -> Unit = { stagedTradingExecutionTests() }
        assertNotNull(testFunction)
    }
}
