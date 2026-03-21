package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class LdapMemberOfConfigTest {

    @Test
    fun `ldap container runs memberOf reconciler on startup`() {
        val composeText = repoFileText("stack.compose/ldap.yml")
        val startupScriptText = repoFileText("stack.config/ldap/run-with-schema-ensure.sh")
        val memberOfScriptText = repoFileText("stack.config/ldap/configure-memberof.sh")

        assertTrue(
            composeText.contains("./configs/ldap/configure-memberof.sh:/tmp/configure-memberof.sh:ro"),
            "LDAP service should mount configure-memberof.sh so the container can repair memberOf overlay state at startup"
        )
        assertTrue(
            startupScriptText.contains("sh /tmp/configure-memberof.sh"),
            "LDAP startup wrapper should run configure-memberof.sh so tx-gateway role lookups see memberOf on user entries"
        )
        assertTrue(
            memberOfScriptText.contains("rewrite_group_membership \"${'$'}group_dn\" \"member\""),
            "memberOf reconciler should rewrite the real membership attribute so existing users get backfilled memberOf values"
        )
        assertTrue(
            memberOfScriptText.contains("Configuration is correct, continuing to reconcile existing group memberships"),
            "memberOf reconciler should continue into the rewrite phase even when the overlay config is already correct"
        )
        kotlin.test.assertFalse(
            memberOfScriptText.contains("description: trigger-memberof-update"),
            "memberOf reconciler should not rely on description touch updates because they do not repopulate memberOf for existing groups"
        )
        kotlin.test.assertFalse(
            memberOfScriptText.contains("Configuration is correct, no update needed\"\n    exit 0"),
            "memberOf reconciler should not exit before rewriting existing group memberships"
        )
    }

    private fun repoFileText(relativePath: String): String {
        val path = findRepoRoot().resolve(relativePath)
        return Files.readString(path)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.compose/ldap.yml"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
