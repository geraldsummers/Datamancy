#!/usr/bin/env kotlin

@file:DependsOn("org.datamancy:trading-sdk:1.0.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

/**
 * Example Trading Script
 * Demonstrates the Datamancy Trading SDK
 */

import org.datamancy.trading.*
import org.datamancy.trading.models.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal

runBlocking {
    // Initialize gateway from environment variables
    // TX_GATEWAY_URL and TX_AUTH_TOKEN must be set
    val tx = TxGateway.fromEnv()

    println("🚀 Datamancy Trading Example")
    println("=" * 50)

    // Check health
    val health = tx.health()
    if (health is ApiResult.Success) {
        println("✓ Gateway healthy: ${health.data}")
    }

    // Example 1: Hyperliquid Market Order
    println("\n📊 Example 1: Hyperliquid Market Order")
    val marketOrder = tx.hyperliquid.market(
        symbol = "ETH-PERP",
        side = Side.BUY,
        size = BigDecimal("0.1")
    )

    when (marketOrder) {
        is ApiResult.Success -> {
            val order = marketOrder.data
            println("✓ Order filled!")
            println("  Order ID: ${order.orderId}")
            println("  Symbol: ${order.symbol}")
            println("  Size: ${order.size}")
            println("  Fill Price: ${order.fillPrice}")
        }
        is ApiResult.Error -> {
            println("✗ Order failed: ${marketOrder.message}")
        }
    }

    // Example 2: Hyperliquid Limit Order
    println("\n📊 Example 2: Hyperliquid Limit Order")
    val limitOrder = tx.hyperliquid.limit(
        symbol = "BTC-PERP",
        side = Side.SELL,
        size = BigDecimal("0.01"),
        price = BigDecimal("50000"),
        postOnly = true
    )

    when (limitOrder) {
        is ApiResult.Success -> {
            val order = limitOrder.data
            println("✓ Limit order placed!")
            println("  Order ID: ${order.orderId}")
            println("  Price: ${order.price}")
        }
        is ApiResult.Error -> {
            println("✗ Order failed: ${limitOrder.message}")
        }
    }

    // Example 3: Check Positions
    println("\n📈 Example 3: Current Positions")
    val positions = tx.hyperliquid.positions()

    when (positions) {
        is ApiResult.Success -> {
            if (positions.data.isEmpty()) {
                println("  No open positions")
            } else {
                positions.data.forEach { position ->
                    println("  ${position.symbol}:")
                    println("    Size: ${position.size}")
                    println("    Entry: ${position.entryPrice}")
                    println("    Mark: ${position.markPrice}")
                    println("    PnL: ${position.unrealizedPnl} (${position.pnlPercent}%)")
                }
            }
        }
        is ApiResult.Error -> {
            println("✗ Failed to get positions: ${positions.message}")
        }
    }

    // Example 4: EVM Transfer (user-to-user)
    println("\n💸 Example 4: EVM Transfer to Another User")
    val transfer = tx.evm.transfer(
        toUser = "alice",  // LDAP username - gateway resolves to EVM address
        amount = BigDecimal("100"),
        token = Token.USDC,
        chain = Chain.BASE
    )

    when (transfer) {
        is ApiResult.Success -> {
            val tx = transfer.data
            println("✓ Transfer submitted!")
            println("  Tx Hash: ${tx.txHash}")
            println("  To User: ${tx.toUser}")
            println("  Amount: ${tx.amount} ${tx.token}")
            println("  Chain: ${tx.chain}")
            println("  Status: ${tx.status}")
        }
        is ApiResult.Error -> {
            println("✗ Transfer failed: ${transfer.message}")
        }
    }

    // Example 5: Check Balance
    println("\n💰 Example 5: Check EVM Balance")
    val balance = tx.evm.balance(Token.USDC, Chain.BASE)

    when (balance) {
        is ApiResult.Success -> {
            println("✓ Balance: ${balance.data} USDC on Base")
        }
        is ApiResult.Error -> {
            println("✗ Failed to get balance: ${balance.message}")
        }
    }

    // Example 6: Address Book Lookup
    println("\n📖 Example 6: Address Book")
    val aliceAddress = tx.evm.addressBook("alice")

    when (aliceAddress) {
        is ApiResult.Success -> {
            println("✓ Alice's EVM address: ${aliceAddress.data}")
        }
        is ApiResult.Error -> {
            println("✗ Lookup failed: ${aliceAddress.message}")
        }
    }

    println("\n" + "=" * 50)
    println("✓ Trading examples complete!")
}
