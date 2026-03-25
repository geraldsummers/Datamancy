package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class SmartUpScriptTest {

    @Test
    fun `smart-up force refreshes ldap one-shot reconciler`() {
        val text = smartUpScriptText()

        assertTrue(
            text.contains("SMART_UP_STATE_DIR=\"\${SMART_UP_STATE_DIR:-\${XDG_STATE_HOME:-\$HOME/.local/state}/datamancy/smart-up}\""),
            "smart-up should keep deploy state in a persistent per-user state directory by default"
        )
        assertTrue(
            text.contains("STATUS_JSON=\"\${STATUS_JSON:-\${DEPLOY_STATUS_JSON:-}}\""),
            "smart-up should allow a persistent deploy ledger override without defaulting back into the rsynced tree"
        )
        assertTrue(
            text.contains("STATUS_JSON=\"\${SMART_UP_STATE_DIR}/deploy-status-\${status_key}.json\""),
            "smart-up should derive a per-stack deploy ledger path under the persistent state directory"
        )
        assertTrue(
            text.contains("LEGACY_STATUS_JSON=\"\${ROOT_DIR}/deploy-status.json\""),
            "smart-up should still recognize the old in-tree deploy status path for migration"
        )
        assertTrue(
            text.contains("Migrated legacy deploy status to \$STATUS_JSON"),
            "smart-up should migrate existing in-tree deploy ledgers into the persistent state location"
        )
        assertTrue(
            text.contains("rsync --delete"),
            "smart-up should explicitly warn when the deploy ledger is configured inside the synced tree"
        )
        assertTrue(
            text.contains("FORCE_REFRESH_SERVICES=\"\${FORCE_REFRESH_SERVICES:-postgres-datamancy-reconcile,ldap-ensure-suffixes,test-all,test-playwright-e2e,test-trading-staged}\""),
            "smart-up should force refresh schema reconcilers and staged trading probes so deploy-time credentials stay consistent"
        )
        assertTrue(
            text.contains("last_deployed_commit"),
            "smart-up should compare services against last deployed commit rather than synced build status"
        )
        assertTrue(
            text.contains("last_deployed_config_hash"),
            "smart-up should track deployed compose config hashes so shared compose files do not force sibling recreates"
        )
        assertTrue(
            text.contains("config --hash '*' > \"\$COMPOSE_HASHES_FILE\""),
            "smart-up should compute per-service compose hashes instead of relying on shared compose file commits"
        )
        assertTrue(
            text.contains("com.docker.compose.config-hash"),
            "smart-up should fall back to the deployed container config hash when migrating existing deploy state"
        )
        assertTrue(
            text.contains("Updated deploy status: \$STATUS_JSON"),
            "smart-up should persist deploy status after recreating changed services"
        )
        assertTrue(
            text.contains("\"force-one-shot\""),
            "smart-up should plan an explicit refresh path for one-shot services in the force-refresh list"
        )
        assertTrue(
            text.contains("dependency-refresh:"),
            "smart-up should plan dependent runtime recreates when a changed one-shot startup dependency requires them"
        )
        assertTrue(
            text.contains("because startup dependency"),
            "smart-up should explain startup-dependency-driven recreates in its output"
        )
        assertTrue(
            text.contains("info \"Refreshing one-shot reconciler ${'$'}service\""),
            "smart-up should surface one-shot reconciler refreshes in its output"
        )
        assertTrue(
            text.contains("wait_for_service_ready()"),
            "smart-up should wait for refreshed services to become ready before it exits"
        )
        assertTrue(
            text.contains("wait_for_service_ready \"${'$'}service\" \"${'$'}wait_mode\" \"${'$'}timeout_seconds\""),
            "smart-up should block on service readiness after each recreate"
        )
        assertTrue(
            text.contains("state_info=\"$(docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.ExitCode}}'"),
            "smart-up should inspect runtime state so one-shot services and healthchecked services are handled correctly"
        )
        assertTrue(
            text.contains("docker compose -f \"${'$'}COMPOSE_FILE\" ps -a -q \"${'$'}1\""),
            "smart-up should inspect exited one-shot containers as well as running ones"
        )
        assertTrue(
            text.contains("[\"git\", \"merge-base\", \"--is-ancestor\""),
            "smart-up should migrate existing deploy state without treating older source commits as fresh changes"
        )
        assertTrue(
            text.contains("repair_dir_ownership()"),
            "smart-up should include the same ownership repair helper used by the run-tests entrypoint"
        )
        assertTrue(
            text.contains("ensure_writable_dir \"${'$'}ROOT_DIR/test-results\""),
            "smart-up should repair root-owned test result paths before force-refreshing test services"
        )
        assertTrue(
            text.contains("Unable to prepare writable test results directory"),
            "smart-up should fail fast if test result directories remain unwritable after repair attempts"
        )
    }

    private fun smartUpScriptText(): String {
        val script = findRepoRoot().resolve("smart-up.sh")
        return Files.readString(script)
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
