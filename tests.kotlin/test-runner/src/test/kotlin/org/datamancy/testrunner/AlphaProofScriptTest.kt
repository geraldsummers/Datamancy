package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class AlphaProofScriptTest {

    @Test
    fun `alpha proof script is deprecated as an operator entrypoint`() {
        val text = repoFileText("scripts/trading/alpha_proof.py")

        assertTrue(
            text.contains("FROM research_features_1m"),
            "alpha proof should continue to consume the canonical research feature layer when imported internally"
        )
        assertTrue(
            text.contains("alpha_proof.py is deprecated") &&
                text.contains("/api/v1/alpha/cross-sectional/run") &&
                text.contains("/api/v1/alpha/cross-sectional/search/run"),
            "alpha proof should refuse direct operator use and redirect operators to cross-sectional research endpoints"
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
