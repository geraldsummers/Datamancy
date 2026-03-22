package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class PostgresComposeConfigTest {

    @Test
    fun `postgres compose reserves explicit worker headroom for timescaledb jobs`() {
        val composeText = repoFileText("stack.compose/postgres.yml")

        assertTrue(
            composeText.contains("max_worker_processes=48"),
            "Postgres should reserve enough worker processes so Timescale jobs and autovacuum do not starve each other"
        )
        assertTrue(
            composeText.contains("timescaledb.max_background_workers=16"),
            "Postgres should pin Timescale background worker allocation instead of relying on image defaults"
        )
    }

    private fun repoFileText(relativePath: String): String {
        val path = findRepoRoot().resolve(relativePath)
        return Files.readString(path)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.compose/postgres.yml"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
