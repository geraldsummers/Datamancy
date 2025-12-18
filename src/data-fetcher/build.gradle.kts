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

    // Ktor client for HTTP requests
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-client-logging:2.3.12")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // YAML config
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    // Database drivers
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.clickhouse:clickhouse-jdbc:0.6.5")
    implementation("com.clickhouse:clickhouse-http-client:0.6.5")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RSS parsing
    implementation("com.rometools:rome:2.1.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.11.0")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.18.1")

    // HTML to Markdown conversion
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // Date/time
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
}

application {
    mainClass.set("org.datamancy.datafetcher.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("data-fetcher")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

tasks.test {
    useJUnitPlatform {
        if (File("/.dockerenv").exists()) {
            // Inside Docker - run all tests (unit + integration)
            includeTags("integration", "unit")
        } else {
            // On host - only run unit tests, integration tests will be delegated to Docker
            excludeTags("integration")
        }
    }

    // Always use Docker service names (only works inside Docker network)
    systemProperty("postgres.url", System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://postgres:5432/datamancy")
    systemProperty("postgres.user", System.getenv("POSTGRES_USER") ?: "datamancer")
    systemProperty("postgres.password", System.getenv("STACK_ADMIN_PASSWORD") ?: "")
    systemProperty("clickhouse.url", System.getenv("CLICKHOUSE_URL") ?: "http://clickhouse:8123")
    systemProperty("clickhouse.user", System.getenv("CLICKHOUSE_USER") ?: "default")
    systemProperty("clickhouse.password", System.getenv("STACK_ADMIN_PASSWORD") ?: "")

    // Show test execution details
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        // Show progress
        showStandardStreams = true
    }

    // Print test summary
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) { // Root suite
            val inDocker = File("/.dockerenv").exists()
            val testType = if (inDocker) "All Tests" else "Unit Tests"

            println("\n╔════════════════════════════════════════════════════════════════╗")
            println("║                    $testType Summary                    ║")
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

    // On host, automatically delegate integration tests to Docker after unit tests complete
    if (!File("/.dockerenv").exists()) {
        doFirst {
            println("\n╔════════════════════════════════════════════════════════════════╗")
            println("║                    Running Unit Tests                         ║")
            println("╚════════════════════════════════════════════════════════════════╝")
        }
        finalizedBy("runIntegrationTestsInDocker")
    } else {
        doFirst {
            println("\n╔════════════════════════════════════════════════════════════════╗")
            println("║              Running All Tests (Unit + Integration)           ║")
            println("╚════════════════════════════════════════════════════════════════╝")
        }
    }
}

// Task to run integration tests inside Docker network
val runIntegrationTestsInDocker by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs @IntegrationTest annotated tests inside Docker network"

    // Always run - integration tests are important
    onlyIf { true }

    val runtimeEnvFile = File(System.getProperty("user.home"), ".datamancy/.env.runtime")
    doFirst {
        // Generate .env.runtime if it doesn't exist
        if (!runtimeEnvFile.exists()) {
            println("\n╔═══════════════════════════════════════════════════════╗")
            println("║   Generating credentials via stack-controller...     ║")
            println("╚═══════════════════════════════════════════════════════╝\n")

            val result = exec {
                workingDir = rootProject.projectDir
                commandLine("./stack-controller.main.kts", "config", "generate")
                isIgnoreExitValue = true
            }

            if (result.exitValue != 0 || !runtimeEnvFile.exists()) {
                throw GradleException("""
                    Failed to generate environment configuration.
                    Try manually: ./stack-controller.main.kts config generate
                """.trimIndent())
            }
        }

        println("\n╔═══════════════════════════════════════════════════════╗")
        println("║   Running @IntegrationTest tests in Docker network   ║")
        println("╚═══════════════════════════════════════════════════════╝\n")
    }

    environment("GRADLE_USER_HOME", "/tmp/gradle-integration-test")

    // No volumes are mounted, so running as root in container is safe
    commandLine(
        "docker", "compose",
        "--env-file", runtimeEnvFile.absolutePath,
        "run", "--rm",
        "-e", "GRADLE_USER_HOME=/tmp/gradle-integration-test",
        "integration-test-runner",
        "./gradlew", ":${project.name}:test", "--no-daemon"
    )
}

kotlin {
    jvmToolchain(21)
}
