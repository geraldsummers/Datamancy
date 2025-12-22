import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.datamancy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-html-builder:2.3.12")

    // Ktor client for proxying
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Database
    implementation("org.postgresql:postgresql:42.7.4")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation(project(":test-commons"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("org.datamancy.controlpanel.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("control-panel")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

tasks.test {
    useJUnitPlatform()

    // Use localhost URLs since services are exposed via docker-compose overlay
    systemProperty("control.panel.url", System.getenv("CONTROL_PANEL_URL") ?: "http://localhost:18097")
    systemProperty("data.fetcher.url", System.getenv("DATA_FETCHER_URL") ?: "http://localhost:18095")
    systemProperty("postgres.url", System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:15432/datamancy")
    systemProperty("postgres.user", System.getenv("POSTGRES_USER") ?: "datamancer")
    systemProperty("postgres.password", System.getenv("STACK_ADMIN_PASSWORD") ?: "")
    systemProperty("clickhouse.url", System.getenv("CLICKHOUSE_URL") ?: "http://localhost:18123")

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

kotlin {
    jvmToolchain(21)
}
