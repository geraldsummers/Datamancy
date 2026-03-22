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

    @Test
    fun `postgres bootstrap and reconcile keep timescaledb scoped to datamancy`() {
        val initDbText = repoFileText("stack.config/postgres/init-db.sh")
        val reconcileText = repoFileText("stack.config/postgres/reconcile-datamancy-schema.sh")

        assertTrue(
            initDbText.contains("\\connect template1") &&
                initDbText.contains("DROP EXTENSION IF EXISTS timescaledb CASCADE;"),
            "Postgres bootstrap should remove TimescaleDB from template1 so non-market databases do not inherit background jobs"
        )
        assertTrue(
            reconcileText.contains("drop_timescaledb_if_present \"template1\"") &&
                reconcileText.contains("datname <> 'datamancy'") &&
                reconcileText.contains("DROP EXTENSION IF EXISTS timescaledb CASCADE;"),
            "Postgres reconcile should strip TimescaleDB from non-datamancy databases on existing clusters"
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
