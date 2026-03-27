package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class TxGatewayComposeConfigTest {

    @Test
    fun `tx-gateway healthcheck requires database connectivity`() {
        val composeText = repoFileText("stack.compose/tx-gateway.yml")

        assertTrue(
            composeText.contains("http://localhost:8080/health/db"),
            "tx-gateway should not report healthy until database connectivity is ready"
        )
        assertTrue(
            composeText.contains("POSTGRES_MARKET_DATA_HOST: market-postgres"),
            "tx-gateway should read market data from the dedicated market-postgres instance instead of the shared cluster"
        )
    }

    private fun repoFileText(relativePath: String): String {
        val path = findRepoRoot().resolve(relativePath)
        return Files.readString(path)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.compose/tx-gateway.yml"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
