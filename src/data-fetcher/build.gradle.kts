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
        // Integration tests MUST run inside Docker network
        // Unit tests can run anywhere
        if (File("/.dockerenv").exists()) {
            // We're inside Docker - run all tests
            includeTags("integration", "unit")
        } else {
            // We're on host - only run unit tests, delegate integration tests to Docker
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

    // If running on host, automatically delegate integration tests to Docker
    if (!File("/.dockerenv").exists()) {
        finalizedBy("runIntegrationTestsInDocker")
    }
}

// Task to run integration tests inside Docker network
val runIntegrationTestsInDocker by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs @IntegrationTest annotated tests inside Docker network"

    // Only run if there are integration tests to run
    onlyIf {
        // Check if any tests were skipped due to integration tag
        val testResults = tasks.test.get().reports.junitXml.outputLocation.get().asFile
        testResults.exists() && testResults.walk().any {
            it.extension == "xml" && it.readText().contains("skipped")
        }
    }

    val envFile = rootProject.file(".env")
    doFirst {
        if (!envFile.exists()) {
            throw GradleException("""
                Integration tests require Docker network access.
                Run './stack-controller.main.kts up' first to start services.
            """.trimIndent())
        }

        println("\n╔═══════════════════════════════════════════════════════╗")
        println("║   Running @IntegrationTest tests in Docker network   ║")
        println("╚═══════════════════════════════════════════════════════╝\n")
    }

    commandLine(
        "docker", "compose",
        "--env-file", envFile.absolutePath,
        "--profile", "testing",
        "run", "--rm",
        "integration-test-runner",
        "gradle", ":${project.name}:test", "--tests", "*", "--no-daemon"
    )
}

kotlin {
    jvmToolchain(21)
}
