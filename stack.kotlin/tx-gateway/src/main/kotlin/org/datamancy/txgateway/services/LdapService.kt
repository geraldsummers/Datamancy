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
    private val usersDn = "ou=users,$baseDn"
    private val defaultMaxTxPerHour = System.getenv("LDAP_DEFAULT_MAX_TX_PER_HOUR")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 240
    private val defaultMaxTxValueUsd = System.getenv("LDAP_DEFAULT_MAX_TX_VALUE_USD")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 25000

    fun init() {
        connection = LDAPConnection(host, port, bindDn, bindPassword)
        logger.info("LDAP connection established")
    }

    fun getUserInfo(username: String): UserInfo? {
        val audit = getTradingAccountAudit(username) ?: return null
        return UserInfo(
            username = audit.username,
            email = audit.email,
            groups = audit.groups,
            evmAddress = getSearchResultEntry(username)?.getAttributeValue("evmAddress"),
            allowedChains = audit.allowedChains,
            allowedExchanges = audit.allowedExchanges,
            allowedTradingModes = audit.allowedTradingModes,
            maxTxPerHour = audit.maxTxPerHour,
            maxTxValueUSD = audit.maxTxValueUSD
        )
    }

    fun getTradingAccountAudit(username: String): TradingAccountAudit? {
        val entry = getSearchResultEntry(username) ?: return null
        return buildTradingAccountAudit(entry)
    }

    fun listTradingAccountAudits(): List<TradingAccountAudit> {
        return try {
            val searchRequest = SearchRequest(
                usersDn,
                SearchScope.ONE,
                Filter.createPresenceFilter("uid"),
                "*"
            )

            val searchResult = connection.search(searchRequest)
            searchResult.searchEntries
                .map(::buildTradingAccountAudit)
                .sortedBy { it.username }
        } catch (e: LDAPException) {
            logger.error("LDAP account audit scan failed", e)
            emptyList()
        }
    }

    fun getEvmAddress(username: String): String? {
        return getSearchResultEntry(username)?.getAttributeValue("evmAddress")
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

    private fun getSearchResultEntry(username: String): SearchResultEntry? {
        return try {
            val searchRequest = SearchRequest(
                baseDn,
                SearchScope.SUB,
                Filter.createEqualityFilter("uid", username),
                "*"
            )

            val searchResult = connection.search(searchRequest)
            if (searchResult.entryCount == 0) {
                logger.warn("User not found in LDAP: {}", username)
                return null
            }
            searchResult.searchEntries.first()
        } catch (e: LDAPException) {
            logger.error("LDAP search failed for user {}", username, e)
            null
        }
    }

    private fun buildTradingAccountAudit(entry: SearchResultEntry): TradingAccountAudit {
        val username = entry.getAttributeValue("uid")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: entry.dn.substringAfter("uid=").substringBefore(",")
        val email = entry.getAttributeValue("mail") ?: "$username@datamancy.net"
        val groups = entry.getAttributeValues("memberOf")
            ?.mapNotNull { dn ->
                dn.substringAfter("cn=").substringBefore(",")
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotEmpty() }
            }
            ?.distinct()
            ?: emptyList()
        val objectClasses = entry.getAttributeValues("objectClass")
            ?.map { it.trim().lowercase() }
            ?.toSet()
            ?: emptySet()
        val hasTradingObjectClass = "tradingaccount" in objectClasses
        val hasTradingProfile = hasTradingObjectClass || entry.hasTradingAttributes()

        val rawAllowedChains = entry.getAttributeValues("allowedChains")?.toList() ?: emptyList()
        val rawAllowedExchanges = entry.getAttributeValues("allowedExchanges")?.toList() ?: emptyList()
        val rawAllowedTradingModes = entry.getAttributeValues("allowedTradingModes")?.toList() ?: emptyList()

        val normalizedChains = TradingPermissionCatalog.normalizeChains(
            rawValues = rawAllowedChains,
            defaultIfEmpty = hasTradingProfile
        )
        val normalizedExchanges = TradingPermissionCatalog.normalizeExchanges(
            rawValues = rawAllowedExchanges,
            defaultIfEmpty = hasTradingProfile
        )
        val normalizedTradingModes = TradingPermissionCatalog.normalizeTradingModes(
            rawValues = rawAllowedTradingModes,
            defaultIfEmpty = hasTradingProfile
        )

        val maxTxPerHourValue = entry.getAttributeValue("maxTxPerHour")?.toIntOrNull()?.coerceAtLeast(1)
        val maxTxValueUsdValue = entry.getAttributeValue("maxTxValueUSD")?.toIntOrNull()?.coerceAtLeast(1)
        val maxTxPerHour = maxTxPerHourValue ?: if (hasTradingProfile) defaultMaxTxPerHour else 1
        val maxTxValueUsd = maxTxValueUsdValue ?: if (hasTradingProfile) defaultMaxTxValueUsd else 1

        val findings = mutableListOf<String>()
        if (hasTradingProfile && !hasTradingObjectClass) {
            findings += "trading profile attributes present without tradingAccount objectClass"
        }
        appendNormalizationFindings("allowedChains", normalizedChains, findings)
        appendNormalizationFindings("allowedExchanges", normalizedExchanges, findings)
        appendNormalizationFindings("allowedTradingModes", normalizedTradingModes, findings)
        if (hasTradingProfile && maxTxPerHourValue == null) {
            findings += "maxTxPerHour defaulted to $defaultMaxTxPerHour"
        }
        if (hasTradingProfile && maxTxValueUsdValue == null) {
            findings += "maxTxValueUSD defaulted to $defaultMaxTxValueUsd"
        }
        val liveModes = normalizedTradingModes.normalized.filter { it == "testnet_live" || it == "mainnet_live" }
        if (liveModes.isNotEmpty() && "hyperliquid" !in normalizedExchanges.normalized) {
            findings += "live trading modes granted without hyperliquid exchange access"
        }
        if (
            "mainnet_live" in normalizedTradingModes.normalized &&
            groups.toSet().intersect(TradingPermissionCatalog.mainnetReservedGroups).isEmpty()
        ) {
            findings += "mainnet_live allowed without reserved group membership (${TradingPermissionCatalog.mainnetReservedGroups.joinToString(",")})"
        }

        return TradingAccountAudit(
            username = username,
            email = email,
            groups = groups,
            hasTradingProfile = hasTradingProfile,
            hasTradingObjectClass = hasTradingObjectClass,
            rawAllowedChains = rawAllowedChains,
            allowedChains = normalizedChains.normalized,
            rawAllowedExchanges = rawAllowedExchanges,
            allowedExchanges = normalizedExchanges.normalized,
            rawAllowedTradingModes = rawAllowedTradingModes,
            allowedTradingModes = normalizedTradingModes.normalized,
            maxTxPerHour = maxTxPerHour,
            maxTxValueUSD = maxTxValueUsd,
            findings = findings.distinct()
        )
    }

    private fun appendNormalizationFindings(
        attributeName: String,
        normalization: PermissionNormalization,
        findings: MutableList<String>
    ) {
        if (normalization.defaulted) {
            findings += "$attributeName defaulted to ${normalization.normalized.joinToString(",")}"
        }
        if (normalization.duplicatesDropped) {
            findings += "$attributeName contained duplicate values"
        }
        if (normalization.unsupported.isNotEmpty()) {
            findings += "$attributeName contains unsupported values: ${normalization.unsupported.joinToString(",")}"
        }
    }

    private fun SearchResultEntry.hasTradingAttributes(): Boolean {
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
