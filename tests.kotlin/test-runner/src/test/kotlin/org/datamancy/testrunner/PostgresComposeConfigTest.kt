package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class PostgresComposeConfigTest {

    @Test
    fun `postgres compose reserves explicit worker headroom for timescaledb jobs`() {
        val composeText = repoFileText("stack.compose/postgres.yml")
        val marketComposeText = repoFileText("stack.compose/market-postgres.yml")

        assertTrue(
            composeText.contains("max_worker_processes=48"),
            "Postgres should reserve enough worker processes so Timescale jobs and autovacuum do not starve each other"
        )
        assertTrue(
            composeText.contains("timescaledb.max_background_workers=16"),
            "Postgres should pin Timescale background worker allocation instead of relying on image defaults"
        )
        assertTrue(
            marketComposeText.contains("max_worker_processes=48") &&
                marketComposeText.contains("timescaledb.max_background_workers=16"),
            "Dedicated market-postgres should preserve the same explicit Timescale worker headroom as the shared cluster"
        )
    }

    @Test
    fun `postgres bootstrap and reconcile keep timescaledb scoped to datamancy`() {
        val initDbText = repoFileText("stack.config/postgres/init-db.sh")
        val initMarketDbText = repoFileText("stack.config/postgres/init-market-db.sh")
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
        assertTrue(
            initMarketDbText.contains("CREATE DATABASE datamancy OWNER pipeline_user") &&
                initMarketDbText.contains("search_service_user") &&
                initMarketDbText.contains("test_runner_user"),
            "Dedicated market-postgres bootstrap should only initialize the Datamancy market database and its read clients"
        )
    }

    @Test
    fun `market postgres keeps market data on vector db root instead of nocow raid`() {
        val marketComposeText = repoFileText("stack.compose/market-postgres.yml")
        val volumeInitText = repoFileText("global.settings/volume-init.yml")

        assertTrue(
            marketComposeText.contains("\${VECTOR_DB_ROOT}/market-postgres:/var/lib/postgresql/data"),
            "Dedicated market-postgres should place PGDATA on the vector SSD root"
        )
        assertTrue(
            volumeInitText.contains("mkdir -p \${VECTOR_DB_ROOT}/market-postgres"),
            "Volume init should provision the market-postgres directory on the vector SSD root"
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
