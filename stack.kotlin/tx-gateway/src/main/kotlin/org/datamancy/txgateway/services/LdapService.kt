package org.datamancy.txgateway.services

import com.unboundid.ldap.sdk.*
import org.datamancy.txgateway.models.UserInfo
import org.slf4j.LoggerFactory

class LdapService(
    private val host: String = System.getenv("LDAP_HOST") ?: "ldap",
    private val port: Int = System.getenv("LDAP_PORT")?.toInt() ?: 389,
    private val baseDn: String = System.getenv("LDAP_BASE_DN") ?: "dc=datamancy,dc=net",
    private val bindDn: String = System.getenv("LDAP_BIND_DN") ?: "cn=admin,$baseDn",
    private val bindPassword: String = System.getenv("LDAP_BIND_PASSWORD") ?: ""
) {
    private val logger = LoggerFactory.getLogger(LdapService::class.java)
    private lateinit var connection: LDAPConnection

    fun init() {
        connection = LDAPConnection(host, port, bindDn, bindPassword)
        logger.info("LDAP connection established")
    }

    fun getUserInfo(username: String): UserInfo? {
        return try {
            val searchRequest = SearchRequest(
                baseDn,
                SearchScope.SUB,
                Filter.createEqualityFilter("uid", username),
                "*"
            )

            val searchResult = connection.search(searchRequest)
            if (searchResult.entryCount == 0) {
                logger.warn("User not found in LDAP: $username")
                return null
            }

            val entry = searchResult.searchEntries[0]

            val email = entry.getAttributeValue("mail") ?: "$username@datamancy.net"
            val groups = entry.getAttributeValues("memberOf")?.map { dn ->
                // Extract CN from DN (e.g., "cn=traders,ou=groups,dc=..." -> "traders")
                dn.substringAfter("cn=").substringBefore(",")
            } ?: emptyList()

            val evmAddress = entry.getAttributeValue("evmAddress")
            val allowedChains = entry.getAttributeValues("allowedChains")?.toList()
                ?: listOf("base", "arbitrum", "optimism")
            val allowedExchanges = entry.getAttributeValues("allowedExchanges")?.toList()
                ?: listOf("hyperliquid")
            val maxTxPerHour = entry.getAttributeValue("maxTxPerHour")?.toIntOrNull() ?: 100
            val maxTxValueUSD = entry.getAttributeValue("maxTxValueUSD")?.toIntOrNull() ?: 10000

            UserInfo(
                username = username,
                email = email,
                groups = groups,
                evmAddress = evmAddress,
                allowedChains = allowedChains,
                allowedExchanges = allowedExchanges,
                maxTxPerHour = maxTxPerHour,
                maxTxValueUSD = maxTxValueUSD
            )
        } catch (e: LDAPException) {
            logger.error("LDAP search failed for user $username", e)
            null
        }
    }

    fun getEvmAddress(username: String): String? {
        return getUserInfo(username)?.evmAddress
    }

    fun healthCheck() {
        val rootDSE = connection.getRootDSE()
        require(rootDSE != null) { "LDAP connection not healthy" }
    }

    fun close() {
        if (::connection.isInitialized) {
            connection.close()
        }
    }
}
