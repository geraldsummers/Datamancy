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

        assertTrue(
            composeText.contains("./configs/ldap/configure-memberof.sh:/tmp/configure-memberof.sh:ro"),
            "LDAP service should mount configure-memberof.sh so the container can repair memberOf overlay state at startup"
        )
        assertTrue(
            startupScriptText.contains("sh /tmp/configure-memberof.sh"),
            "LDAP startup wrapper should run configure-memberof.sh so tx-gateway role lookups see memberOf on user entries"
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
