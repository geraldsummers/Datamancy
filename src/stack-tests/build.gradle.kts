plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "org.datamancy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin stdlib
    implementation(kotlin("stdlib"))

    // YAML parsing for docker-compose
    implementation("com.charleskorn.kaml:kaml:0.55.0")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.+")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.+")
    implementation("ch.qos.logback:logback-classic:1.4.+")

    // Ktor client for HTTP tests
    testImplementation("io.ktor:ktor-client-core:2.3.+")
    testImplementation("io.ktor:ktor-client-cio:2.3.+")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.+")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:2.3.+")

    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.+")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.+")
}

tasks.test {
    useJUnitPlatform()

    // Set working directory to project root so tests can find discovered-endpoints.json
    workingDir = project.rootDir
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

        // Run the discovery main class
        // Write to root build dir where tests will look for it
        val discoveryOutput = file("${project.rootDir}/build/discovered-endpoints.json")

        javaexec {
            mainClass.set("org.datamancy.stacktests.discovery.EndpointDiscoveryKt")
            classpath = sourceSets["main"].runtimeClasspath
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

kotlin {
    jvmToolchain(21)
}
