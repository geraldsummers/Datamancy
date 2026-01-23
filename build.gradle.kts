plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.github.johnrengelman.shadow")

    group = "org.datamancy"
    version = "1.0-SNAPSHOT"

    dependencies {
        // Common dependencies for all subprojects can go here
    }

    // Configure JVM toolchain for all subprojects
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    // Enforce warnings as errors for Kotlin compilation
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            allWarningsAsErrors = true
        }
    }

    // Configure test tasks for all subprojects
    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Configure shadow JAR for application modules
    tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

// Root-level test task runs unit tests only (no Docker required)
tasks.register("test") {
    dependsOn(
        ":agent-tool-server:test",
        ":search-service:test",
    )
    // Note: test-commons and test-runner have no unit tests (test-runner is for integration only)
}

// Build test-runner container for integration tests
tasks.register("buildTestRunner") {
    group = "verification"
    description = "Build the integration test runner Docker image"
    doLast {
        exec {
            commandLine("docker", "build", "-f", "Dockerfile.test-runner", "-t", "datamancy/test-runner:latest", ".")
        }
    }
}

// Run integration tests via Docker Compose
tasks.register("integrationTest") {
    group = "verification"
    description = "Run integration tests inside Docker stack (requires stack to be running)"
    doLast {
        println("""
            ╔═══════════════════════════════════════════════════════════════════════════╗
            ║  To run integration tests, use Docker Compose:                           ║
            ╚═══════════════════════════════════════════════════════════════════════════╝

            # Run all tests:
            docker compose --profile testing run --rm integration-test-runner

            # Run specific suite:
            docker compose --profile testing run --rm integration-test-runner foundation
            docker compose --profile testing run --rm integration-test-runner docker
            docker compose --profile testing run --rm integration-test-runner llm
            docker compose --profile testing run --rm integration-test-runner knowledge-base
            docker compose --profile testing run --rm integration-test-runner data-pipeline
            docker compose --profile testing run --rm integration-test-runner e2e

            # Debug mode:
            docker compose --profile testing run --rm integration-test-runner bash
        """.trimIndent())
    }
}