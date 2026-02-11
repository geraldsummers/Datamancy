#!/usr/bin/env kotlin

@file:DependsOn("org.datamancy:trading-sdk:1.0.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

/**
 * Web3 Wallet Trading Example
 *
 * This example demonstrates using MetaMask/Brave Wallet with the Datamancy Trading SDK
 * in a Jupyter notebook environment.
 *
 * Prerequisites:
 * 1. Install JupyterLab Web3 Wallet extension (included in Datamancy Jupyter image)
 * 2. Install MetaMask or Brave browser extension
 * 3. Connect wallet using %walletConnect magic command
 */

import org.datamancy.trading.*
import org.datamancy.trading.models.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal

runBlocking {
    println("🦊 Web3 Wallet Trading Example")
    println("=" * 50)

    // Step 1: Connect to trading gateway using Web3 wallet
    println("\n📡 Connecting to trading gateway with Web3 wallet...")

    val tx = try {
        TxGateway.fromWallet(
            url = System.getenv("TX_GATEWAY_URL") ?: "http://tx-gateway:8080",
            token = System.getenv("TX_AUTH_TOKEN") ?: throw IllegalStateException("TX_AUTH_TOKEN not set")
        )
    } catch (e: IllegalStateException) {
        println("\n❌ ${e.message}")
        println("\nTo connect your wallet:")
        println("1. Open JupyterLab command palette (Ctrl/Cmd+Shift+C)")
        println("2. Search for '🦊 Connect Web3 Wallet'")
        println("3. Or run in a cell: %walletConnect")
        println("4. Click 'Connect Wallet' and approve in MetaMask/Brave")
        return@runBlocking
    }

    println("\n✓ Successfully connected to trading gateway with Web3 wallet!")

    // Step 2: Check gateway health
    println("\n🏥 Checking gateway health...")
    when (val health = tx.health()) {
        is ApiResult.Success -> {
            println("✓ Gateway healthy: ${health.data}")
        }
        is ApiResult.Error -> {
            println("✗ Gateway unhealthy: ${health.message}")
            return@runBlocking
        }
    }

    // Step 3: Example - EVM Transfer
    // When executed, this will trigger a MetaMask popup for the user to approve
    println("\n💸 Example: EVM Transfer")
    println("This will prompt MetaMask/Brave for approval...")

    val transfer = tx.evm.transfer(
        toUser = "alice",
        amount = BigDecimal("10"),
        token = Token.USDC,
        chain = Chain.BASE
    )

    when (transfer) {
        is ApiResult.Success -> {
            val txData = transfer.data
            println("✓ Transfer approved and submitted!")
            println("  Tx Hash: ${txData.txHash}")
            println("  To User: ${txData.toUser}")
            println("  Amount: ${txData.amount} ${txData.token}")
            println("  Chain: ${txData.chain}")
            println("  Status: ${txData.status}")
            println("\n  View on explorer: https://basescan.org/tx/${txData.txHash}")
        }
        is ApiResult.Error -> {
            println("✗ Transfer failed: ${transfer.message}")
            println("  (User may have rejected the transaction in MetaMask)")
        }
    }

    // Step 4: Check Balance
    println("\n💰 Checking EVM Balance...")
    when (val balance = tx.evm.balance(Token.USDC, Chain.BASE)) {
        is ApiResult.Success -> {
            println("✓ Balance: ${balance.data} USDC on Base")
        }
        is ApiResult.Error -> {
            println("✗ Failed to get balance: ${balance.message}")
        }
    }

    // Step 5: Hyperliquid Example (if available)
    println("\n📊 Example: Hyperliquid Market Order")
    println("This will also prompt for approval...")

    val marketOrder = tx.hyperliquid.market(
        symbol = "ETH-PERP",
        side = Side.BUY,
        size = BigDecimal("0.01")
    )

    when (marketOrder) {
        is ApiResult.Success -> {
            val order = marketOrder.data
            println("✓ Order approved and filled!")
            println("  Order ID: ${order.orderId}")
            println("  Symbol: ${order.symbol}")
            println("  Size: ${order.size}")
            println("  Fill Price: ${order.fillPrice}")
        }
        is ApiResult.Error -> {
            println("✗ Order failed: ${marketOrder.message}")
        }
    }

    println("\n" + "=" * 50)
    println("✓ Web3 wallet trading example complete!")
    println("\n🔐 Your private keys never left your browser!")
    println("   All transactions were signed locally by MetaMask/Brave")
}
