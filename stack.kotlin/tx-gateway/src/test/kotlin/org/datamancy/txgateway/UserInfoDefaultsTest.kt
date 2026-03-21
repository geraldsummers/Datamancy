package org.datamancy.txgateway

import org.datamancy.txgateway.models.UserInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class UserInfoDefaultsTest {
    @Test
    fun `user info default trading modes stay off mainnet`() {
        val userInfo = UserInfo(
            username = "trader1",
            email = "trader1@datamancy.net",
            groups = listOf("traders"),
            evmAddress = null,
            allowedChains = listOf("base"),
            allowedExchanges = listOf("hyperliquid"),
            maxTxPerHour = 100,
            maxTxValueUSD = 25_000
        )

        assertEquals(
            listOf("backtest", "forward_paper", "testnet_live"),
            userInfo.allowedTradingModes
        )
    }
}
