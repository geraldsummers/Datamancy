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
            text.contains("STATUS_JSON=\"\${STATUS_JSON:-\${DEPLOY_STATUS_JSON:-\$ROOT_DIR/deploy-status.json}}\""),
            "smart-up should track deploy state outside the synced build-status artifact"
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
            text.contains("Updated deploy status: \$STATUS_JSON"),
            "smart-up should persist deploy status after recreating changed services"
        )
        assertTrue(
            text.contains("append_entry(name, \"build\" if needs_build else \"no-build\", \"\", \"force-one-shot\", build_key)"),
            "smart-up should plan an explicit refresh path for one-shot services in the force-refresh list"
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
