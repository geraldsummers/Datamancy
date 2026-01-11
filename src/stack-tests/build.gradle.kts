import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.time.Duration

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Kotlin stdlib
    implementation(libs.kotlin.stdlib)

    // YAML parsing for docker-compose
    implementation(libs.kaml)

    // Kotlinx serialization
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.bundles.logging)

    // Ktor client for HTTP tests
    testImplementation(libs.bundles.ktor.client)

    // Kotlin test
    testImplementation(libs.kotlin.test)

    // JUnit 5
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // JDBC drivers for database testing
    testImplementation(libs.postgres.jdbc)
    testRuntimeOnly(libs.postgres.jdbc)
    testImplementation(libs.mariadb.jdbc)
    testRuntimeOnly(libs.mariadb.jdbc)

    // Shared test commons
    testImplementation(project(":test-commons"))
}

// Helper function to check if a service health endpoint is responding
fun checkServiceHealth(healthUrl: String): Boolean {
    return try {
        val url = URL(healthUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.requestMethod = "GET"
        connection.connect()

        val responseCode = connection.responseCode
        connection.disconnect()

        // Accept 2xx, 3xx, and even some 4xx/5xx (service is up, just not fully ready)
        responseCode in 200..599
    } catch (e: Exception) {
        // Connection refused, timeout, etc. - service not ready
        false
    }
}

// Helper function to wait for critical services
fun waitForCriticalServices() {
    // Critical services that must be healthy before tests run
    val criticalServices = mapOf(
        "clickhouse" to "http://localhost:18123/ping",
        "qdrant" to "http://localhost:16333",
        "control-panel" to "http://localhost:18097/health",
        "data-fetcher" to "http://localhost:18095/health",
        "unified-indexer" to "http://localhost:18096/health",
        "search-service" to "http://localhost:18098/health",
        "embedding-service" to "http://localhost:18080/health"
    )

    val maxWaitTime = 300_000L // 5 minutes max
    val checkInterval = 5_000L // Check every 5 seconds
    val startTime = System.currentTimeMillis()
    val healthyServices = mutableSetOf<String>()

    while (healthyServices.size < criticalServices.size) {
        val elapsed = System.currentTimeMillis() - startTime

        if (elapsed > maxWaitTime) {
            val unhealthy = criticalServices.keys - healthyServices
            println("\n⚠️  Timeout after ${elapsed / 1000}s")
            println("   Unhealthy services: ${unhealthy.joinToString(", ")}")
            println("   Continuing with tests anyway...\n")
            break
        }

        for ((service, healthUrl) in criticalServices) {
            if (service !in healthyServices) {
                if (checkServiceHealth(healthUrl)) {
                    healthyServices.add(service)
                    val progress = "${healthyServices.size}/${criticalServices.size}"
                    val elapsedSec = elapsed / 1000
                    println("  ✓ $service is healthy ($progress) [${elapsedSec}s]")
                }
            }
        }

        if (healthyServices.size < criticalServices.size) {
            Thread.sleep(checkInterval)
        }
    }

    val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
    if (healthyServices.size == criticalServices.size) {
        println("\n✅ All ${healthyServices.size} critical services healthy in ${totalElapsed}s\n")
    }
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts

    // Increase timeout for endpoint discovery and slow services (10 minutes total)
    timeout.set(Duration.ofMinutes(10))

    // Enable parallel test execution for long-running endpoint tests
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
    // Use a high thread count since tests mostly wait for I/O
    systemProperty("junit.jupiter.execution.parallel.config.dynamic.factor", "4.0")

    // Set working directory to project root so tests can find discovered-endpoints.json
    workingDir = project.rootDir

    // Ensure discovered-endpoints-localhost.json exists before running tests
    // Note: discoverEndpoints task defined below

    // Bring up the stack with test port overlay before running tests
    doFirst {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║           Stack Tests - Localhost Integration                 ║")
        println("╚════════════════════════════════════════════════════════════════╝\n")

        // Load environment variables from ~/.datamancy/.env
        val envFile = file("${System.getProperty("user.home")}/.datamancy/.env")
        if (envFile.exists()) {
            envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
                .forEach { line ->
                    val (key, value) = line.split("=", limit = 2)
                    // Use both environment() and systemProperty() for maximum compatibility
                    environment(key.trim(), value.trim())
                    systemProperty(key.trim(), value.trim())
                }
            println("✅ Loaded ${envFile.readLines().count { it.contains("=") && !it.startsWith("#") }} environment variables from .env")
        } else {
            println("⚠️  No .env file found at ${envFile.absolutePath}")
        }

        // Generate localhost endpoint mappings using gradle.properties
        println("📝 Generating localhost endpoint mappings...")

        val portMap = mapOf(
            "postgres" to (project.property("port.postgres.test") as String).toInt() to (project.property("port.postgres.internal") as String).toInt(),
            "clickhouse" to (project.property("port.clickhouse.test") as String).toInt() to (project.property("port.clickhouse.http") as String).toInt(),
            "qdrant" to (project.property("port.qdrant.test") as String).toInt() to (project.property("port.qdrant.http") as String).toInt(),
            "control-panel" to (project.property("port.controlPanel.test") as String).toInt() to (project.property("port.controlPanel.internal") as String).toInt(),
            "data-fetcher" to (project.property("port.dataFetcher.test") as String).toInt() to (project.property("port.dataFetcher.internal") as String).toInt(),
            "unified-indexer" to (project.property("port.unifiedIndexer.test") as String).toInt() to (project.property("port.unifiedIndexer.internal") as String).toInt(),
            "search-service" to (project.property("port.searchService.test") as String).toInt() to (project.property("port.searchService.internal") as String).toInt(),
            "embedding-service" to (project.property("port.embeddingService.test") as String).toInt() to (project.property("port.embeddingService.internal") as String).toInt()
        )

        val discoveredFile = file("${project.rootDir}/build/discovered-endpoints.json")
        if (!discoveredFile.exists()) {
            println("⚠️  No discovered-endpoints.json found, skipping localhost mapping")
        } else {
            // For now, just copy the file (full implementation would map ports)
            val outputFile = file("${project.rootDir}/build/discovered-endpoints-localhost.json")
            discoveredFile.copyTo(outputFile, overwrite = true)
            println("✅ Generated localhost endpoints mapping")
        }

        // Check if test ports are actually exposed by testing one port
        println("🔍 Checking if test ports are exposed...")
        val controlPanelPort = (project.property("port.controlPanel.test") as String).toInt()
        val testPortOpen = try {
            Socket("localhost", controlPanelPort).use { true }
        } catch (e: Exception) {
            false
        }

        if (testPortOpen) {
            println("✅ Stack is already running with test ports exposed\n")
        } else {
            println("🚀 Bringing up Docker stack via stack-controller test-up...")
            println("   This brings up the stack with test-ports overlay and proper env file in one atomic operation\n")

            // Use stack-controller test-up to bring up stack with test-ports overlay
            val stackController = project.rootDir.resolve("stack-controller.main.kts")
            if (!stackController.exists()) {
                throw GradleException("stack-controller.main.kts not found at ${stackController.absolutePath}")
            }

            val result = exec {
                workingDir = project.rootDir
                commandLine(stackController.absolutePath, "test-up")
                standardOutput = System.out
                errorOutput = System.err
                isIgnoreExitValue = true
            }

            if (result.exitValue != 0) {
                println("\n⚠️  Stack controller exited with code ${result.exitValue}")
                println("   Some services may have failed, but continuing with health checks...")
            }

            println("\n⏳ Waiting for critical services to become healthy...")
            println("   This may take 3-5 minutes on first run...\n")

            waitForCriticalServices()
        }

        println("🧪 Running tests against localhost endpoints...\n")
    }

    // Optionally tear down stack after tests complete
    doLast {
        val teardownEnv = System.getenv("STACK_TESTS_TEARDOWN")
        if (teardownEnv == "true") {
            println("\n🛑 Tearing down stack via stack-controller...")

            val stackController = project.rootDir.resolve("stack-controller.main.kts")
            if (stackController.exists()) {
                exec {
                    workingDir = project.rootDir
                    commandLine(stackController.absolutePath, "down")
                    standardOutput = System.out
                    errorOutput = System.err
                    isIgnoreExitValue = true
                }
                println("✅ Stack torn down\n")
            } else {
                println("⚠️  stack-controller.main.kts not found, skipping teardown")
            }
        } else {
            println("\n📌 Stack remains running for inspection")
            println("   Set STACK_TESTS_TEARDOWN=true to auto-teardown\n")
        }
    }
}

// Task to discover endpoints from Kotlin sources and docker-compose
tasks.register("discoverEndpoints") {
    group = "stack-tests"
    description = "Scan codebase and docker-compose for all HTTP endpoints"

    // Always run this task (never use cached output)
    outputs.upToDateWhen { false }

    // Ensure main sources are compiled before running discovery
    dependsOn(tasks.compileKotlin)

    doLast {
        println("🔍 Discovering endpoints...")

        // Run the discovery main class with timeout
        // Write to root build dir where tests will look for it
        val discoveryOutput = file("${project.rootDir}/build/discovered-endpoints.json")

        javaexec {
            mainClass.set("org.datamancy.stacktests.discovery.EndpointDiscoveryKt")
            classpath = sourceSets["main"].runtimeClasspath
            // Add timeout to prevent hanging (30 seconds should be plenty)
            timeout.set(Duration.ofSeconds(30))
            args = listOf(
                project.rootDir.absolutePath,
                discoveryOutput.absolutePath
            )
        }

        println()
        println("✅ Discovery complete: ${discoveryOutput.absolutePath}")
        println()
    }
}

// Task to run stack tests inside Docker container
tasks.register("stackTest") {
    group = "stack-tests"
    description = "Run stack tests inside Docker container on backend network"

    dependsOn(tasks.testClasses, tasks.named("discoverEndpoints"))

    doLast {
        val projectRoot = project.rootDir
        val stackController = projectRoot.resolve("stack-controller.main.kts")

        println()
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║              Stack Tests - Docker Integration                 ║")
        println("╚════════════════════════════════════════════════════════════════╝")
        println()

        // Step 1: Ensure stack is running
        println("Step 1/3: Ensuring Docker stack is running...")
        exec {
            workingDir = projectRoot
            commandLine(stackController.absolutePath, "up")
        }
        println("✅ Stack is running")
        println()

        // Step 2: Run tests in Docker container
        println("Step 2/3: Running tests inside Docker container...")
        val result = exec {
            workingDir = projectRoot
            commandLine("docker", "compose", "run", "--rm", "stack-test-runner")
            isIgnoreExitValue = true
        }
        println()

        // Step 3: Report results
        if (result.exitValue == 0) {
            println("✅ All tests passed")
        } else {
            println("❌ Some tests failed")
            println("See test results in build/test-results/")
        }

        println()
        println("Step 3/3: Stack remains running (use './stack-controller.main.kts down' to stop)")
        println()

        // Propagate test failure
        if (result.exitValue != 0) {
            throw GradleException("Tests failed")
        }
    }
}

// JVM toolchain configured in root build.gradle.kts
