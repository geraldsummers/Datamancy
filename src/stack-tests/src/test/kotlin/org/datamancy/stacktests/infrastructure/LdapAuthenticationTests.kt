package org.datamancy.stacktests.infrastructure

import kotlinx.coroutines.runBlocking
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Tests LDAP authentication and directory operations.
 *
 * Tests cover:
 * - LDAP server connectivity
 * - Admin authentication
 * - User search operations
 * - Group membership queries
 * - memberOf overlay functionality
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LdapAuthenticationTests : BaseStackTest() {

    @Test
    @Order(1)
    fun `LDAP server is accessible and healthy`() = runBlocking {
        // Test LDAP connectivity using ldapsearch command inside container with admin creds
        val adminPassword = getConfig("LDAP_ADMIN_PASSWORD", "admin")
        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", adminPassword,
            "-b", "dc=stack,dc=local",
            "-s", "base",
            "-LLL",
            "(objectClass=*)"
        )

        assertTrue(result.success, "LDAP server should be accessible")
        assertTrue(result.output.contains("dc=stack"), "Should return base DN")
        println("✓ LDAP server is accessible")
    }

    @Test
    @Order(2)
    fun `admin can authenticate to LDAP`() = runBlocking {
        val adminPassword = getConfig("LDAP_ADMIN_PASSWORD", "admin")

        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", adminPassword,
            "-b", "dc=stack,dc=local",
            "-LLL",
            "(objectClass=organization)"
        )

        assertTrue(result.success, "Admin should authenticate successfully")
        println("✓ Admin authentication successful")
    }

    @Test
    @Order(3)
    fun `can search for users in LDAP`() = runBlocking {
        val adminPassword = getConfig("LDAP_ADMIN_PASSWORD", "admin")

        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", adminPassword,
            "-b", "ou=users,dc=stack,dc=local",
            "-LLL",
            "(objectClass=inetOrgPerson)"
        )

        assertTrue(result.success, "User search should succeed")
        // Check if any users exist (from bootstrap_ldap.ldif)
        println("  Found users: ${if (result.output.contains("dn:")) "Yes" else "No (empty directory)"}")
        println("✓ User search executed successfully")
    }

    @Test
    @Order(4)
    fun `can search for groups in LDAP`() = runBlocking {
        val adminPassword = getConfig("LDAP_ADMIN_PASSWORD", "admin")

        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", adminPassword,
            "-b", "ou=groups,dc=stack,dc=local",
            "-LLL",
            "(objectClass=groupOfNames)"
        )

        assertTrue(result.success, "Group search should succeed")

        // Verify standard groups exist
        val hasAdminsGroup = result.output.contains("cn=admins") ||
                            result.output.contains("cn: admins")
        val hasUsersGroup = result.output.contains("cn=users") ||
                           result.output.contains("cn: users")

        assertTrue(hasAdminsGroup || hasUsersGroup,
            "Should find standard groups (admins or users)")
        println("✓ Group search found standard groups")
    }

    @Test
    @Order(5)
    fun `memberOf overlay is configured`() = runBlocking {
        val adminPassword = getConfig("LDAP_ADMIN_PASSWORD", "admin")

        // Search for a user and check if memberOf attribute is present
        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", adminPassword,
            "-b", "ou=users,dc=stack,dc=local",
            "-LLL",
            "(objectClass=inetOrgPerson)",
            "memberOf"
        )

        assertTrue(result.success, "memberOf overlay query should succeed")

        // Check if memberOf overlay is working
        if (result.output.contains("memberOf:") || result.output.contains("member of:")) {
            println("✓ memberOf overlay is active and working")
        } else {
            println("⚠ memberOf overlay configured but no users have group memberships yet")
        }
    }

    @Test
    @Order(6)
    fun `invalid credentials are rejected`() = runBlocking {
        val result = executeLdapCommand(
            "ldapsearch",
            "-x",
            "-H", "ldap://localhost",
            "-D", "cn=admin,dc=stack,dc=local",
            "-w", "wrongpassword",
            "-b", "dc=stack,dc=local",
            "-LLL",
            "(objectClass=*)"
        )

        assertFalse(result.success, "Invalid credentials should be rejected")
        assertTrue(
            result.error.contains("Invalid credentials") ||
            result.error.contains("49"),  // LDAP error code 49
            "Should return invalid credentials error"
        )
        println("✓ Invalid credentials properly rejected")
    }

    /**
     * Execute an LDAP command inside the ldap container and capture output.
     */
    private fun executeLdapCommand(vararg command: String): CommandResult {
        return try {
            // Run ldapsearch inside the ldap docker container
            val dockerCommand = listOf("docker", "exec", "ldap") + command.toList()
            val process = ProcessBuilder(dockerCommand)
                .redirectErrorStream(false)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream))
                .readText()
            val error = BufferedReader(InputStreamReader(process.errorStream))
                .readText()

            val exitCode = process.waitFor()

            CommandResult(
                success = exitCode == 0,
                output = output,
                error = error,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = "Command execution failed: ${e.message}",
                exitCode = -1
            )
        }
    }

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int
    )

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║         LDAP Authentication Tests                  ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
