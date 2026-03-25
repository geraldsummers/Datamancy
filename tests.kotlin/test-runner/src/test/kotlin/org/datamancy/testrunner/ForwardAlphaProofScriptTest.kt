package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ForwardAlphaProofScriptTest {

    @Test
    fun `forward alpha proof script is deprecated as an operator entrypoint`() {
        val text = repoFileText("scripts/trading/forward_alpha_proof.py")

        assertTrue(
            text.contains("from alpha_proof import"),
            "forward alpha proof should continue to reuse the shared alpha proof helpers while it remains in-repo"
        )
        assertTrue(
            text.contains("forward_alpha_proof.py is deprecated") &&
                text.contains("/api/v1/alpha/cross-sectional/run") &&
                text.contains("/api/v1/alpha/cross-sectional/search/run"),
            "forward alpha proof should refuse direct operator use and redirect operators to cross-sectional research endpoints"
        )
    }

    private fun repoFileText(relativePath: String): String {
        val path = findRepoRoot().resolve(relativePath)
        return Files.readString(path)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("scripts"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
