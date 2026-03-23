package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestRunnerCommandWiringTest {

    @Test
    fun `kt all keeps playwright separate from ts command`() {
        val text = Files.readString(
            findRepoRoot().resolve("tests.kotlin/test-runner/src/main/kotlin/org/datamancy/testrunner/Main.kt")
        )

        val allSuiteBlock = text.substringAfter("\"all\" -> {").substringBefore("        else -> {")

        assertFalse(
            allSuiteBlock.contains("runner.playwrightE2ETests()"),
            "`kt all` should not dispatch Playwright because `ts` owns the browser path"
        )
    }

    @Test
    fun `run tests usage makes playwright split explicit`() {
        val text = Files.readString(
            findRepoRoot().resolve("tests.containers/test-runner/run-tests.sh")
        )

        assertTrue(
            text.contains("kt all' excludes Playwright"),
            "run-tests usage should tell operators that `kt all` excludes Playwright coverage"
        )
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("smart-up.sh"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
