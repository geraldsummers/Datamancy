package org.datamancy.txgateway

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.datamancy.txgateway.models.UserInfo
import org.datamancy.txgateway.services.CredentialResolver
import org.datamancy.txgateway.services.LdapService

class CredentialResolverTest {

    @Test
    fun resolvesTestnetKeyWithExplicitAccountAddressFromEnv() {
        val ldapService = mockk<LdapService>(relaxed = true)
        every { ldapService.getHyperliquidKeyRef("sysadmin") } returns null

        val env = mapOf(
            "HYPERLIQUID_TESTNET_KEY" to "0xprivate",
            "HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS" to "0xAccount"
        )
        val resolver = CredentialResolver(
            ldapService = ldapService,
            envProvider = { env[it] },
            credentialStoreFile = null,
            defaultHyperliquidExecutionMode = "testnet_live"
        )

        assertEquals(
            "0xAccount:0xprivate",
            resolver.resolveHyperliquidCredential(username = "sysadmin", providedCredential = null, executionMode = "testnet_live")
        )
    }

    @Test
    fun preservesAlreadyQualifiedHyperliquidCredential() {
        val ldapService = mockk<LdapService>(relaxed = true)
        every { ldapService.getHyperliquidKeyRef("sysadmin") } returns null

        val env = mapOf(
            "HYPERLIQUID_TESTNET_KEY" to "0xAccount:0xprivate",
            "HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS" to "0xIgnored"
        )
        val resolver = CredentialResolver(
            ldapService = ldapService,
            envProvider = { env[it] },
            credentialStoreFile = null,
            defaultHyperliquidExecutionMode = "testnet_live"
        )

        assertEquals(
            "0xAccount:0xprivate",
            resolver.resolveHyperliquidCredential(username = "sysadmin", providedCredential = null, executionMode = "testnet_live")
        )
    }

    @Test
    fun composesLdapKeyRefWithLinkedWalletAddress() {
        val ldapService = mockk<LdapService>(relaxed = true)
        every { ldapService.getHyperliquidKeyRef("sysadmin") } returns "TRADER_KEY"
        every { ldapService.getUserInfo("sysadmin") } returns UserInfo(
            username = "sysadmin",
            email = "sysadmin@datamancy.net",
            groups = emptyList(),
            evmAddress = "0xWallet",
            allowedChains = emptyList(),
            allowedExchanges = emptyList(),
            allowedTradingModes = emptyList(),
            maxTxPerHour = 0,
            maxTxValueUSD = 0
        )

        val env = mapOf("TRADER_KEY" to "0xprivate")
        val resolver = CredentialResolver(
            ldapService = ldapService,
            envProvider = { env[it] },
            credentialStoreFile = null,
            defaultHyperliquidExecutionMode = "testnet_live"
        )

        assertEquals(
            "0xWallet:0xprivate",
            resolver.resolveHyperliquidCredential(username = "sysadmin", providedCredential = null, executionMode = "testnet_live")
        )
    }

    @Test
    fun returnsNullWhenNoCredentialExists() {
        val ldapService = mockk<LdapService>(relaxed = true)
        every { ldapService.getHyperliquidKeyRef("sysadmin") } returns null

        val resolver = CredentialResolver(
            ldapService = ldapService,
            envProvider = { null },
            credentialStoreFile = null,
            defaultHyperliquidExecutionMode = "testnet_live"
        )

        assertNull(resolver.resolveHyperliquidCredential(username = "sysadmin", providedCredential = null, executionMode = "testnet_live"))
    }
}
