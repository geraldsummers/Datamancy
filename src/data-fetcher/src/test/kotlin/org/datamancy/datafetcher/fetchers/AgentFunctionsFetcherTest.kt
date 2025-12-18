package org.datamancy.datafetcher.fetchers

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentFunctionsFetcherTest {

    @Test
    fun `test CatalogDiff data class`() {
        val diff = CatalogDiff(
            added = setOf("tool1", "tool2"),
            removed = setOf("tool3"),
            modified = setOf("tool4")
        )

        assertEquals(2, diff.added.size)
        assertEquals(1, diff.removed.size)
        assertEquals(1, diff.modified.size)
        assertTrue(diff.added.contains("tool1"))
        assertTrue(diff.removed.contains("tool3"))
        assertTrue(diff.modified.contains("tool4"))
    }

    @Test
    fun `test CatalogDiff with empty sets`() {
        val diff = CatalogDiff(
            added = emptySet(),
            removed = emptySet(),
            modified = emptySet()
        )

        assertEquals(0, diff.added.size)
        assertEquals(0, diff.removed.size)
        assertEquals(0, diff.modified.size)
    }

    @Test
    fun `test AgentFunctionsFetcher instantiation`() {
        val fetcher = AgentFunctionsFetcher()
        assertNotNull(fetcher)
    }

    @Test
    fun `test dryRun returns checks`() = runTest {
        val fetcher = AgentFunctionsFetcher()
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())

        // Should have at least 3 checks: healthz, tools endpoint, directory
        assertTrue(result.checks.size >= 3)
    }

    @Test
    fun `test dryRun checks agent tool server`() = runTest {
        val fetcher = AgentFunctionsFetcher()
        val result = fetcher.dryRun()

        val agentServerCheck = result.checks.find { it.name.contains("Agent Tool Server") }
        assertNotNull(agentServerCheck)
    }

    @Test
    fun `test dryRun checks tools endpoint`() = runTest {
        val fetcher = AgentFunctionsFetcher()
        val result = fetcher.dryRun()

        val toolsCheck = result.checks.find { it.name.contains("tools endpoint") }
        assertNotNull(toolsCheck)
    }

    @Test
    fun `test dryRun checks directory`() = runTest {
        val fetcher = AgentFunctionsFetcher()
        val result = fetcher.dryRun()

        val dirCheck = result.checks.find { it.name.contains("directory") }
        assertNotNull(dirCheck)
    }
}
