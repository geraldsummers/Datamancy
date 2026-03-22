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
            text.contains("lines.append(f\"{name}|{'build' if needs_build else 'no-build'}||force-one-shot|{build_key}\")"),
            "smart-up should plan an explicit refresh path for one-shot services in the force-refresh list"
        )
        assertTrue(
            text.contains("info \"Refreshing one-shot reconciler ${'$'}service\""),
            "smart-up should surface one-shot reconciler refreshes in its output"
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
