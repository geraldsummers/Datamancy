package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class RunTestsScriptTest {

    @Test
    fun `run-tests repairs unwritable results directories before launching suites`() {
        val text = runTestsScriptText()

        assertTrue(
            text.contains("repair_dir_ownership()"),
            "run-tests should include a helper to repair root-owned result directories"
        )
        assertTrue(
            text.contains("docker run --rm -v \"${'$'}target:/target\" alpine sh -lc \"chown -R ${'$'}uid:${'$'}gid /target && chmod -R u+rwX /target\""),
            "run-tests should use a docker-assisted ownership reset when result directories are not writable"
        )
        assertTrue(
            text.contains("ensure_writable_dir \"${'$'}DIST_DIR/test-results\""),
            "run-tests should verify the shared test-results root is writable before preparing suite subdirectories"
        )
        assertTrue(
            text.contains("Unable to prepare writable test results directory"),
            "run-tests should fail fast with a clear error if it cannot repair the result path"
        )
    }

    private fun runTestsScriptText(): String {
        val script = findRepoRoot().resolve("tests.containers/test-runner/run-tests.sh")
        return Files.readString(script)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("tests.containers/test-runner/run-tests.sh"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
