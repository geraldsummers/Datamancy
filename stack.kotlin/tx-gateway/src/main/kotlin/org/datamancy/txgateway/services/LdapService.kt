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
    private val defaultAllowedChains = parseCsvEnv(
        "LDAP_DEFAULT_ALLOWED_CHAINS",
        "base,arbitrum,optimism"
    )
    private val defaultAllowedExchanges = parseCsvEnv(
        "LDAP_DEFAULT_ALLOWED_EXCHANGES",
        "swyftx,binance,bybit,coinbase,dydx,hyperliquid,aster"
    )
    private val defaultAllowedTradingModes = parseCsvEnv(
        "LDAP_DEFAULT_ALLOWED_TRADING_MODES",
        "backtest,forward_paper"
    )
    private val defaultMaxTxPerHour = System.getenv("LDAP_DEFAULT_MAX_TX_PER_HOUR")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 100
    private val defaultMaxTxValueUsd = System.getenv("LDAP_DEFAULT_MAX_TX_VALUE_USD")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 10000

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
            val hasTradingProfile = entry.hasTradingProfile()

            val email = entry.getAttributeValue("mail") ?: "$username@datamancy.net"
            val groups = entry.getAttributeValues("memberOf")?.map { dn ->
                // Extract CN from DN (e.g., "cn=traders,ou=groups,dc=..." -> "traders")
                dn.substringAfter("cn=").substringBefore(",")
            } ?: emptyList()

            val evmAddress = entry.getAttributeValue("evmAddress")
            val allowedChains = entry.getAttributeValues("allowedChains")?.toList()
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                ?.ifEmpty { null }
                ?: if (hasTradingProfile) defaultAllowedChains else emptyList()
            val allowedExchanges = entry.getAttributeValues("allowedExchanges")?.toList()
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                ?.distinct()
                ?.ifEmpty { null }
                ?: if (hasTradingProfile) defaultAllowedExchanges else emptyList()
            val allowedTradingModes = entry.getAttributeValues("allowedTradingModes")?.toList()
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                ?.distinct()
                ?.ifEmpty { null }
                ?: if (hasTradingProfile) defaultAllowedTradingModes else emptyList()
            val maxTxPerHour = entry.getAttributeValue("maxTxPerHour")?.toIntOrNull()?.coerceAtLeast(1)
                ?: if (hasTradingProfile) defaultMaxTxPerHour else 1
            val maxTxValueUSD = entry.getAttributeValue("maxTxValueUSD")?.toIntOrNull()?.coerceAtLeast(1)
                ?: if (hasTradingProfile) defaultMaxTxValueUsd else 1

            UserInfo(
                username = username,
                email = email,
                groups = groups,
                evmAddress = evmAddress,
                allowedChains = allowedChains,
                allowedExchanges = allowedExchanges,
                allowedTradingModes = allowedTradingModes,
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

    private fun parseCsvEnv(name: String, defaultValue: String): List<String> {
        val raw = System.getenv(name)?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultValue
        return raw.split(',', ';', '|', ' ')
            .map { it.trim() }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun SearchResultEntry.hasTradingProfile(): Boolean {
        val objectClasses = getAttributeValues("objectClass")
            ?.map { it.trim().lowercase() }
            ?.toSet()
            ?: emptySet()
        if ("tradingaccount" in objectClasses) {
            return true
        }

        val tradingAttributes = listOf(
            "evmAddress",
            "evmKeyRef",
            "hyperliquidKeyRef",
            "allowedChains",
            "allowedExchanges",
            "allowedTradingModes",
            "maxTxPerHour",
            "maxTxValueUSD"
        )
        return tradingAttributes.any { hasAttribute(it) }
    }
}
