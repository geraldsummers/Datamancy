package org.datamancy.ldapsync.core

import com.unboundid.ldap.sdk.*
import org.datamancy.ldapsync.api.LdapUser
import org.slf4j.LoggerFactory

/**
 * LDAP client for querying user information
 */
class LdapClient(
    private val host: String,
    private val port: Int,
    private val bindDn: String,
    private val bindPassword: String,
    private val baseDn: String,
    private val usersOu: String = "ou=users",
    private val groupsOu: String = "ou=groups"
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var connection: LDAPConnection? = null

    suspend fun connect() {
        try {
            log.info("Connecting to LDAP server: ldap://$host:$port")
            connection = LDAPConnection(host, port, bindDn, bindPassword)
            log.info("Successfully connected to LDAP server")
        } catch (e: LDAPException) {
            log.error("Failed to connect to LDAP: ${e.message}", e)
            throw RuntimeException("LDAP connection failed", e)
        }
    }

    suspend fun disconnect() {
        connection?.close()
        connection = null
        log.info("Disconnected from LDAP server")
    }

    /**
     * Query all users from LDAP
     */
    suspend fun getAllUsers(): List<LdapUser> {
        val conn = connection ?: throw IllegalStateException("Not connected to LDAP")

        val searchBase = "$usersOu,$baseDn"
        val filter = "(objectClass=inetOrgPerson)"

        log.info("Searching for users in: $searchBase with filter: $filter")

        val searchRequest = SearchRequest(
            searchBase,
            SearchScope.ONE,
            filter,
            "uid", "mail", "cn", "sn", "displayName", "uidNumber", "gidNumber"
        )

        return try {
            val searchResult = conn.search(searchRequest)
            val users = searchResult.searchEntries.mapNotNull { entry ->
                parseLdapEntry(entry)
            }
            log.info("Found ${users.size} users in LDAP")
            users
        } catch (e: LDAPException) {
            log.error("Failed to search LDAP: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get groups for a specific user DN
     */
    private suspend fun getGroupsForUser(userDn: String): Set<String> {
        val conn = connection ?: return emptySet()

        val searchBase = "$groupsOu,$baseDn"
        val filter = "(&(objectClass=groupOfNames)(member=$userDn))"

        val searchRequest = SearchRequest(
            searchBase,
            SearchScope.ONE,
            filter,
            "cn"
        )

        return try {
            val searchResult = conn.search(searchRequest)
            searchResult.searchEntries.mapNotNull { it.getAttributeValue("cn") }.toSet()
        } catch (e: LDAPException) {
            log.warn("Failed to get groups for $userDn: ${e.message}")
            emptySet()
        }
    }

    private suspend fun parseLdapEntry(entry: SearchResultEntry): LdapUser? {
        val uid = entry.getAttributeValue("uid") ?: return null
        val email = entry.getAttributeValue("mail") ?: "$uid@example.com"
        val cn = entry.getAttributeValue("cn") ?: uid
        val sn = entry.getAttributeValue("sn") ?: uid
        val displayName = entry.getAttributeValue("displayName") ?: cn
        val uidNumber = entry.getAttributeValue("uidNumber")?.toIntOrNull() ?: 10000
        val gidNumber = entry.getAttributeValue("gidNumber")?.toIntOrNull() ?: 10000

        // Get groups for this user
        val groups = getGroupsForUser(entry.dn)

        return LdapUser(
            uid = uid,
            email = email,
            displayName = displayName,
            cn = cn,
            sn = sn,
            groups = groups,
            uidNumber = uidNumber,
            gidNumber = gidNumber,
            attributes = entry.attributes.associate { it.name to (it.value ?: "") }
        )
    }

    /**
     * Test LDAP connection
     */
    suspend fun testConnection(): Boolean {
        return try {
            val conn = connection ?: throw IllegalStateException("Not connected")
            conn.isConnected
        } catch (e: Exception) {
            log.error("Connection test failed: ${e.message}")
            false
        }
    }
}
