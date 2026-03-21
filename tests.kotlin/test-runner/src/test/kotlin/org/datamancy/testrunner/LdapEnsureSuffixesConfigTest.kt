package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LdapEnsureSuffixesConfigTest {

    @Test
    fun `ldap ensure script stays idempotent for existing entries`() {
        val scriptText = repoFileText("stack.config/ldap/ensure-suffixes.sh")

        assertTrue(
            scriptText.contains("Type or value exists"),
            "ldap ensure script should tolerate duplicate LDAP add operations so reruns stay idempotent"
        )
        assertTrue(
            scriptText.contains("Already exists"),
            "ldap ensure script should tolerate already-existing LDAP entries when startup reconciliation reruns"
        )
        assertTrue(
            scriptText.contains("entry_has_exact_value"),
            "ldap ensure script should verify exact group membership values before attempting member adds"
        )
        assertTrue(
            scriptText.contains("grep -Fqx \"\${ATTRIBUTE_NAME}: \${EXPECTED_VALUE}\""),
            "ldap ensure script should compare group membership values literally to avoid filter false negatives"
        )
        assertFalse(
            scriptText.contains("run_ldap_cmd \"group member add \${GROUP_NAME}\" \\\n    ldapmodify -x -H ldap://ldap:389 -D \"\$ADMIN_DN\" -w \"\$ADMIN_PW\" -f /tmp/group_member_add.ldif >/dev/null"),
            "ldap ensure script should not discard run_ldap_cmd error output for group membership updates"
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
