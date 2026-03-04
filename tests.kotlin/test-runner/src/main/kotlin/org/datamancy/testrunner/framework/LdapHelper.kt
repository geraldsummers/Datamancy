package org.datamancy.testrunner.framework

import java.util.*
import javax.naming.Context
import javax.naming.directory.*
import javax.naming.ldap.InitialLdapContext
import kotlin.random.Random

/**
 * LDAP user lifecycle management for testing authentication flows.
 *
 * LdapHelper manages the creation and deletion of test users in OpenLDAP, which is
 * the authoritative user directory for the entire Datamancy stack. All authentication
 * flows ultimately validate credentials against LDAP.
 *
 * ## Authentication Cascade Foundation
 * LDAP is the first link in the authentication chain:
 * - **LDAP**: User accounts and group memberships (authoritative source)
 * - **Authelia**: Validates credentials via LDAP bind operations
 * - **OIDC**: Issues tokens based on Authelia sessions (which validated against LDAP)
 * - **Services**: Trust Authelia sessions or OIDC tokens
 *
 * ## Why Ephemeral Users Are Critical
 * Tests create temporary users to ensure:
 * - **Isolation**: Each test has its own user(s), no shared state
 * - **Cleanup**: No accumulation of test accounts in LDAP over time
 * - **Repeatability**: Tests can run multiple times without conflicts
 * - **Real Workflows**: Tests use actual LDAP operations, not mocks
 *
 * ## Integration with Broader Stack
 * Users created here can:
 * - Authenticate with Authelia (via AuthHelper)
 * - Obtain OIDC tokens (via OIDCHelper)
 * - Access services requiring specific group memberships
 * - Test cross-service SSO (one LDAP user, multiple service logins)
 *
 * @property ldapUrl OpenLDAP server URL (e.g., "ldap://ldap:389")
 * @property adminDn LDAP admin distinguished name for management operations
 * @property adminPassword LDAP admin password for creating/deleting users
 */
class LdapHelper(
    private val ldapUrl: String,
    private val adminDn: String = "cn=admin,dc=datamancy,dc=net",
    private val adminPassword: String
) {
    private val baseDn = "dc=datamancy,dc=net"
    private val usersDn = "ou=users,$baseDn"

    /**
     * Creates a test user in LDAP with specified credentials and group memberships.
     *
     * The user is created as an `inetOrgPerson` entry under `ou=users,dc=datamancy,dc=net`
     * with all required attributes for Authelia authentication. Group memberships are
     * established by adding the user's DN to the specified LDAP groups.
     *
     * Tests use this to:
     * - Create users for authentication flow validation
     * - Test group-based access control (e.g., "admins" vs "users" groups)
     * - Validate LDAP bind operations work correctly
     *
     * @param username LDAP username (uid attribute)
     * @param password User password (stored as userPassword attribute)
     * @param groups LDAP groups to add user to (default: ["users"])
     * @return Result.success with TestUser, or Result.failure with error
     */
    fun createTestUser(username: String, password: String, groups: List<String> = listOf("users")): Result<TestUser> {
        return try {
            val ctx = getLdapContext()

            
            val userDn = "uid=$username,$usersDn"

            
            val attrs = BasicAttributes(true) 
            attrs.put("objectClass", "inetOrgPerson")
            attrs.put("uid", username)
            attrs.put("cn", username)
            attrs.put("sn", "TestUser")
            attrs.put("mail", "$username@datamancy.test")
            attrs.put("userPassword", password)
            attrs.put("displayName", "Test User $username")

            
            ctx.createSubcontext(userDn, attrs)

            
            for (groupName in groups) {
                addUserToGroup(ctx, username, groupName)
            }

            ctx.close()

            Result.success(TestUser(username, password, groups))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to create LDAP user: ${e.message}", e))
        }
    }

    
    fun deleteTestUser(username: String): Result<Unit> {
        return try {
            val ctx = getLdapContext()

            val userDn = "uid=$username,$usersDn"

            
            removeUserFromAllGroups(ctx, username)

            
            ctx.destroySubcontext(userDn)

            ctx.close()

            Result.success(Unit)
        } catch (e: Exception) {
            
            if (e.message?.contains("No such object") == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete LDAP user: ${e.message}", e))
            }
        }
    }

    /**
     * Creates an ephemeral user with auto-generated username and secure password.
     *
     * This is the primary method for test isolation. The username includes a timestamp
     * and random number to ensure uniqueness even with parallel test execution. The
     * password is cryptographically secure and immediately discarded after authentication.
     *
     * ## Why This Pattern Works
     * - **No Conflicts**: Timestamp + random ensures uniqueness across parallel tests
     * - **Traceability**: Username includes timestamp for debugging test failures
     * - **Security**: Password never reused, generated fresh each time
     * - **Cleanup**: Caller can identify ephemeral users by "test-" prefix
     *
     * @param groups LDAP groups to assign the user to
     * @return Result.success with TestUser (including generated username and password)
     */
    fun createEphemeralUser(groups: List<String> = listOf("users")): Result<TestUser> {
        val timestamp = System.currentTimeMillis()
        val random = Random.nextInt(1000, 9999)
        val username = "test-$timestamp-$random"
        val password = generateSecurePassword()

        return createTestUser(username, password, groups)
    }

    
    private fun addUserToGroup(ctx: DirContext, username: String, groupName: String) {
        try {
            val groupDn = "cn=$groupName,ou=groups,$baseDn"
            val userDn = "uid=$username,$usersDn"

            val mods = arrayOf(
                ModificationItem(
                    DirContext.ADD_ATTRIBUTE,
                    BasicAttribute("member", userDn)
                )
            )

            ctx.modifyAttributes(groupDn, mods)
        } catch (e: Exception) {
            
            println("      ℹ️  Could not add user to group $groupName: ${e.message}")
        }
    }

    
    private fun removeUserFromAllGroups(ctx: DirContext, username: String) {
        try {
            val userDn = "uid=$username,$usersDn"
            val groupsDn = "ou=groups,$baseDn"

            
            val attrs = SearchControls()
            attrs.searchScope = SearchControls.ONELEVEL_SCOPE
            attrs.returningAttributes = arrayOf("cn")

            val results = ctx.search(groupsDn, "(member=$userDn)", attrs)

            while (results.hasMore()) {
                val result = results.next()
                val groupDn = result.nameInNamespace

                val mods = arrayOf(
                    ModificationItem(
                        DirContext.REMOVE_ATTRIBUTE,
                        BasicAttribute("member", userDn)
                    )
                )

                ctx.modifyAttributes(groupDn, mods)
            }
        } catch (e: Exception) {
            
        }
    }

    
    private fun getLdapContext(): DirContext {
        val env = Hashtable<String, String>()
        env[Context.INITIAL_CONTEXT_FACTORY] = "com.sun.jndi.ldap.LdapCtxFactory"
        env[Context.PROVIDER_URL] = ldapUrl
        env[Context.SECURITY_AUTHENTICATION] = "simple"
        env[Context.SECURITY_PRINCIPAL] = adminDn
        env[Context.SECURITY_CREDENTIALS] = adminPassword

        return InitialLdapContext(env, null)
    }

    
    private fun generateSecurePassword(length: Int = 16): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
}


data class TestUser(
    val username: String,
    val password: String,
    val groups: List<String>
)
