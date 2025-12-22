import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.time.Duration

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
    useJUnitPlatform()

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
    dependsOn(tasks.named("discoverEndpoints"))

    // Bring up the stack with test port overlay before running tests
    doFirst {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║           Stack Tests - Localhost Integration                 ║")
        println("╚════════════════════════════════════════════════════════════════╝\n")

        // Generate localhost endpoint mappings
        println("📝 Generating localhost endpoint mappings...")
        exec {
            workingDir = project.rootDir
            commandLine("python3", "-c", """
import json

# Service port mappings (localhost_port -> internal_port)
port_map = {
    'postgres': (15432, 5432),
    'clickhouse': (18123, 8123),
    'qdrant': (16333, 6333),
    'couchdb': (15984, 5984),
    'control-panel': (18097, 8097),
    'data-fetcher': (18095, 8095),
    'unified-indexer': (18096, 8096),
    'search-service': (18098, 8097),
    'embedding-service': (18080, 8080),
    'authelia': (19091, 9091),
    'benthos': (14195, 4195),
    'docker-proxy': (12375, 2375),
    'bookstack': (10080, 80),
    'forgejo': (13000, 3000),
    'grafana': (13001, 3000),
    'homepage': (13002, 3000),
    'jupyterhub': (18000, 8000),
    'open-webui': (18081, 8080),
    'planka': (11337, 1337),
    'vaultwarden': (10081, 80),
    'element': (10082, 80),
    'synapse': (18008, 8008),
    'mastodon-web': (13003, 3000),
    'mastodon-streaming': (14000, 4000),
    'roundcube': (10083, 80),
    'seafile': (18001, 8000),
    'onlyoffice': (10084, 80),
    'radicale': (15232, 5232),
    'homeassistant': (18124, 8123),
    'qbittorrent': (18082, 8080),
    'ldap-account-manager': (10085, 80),
    'litellm': (14001, 4000),
    'vllm': (18002, 8000),
}

# Read discovered endpoints and transform to localhost URLs
with open('build/discovered-endpoints.json') as f:
    data = json.load(f)

for service in data['services']:
    name = service['name']
    if name in port_map:
        localhost_port, internal_port = port_map[name]
        service['baseUrl'] = f"http://localhost:{localhost_port}"
        for endpoint in service['endpoints']:
            endpoint['serviceUrl'] = f"http://localhost:{localhost_port}"

# Write transformed endpoints
with open('build/discovered-endpoints-localhost.json', 'w') as f:
    json.dump(data, f, indent=4)

print(f"✅ Generated localhost endpoints: {sum(len(s['endpoints']) for s in data['services'])} endpoints across {len(data['services'])} services")
""")
        }

        // Check if test ports are actually exposed by testing one port
        println("🔍 Checking if test ports are exposed...")
        val testPortOpen = try {
            Socket("localhost", 18097).use { true }
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

kotlin {
    jvmToolchain(21)
}
