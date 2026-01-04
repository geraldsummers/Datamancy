import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    // Ktor server
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.html.builder)

    // Ktor client for proxying
    implementation(libs.bundles.ktor.client)

    // Logging
    implementation(libs.bundles.logging)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Database
    implementation(libs.postgres.jdbc)

    // Tests
    testImplementation(libs.kotlin.test)
    testImplementation(project(":test-commons"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.datamancy.controlpanel.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("control-panel")
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts

    // Use localhost URLs since services are exposed via docker-compose overlay
    systemProperty("control.panel.url", System.getenv("CONTROL_PANEL_URL") ?: "http://localhost:${project.property("port.controlPanel.test")}")
    systemProperty("data.fetcher.url", System.getenv("DATA_FETCHER_URL") ?: "http://localhost:${project.property("port.dataFetcher.test")}")
    systemProperty("postgres.url", System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:${project.property("port.postgres.test")}/datamancy")
    systemProperty("postgres.user", System.getenv("POSTGRES_USER") ?: "datamancer")
    systemProperty("postgres.password", System.getenv("STACK_ADMIN_PASSWORD") ?: "")
    systemProperty("clickhouse.url", System.getenv("CLICKHOUSE_URL") ?: "http://localhost:${project.property("port.clickhouse.test")}")

    // Bring up the whole stack before running tests
    doFirst {
        // Check if stack is already running
        val checkResult = ByteArrayOutputStream()
        exec {
            workingDir = rootProject.projectDir
            commandLine("docker", "compose", "ps", "-q")
            standardOutput = checkResult
        }

        val isStackRunning = checkResult.toString().trim().isNotEmpty()

        if (isStackRunning) {
            println("\n✅ Stack is already running - skipping startup\n")
        } else {
            println("\n╔════════════════════════════════════════════════════════════════╗")
            println("║              Bringing up stack for tests                      ║")
            println("╚════════════════════════════════════════════════════════════════╝\n")

            val envFile = File(System.getProperty("user.home"), ".datamancy/.env")
            val testOverlay = rootProject.file("docker-compose.test-ports.yml")

            // Bring up the full stack with test overlay to expose ports
            // Ignore exit code - some services may fail but core services will be up
            exec {
                workingDir = rootProject.projectDir
                commandLine(
                    "docker", "compose",
                    "-f", "docker-compose.yml",
                    "-f", testOverlay.absolutePath,
                    "--env-file", envFile.absolutePath,
                    "up", "-d"
                )
                isIgnoreExitValue = true
            }

            println("✅ Stack is up\n")
        }
    }

    // Show test execution details
    testLogging {
        events("passed", "skipped", "failed", "started")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = false
    }

    // Show progress as tests run
    var testCount = 0
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        testCount++
        if (testCount % 10 == 0) {
            println("  ... $testCount tests completed ...")
        }
    }))

    // Print test summary
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) { // Root suite
            println("\n╔════════════════════════════════════════════════════════════════╗")
            println("║                    Tests Summary                    ║")
            println("╚════════════════════════════════════════════════════════════════╝")
            println("  Total:   ${result.testCount}")
            println("  ✓ Passed: ${result.successfulTestCount}")
            println("  ✗ Failed: ${result.failedTestCount}")
            println("  ⊘ Skipped: ${result.skippedTestCount}")
            println("  ⏱️  Duration: ${result.endTime - result.startTime}ms")

            if (result.failedTestCount > 0) {
                println("\n  ❌ Some tests failed!")
            } else {
                println("\n  ✅ All tests passed!")
            }
            println()
        }
    }))
}

// JVM toolchain configured in root build.gradle.kts
