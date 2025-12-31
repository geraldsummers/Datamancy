#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Directly triggers AU law fetcher by invoking it in the data-fetcher container.
 * This bypasses the need for an exposed API endpoint.
 */

val host = System.getenv("TARGET_HOST") ?: "latium.local"
val limitActs = System.getenv("LIMIT_ACTS") ?: "5"

println("=== AU Law Data Fetcher ===")
println("Target: $host")
println("Limit: $limitActs Acts")
println()

// Build a Kotlin script to run inside the container
val fetchScript = """
import org.datamancy.datafetcher.config.LegalConfig
import org.datamancy.datafetcher.fetchers.LegalDocsFetcher
import kotlinx.coroutines.runBlocking

println("Starting AU law fetch...")
val config = LegalConfig()
val fetcher = LegalDocsFetcher(config)

runBlocking {
    val result = fetcher.fetchToClickHouse(limitPerJurisdiction = $limitActs)
    println(result.message)
}
""".trimIndent()

// Run via ssh and docker exec
println("1. Triggering fetch inside data-fetcher container...")

val sshCommand = arrayOf(
    "ssh", "gerald@$host",
    "docker", "exec", "-i", "data-fetcher",
    "kotlin", "-J-Djava.security.manager=allow",
    "-cp", "/app/app.jar:/app/lib/*",
    "-e", fetchScript
)

println("Command: ${sshCommand.joinToString(" ")}")
println()

val process = ProcessBuilder(*sshCommand)
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

val exitCode = process.waitFor()
println()

if (exitCode == 0) {
    println("✓ Fetch completed successfully!")
} else {
    println("✗ Fetch failed with exit code: $exitCode")
    System.exit(exitCode)
}
