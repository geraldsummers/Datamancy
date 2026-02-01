package org.datamancy.testrunner.framework

import java.util.*
import javax.naming.Context
import javax.naming.directory.*
import javax.naming.ldap.InitialLdapContext
import kotlin.random.Random

/**
 * Helper for managing ephemeral LDAP test users
 *
 * Creates/deletes test users in LDAP for authentication testing
 */
class LdapHelper(
    private val ldapUrl: String,
    private val adminDn: String = "cn=admin,dc=datamancy,dc=net",
    private val adminPassword: String
) {
    private val baseDn = "dc=datamancy,dc=net"
    private val usersDn = "ou=users,$baseDn"

    /**
     * Create a test user in LDAP
     */
    fun createTestUser(username: String, password: String, groups: List<String> = listOf("users")): Result<TestUser> {
        return try {
            val ctx = getLdapContext()

            // Create user DN
            val userDn = "uid=$username,$usersDn"

            // User attributes
            val attrs = BasicAttributes(true) // case-ignore
            attrs.put("objectClass", "inetOrgPerson")
            attrs.put("uid", username)
            attrs.put("cn", username)
            attrs.put("sn", "TestUser")
            attrs.put("mail", "$username@datamancy.test")
            attrs.put("userPassword", password)
            attrs.put("displayName", "Test User $username")

            // Create user entry
            ctx.createSubcontext(userDn, attrs)

            // Add user to groups
            for (groupName in groups) {
                addUserToGroup(ctx, username, groupName)
            }

            ctx.close()

            Result.success(TestUser(username, password, groups))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to create LDAP user: ${e.message}", e))
        }
    }

    /**
     * Delete a test user from LDAP
     */
    fun deleteTestUser(username: String): Result<Unit> {
        return try {
            val ctx = getLdapContext()

            val userDn = "uid=$username,$usersDn"

            // Remove user from all groups first
            removeUserFromAllGroups(ctx, username)

            // Delete user entry
            ctx.destroySubcontext(userDn)

            ctx.close()

            Result.success(Unit)
        } catch (e: Exception) {
            // Swallow errors if user doesn't exist (already cleaned up)
            if (e.message?.contains("No such object") == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete LDAP user: ${e.message}", e))
            }
        }
    }

    /**
     * Create an ephemeral test user with generated credentials
     */
    fun createEphemeralUser(groups: List<String> = listOf("users")): Result<TestUser> {
        val timestamp = System.currentTimeMillis()
        val random = Random.nextInt(1000, 9999)
        val username = "test-$timestamp-$random"
        val password = generateSecurePassword()

        return createTestUser(username, password, groups)
    }

    /**
     * Add user to a group
     */
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
            // Group might not exist or user already in group - not critical for tests
            println("      ℹ️  Could not add user to group $groupName: ${e.message}")
        }
    }

    /**
     * Remove user from all groups
     */
    private fun removeUserFromAllGroups(ctx: DirContext, username: String) {
        try {
            val userDn = "uid=$username,$usersDn"
            val groupsDn = "ou=groups,$baseDn"

            // Search for all groups containing this user
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
            // Not critical - continue with user deletion
        }
    }

    /**
     * Get LDAP context with admin credentials
     */
    private fun getLdapContext(): DirContext {
        val env = Hashtable<String, String>()
        env[Context.INITIAL_CONTEXT_FACTORY] = "com.sun.jndi.ldap.LdapCtxFactory"
        env[Context.PROVIDER_URL] = ldapUrl
        env[Context.SECURITY_AUTHENTICATION] = "simple"
        env[Context.SECURITY_PRINCIPAL] = adminDn
        env[Context.SECURITY_CREDENTIALS] = adminPassword

        return InitialLdapContext(env, null)
    }

    /**
     * Generate a secure random password
     */
    private fun generateSecurePassword(length: Int = 16): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
}

/**
 * Test user data class
 */
data class TestUser(
    val username: String,
    val password: String,
    val groups: List<String>
)
