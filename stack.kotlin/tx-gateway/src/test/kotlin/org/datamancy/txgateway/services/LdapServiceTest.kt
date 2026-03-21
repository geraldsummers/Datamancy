package org.datamancy.txgateway.services

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResult
import com.unboundid.ldap.sdk.SearchResultEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LdapServiceTest {

    @Test
    fun `trading account audit falls back to group search when memberOf is absent`() {
        val service = LdapService(
            host = "ldap",
            port = 389,
            baseDn = "dc=datamancy,dc=net",
            bindDn = "cn=admin,dc=datamancy,dc=net",
            bindPassword = "secret"
        )
        val connection = mockk<LDAPConnection>(relaxed = true)
        every { connection.isConnected } returns true
        val userEntry = mockk<SearchResultEntry>()
        every { userEntry.dn } returns "uid=trader1,ou=users,dc=datamancy,dc=net"
        every { userEntry.getAttributeValue("uid") } returns "trader1"
        every { userEntry.getAttributeValue("mail") } returns "trader1@datamancy.net"
        every { userEntry.getAttributeValue("maxTxPerHour") } returns "100"
        every { userEntry.getAttributeValue("maxTxValueUSD") } returns "25000"
        every { userEntry.getAttributeValue("cn") } returns null
        every { userEntry.getAttributeValues("memberOf") } returns null
        every { userEntry.getAttributeValues("objectClass") } returns arrayOf("inetOrgPerson", "tradingAccount")
        every { userEntry.getAttributeValues("allowedChains") } returns arrayOf("base")
        every { userEntry.getAttributeValues("allowedExchanges") } returns arrayOf("hyperliquid")
        every { userEntry.getAttributeValues("allowedTradingModes") } returns arrayOf("forward_paper")
        every { userEntry.hasAttribute(any<String>()) } answers {
            firstArg<String>() in setOf(
                "allowedChains",
                "allowedExchanges",
                "allowedTradingModes",
                "maxTxPerHour",
                "maxTxValueUSD"
            )
        }

        val groupEntry = mockk<SearchResultEntry>()
        every { groupEntry.getAttributeValue("cn") } returns "admins"
        val userSearch = mockSearchResult(listOf(userEntry))
        val groupSearch = mockSearchResult(listOf(groupEntry))
        val emptySearch = mockSearchResult(emptyList())

        every { connection.search(any<SearchRequest>()) } answers {
            when (val request = firstArg<SearchRequest>().baseDN) {
                "dc=datamancy,dc=net" -> userSearch
                "ou=groups,dc=datamancy,dc=net" -> {
                    val filter = firstArg<SearchRequest>().filter.toString()
                    kotlin.test.assertTrue(filter.contains("(member=uid=trader1,ou=users,dc=datamancy,dc=net)"))
                    kotlin.test.assertTrue(filter.contains("(uniqueMember=uid=trader1,ou=users,dc=datamancy,dc=net)"))
                    groupSearch
                }
                else -> emptySearch
            }
        }

        setConnection(service, connection)

        val audit = service.getTradingAccountAudit("trader1")

        assertNotNull(audit)
        assertEquals(listOf("admins"), audit.groups)
    }

    @Test
    fun `health check reconnects after ldap restart`() {
        val staleConnection = mockk<LDAPConnection>(relaxed = true)
        every { staleConnection.isConnected } returns true
        every { staleConnection.rootDSE } throws LDAPException(ResultCode.SERVER_DOWN, "server down")

        val freshConnection = mockk<LDAPConnection>(relaxed = true)
        every { freshConnection.isConnected } returns true
        every { freshConnection.rootDSE } returns mockk(relaxed = true)

        var factoryCalls = 0
        val service = LdapService(
            host = "ldap",
            port = 389,
            baseDn = "dc=datamancy,dc=net",
            bindDn = "cn=admin,dc=datamancy,dc=net",
            bindPassword = "secret",
            connectionFactory = {
                factoryCalls += 1
                freshConnection
            }
        )

        setConnection(service, staleConnection)

        service.healthCheck()

        assertEquals(1, factoryCalls)
        verify(exactly = 1) { staleConnection.close() }
    }

    @Test
    fun `trading account audit reconnects when ldap socket is stale`() {
        val staleConnection = mockk<LDAPConnection>(relaxed = true)
        every { staleConnection.isConnected } returns true
        every { staleConnection.search(any<SearchRequest>()) } throws LDAPException(ResultCode.SERVER_DOWN, "server down")

        val userEntry = mockk<SearchResultEntry>()
        every { userEntry.dn } returns "uid=trader1,ou=users,dc=datamancy,dc=net"
        every { userEntry.getAttributeValue("uid") } returns "trader1"
        every { userEntry.getAttributeValue("mail") } returns "trader1@datamancy.net"
        every { userEntry.getAttributeValue("maxTxPerHour") } returns "100"
        every { userEntry.getAttributeValue("maxTxValueUSD") } returns "25000"
        every { userEntry.getAttributeValue("cn") } returns null
        every { userEntry.getAttributeValues("memberOf") } returns null
        every { userEntry.getAttributeValues("objectClass") } returns arrayOf("inetOrgPerson", "tradingAccount")
        every { userEntry.getAttributeValues("allowedChains") } returns arrayOf("base")
        every { userEntry.getAttributeValues("allowedExchanges") } returns arrayOf("hyperliquid")
        every { userEntry.getAttributeValues("allowedTradingModes") } returns arrayOf("forward_paper")
        every { userEntry.hasAttribute(any<String>()) } answers {
            firstArg<String>() in setOf(
                "allowedChains",
                "allowedExchanges",
                "allowedTradingModes",
                "maxTxPerHour",
                "maxTxValueUSD"
            )
        }

        val groupEntry = mockk<SearchResultEntry>()
        every { groupEntry.getAttributeValue("cn") } returns "admins"
        val userSearch = mockSearchResult(listOf(userEntry))
        val groupSearch = mockSearchResult(listOf(groupEntry))

        val freshConnection = mockk<LDAPConnection>(relaxed = true)
        every { freshConnection.isConnected } returns true
        every { freshConnection.search(any<SearchRequest>()) } answers {
            when (val request = firstArg<SearchRequest>().baseDN) {
                "dc=datamancy,dc=net" -> userSearch
                "ou=groups,dc=datamancy,dc=net" -> {
                    val filter = firstArg<SearchRequest>().filter.toString()
                    kotlin.test.assertTrue(filter.contains("(member=uid=trader1,ou=users,dc=datamancy,dc=net)"))
                    kotlin.test.assertTrue(filter.contains("(uniqueMember=uid=trader1,ou=users,dc=datamancy,dc=net)"))
                    groupSearch
                }

                else -> mockSearchResult(emptyList())
            }
        }

        var factoryCalls = 0
        val service = LdapService(
            host = "ldap",
            port = 389,
            baseDn = "dc=datamancy,dc=net",
            bindDn = "cn=admin,dc=datamancy,dc=net",
            bindPassword = "secret",
            connectionFactory = {
                factoryCalls += 1
                freshConnection
            }
        )

        setConnection(service, staleConnection)

        val audit = service.getTradingAccountAudit("trader1")

        assertNotNull(audit)
        assertEquals(listOf("admins"), audit.groups)
        assertEquals(1, factoryCalls)
        verify(exactly = 1) { staleConnection.close() }
    }

    private fun mockSearchResult(entries: List<SearchResultEntry>): SearchResult {
        return mockk<SearchResult>().also { result ->
            every { result.entryCount } returns entries.size
            every { result.searchEntries } returns entries
        }
    }

    private fun setConnection(service: LdapService, connection: LDAPConnection) {
        val field = LdapService::class.java.getDeclaredField("connection")
        field.isAccessible = true
        field.set(service, connection)
    }
}
