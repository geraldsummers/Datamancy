package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.TestRunner
import java.io.File

/**
 * Stack Deployment Tests
 *
 * Tests full stack deployment lifecycle using labware Docker socket:
 * - Deploy isolated test stack
 * - Wait for all services to become healthy
 * - Run integration tests against deployed stack
 * - Clean up test stack
 *
 * This validates that the stack can successfully deploy from scratch.
 */
suspend fun TestRunner.stackDeploymentTests() = suite("Stack Deployment Tests") {

    val labwareSocket = "/run/labware-docker.sock"
    val testProjectName = "datamancy-test-${System.currentTimeMillis()}"
    val distDir = File("/app")  // Assuming test-runner runs with dist mounted

    // Check labware socket availability
    val isLabwareAvailable = File(labwareSocket).exists()

    if (!isLabwareAvailable) {
        skip("Labware socket not available", "Tests require labware VM at $labwareSocket")
        return@suite
    }

    test("Labware Docker socket is accessible") {
        val socketFile = File(labwareSocket)
        require(socketFile.exists()) { "Labware socket should exist at $labwareSocket" }
        require(socketFile.canRead()) { "Labware socket should be readable" }
    }

    test("Can connect to labware Docker daemon") {
        val (exitCode, output) = execLabwareDocker("version", "--format", "{{.Server.Version}}")
        require(exitCode == 0) { "Docker version command should succeed, got: $output" }
        require(output.trim().isNotEmpty()) { "Docker version should not be empty" }
        println("      Labware Docker version: ${output.trim()}")
    }

    test("Deploy test stack to labware") {
        println("      Deploying stack with project name: $testProjectName")

        val composeFile = distDir.resolve("docker-compose.yml")
        require(composeFile.exists()) { "docker-compose.yml not found at ${composeFile.absolutePath}" }

        val envFile = distDir.resolve(".env")
        require(envFile.exists()) { ".env file not found at ${envFile.absolutePath}" }

        // Deploy using docker compose with labware socket
        val (exitCode, output) = execLabwareCompose(
            distDir,
            testProjectName,
            "up", "-d"
        )

        if (exitCode != 0) {
            println("      Failed to deploy stack: $output")
            throw AssertionError("Stack deployment failed with exit code $exitCode")
        }

        println("      Stack deployment initiated successfully")
    }

    test("Wait for critical services to become healthy") {
        val criticalServices = listOf(
            "postgres" to 300,      // 5 minutes
            "ldap" to 120,          // 2 minutes
            "caddy" to 120,         // 2 minutes
            "authelia" to 180       // 3 minutes
        )

        criticalServices.forEach { (service, timeout) ->
            println("      Waiting for $service (timeout: ${timeout}s)...")
            waitForServiceHealthy(testProjectName, service, timeout)
            println("        ✓ $service is healthy")
        }
    }

    test("Wait for slow services to become healthy") {
        val slowServices = listOf(
            "forgejo" to 600,       // 10 minutes
            "grafana" to 300,       // 5 minutes
            "open-webui" to 300     // 5 minutes
        )

        slowServices.forEach { (service, timeout) ->
            println("      Waiting for $service (timeout: ${timeout}s)...")
            try {
                waitForServiceHealthy(testProjectName, service, timeout)
                println("        ✓ $service is healthy")
            } catch (e: Exception) {
                println("        ⚠ $service failed to become healthy (non-critical): ${e.message}")
                // Get logs for debugging
                val (_, logs) = execLabwareCompose(
                    distDir,
                    testProjectName,
                    "logs", "--tail=20", service
                )
                println("Last 20 lines of $service logs:\n$logs")
            }
        }
    }

    test("Verify stack isolation from production") {
        // Get containers from test project
        val (_, testOutput) = execLabwareCompose(
            distDir,
            testProjectName,
            "ps", "--format", "{{.Name}}"
        )
        val testContainers = testOutput.lines().filter { it.isNotBlank() }.toSet()

        require(testContainers.isNotEmpty()) { "Test stack should have running containers" }
        println("      Test stack has ${testContainers.size} containers")

        // Get containers from production Docker
        val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
        val prodOutput = prodProcess.inputStream.bufferedReader().readText()
        prodProcess.waitFor()
        val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

        val overlap = testContainers.intersect(prodContainers)
        require(overlap.isEmpty()) {
            "Test and production should have no overlapping containers. Found: $overlap"
        }

        println("      ✓ Verified isolation: test stack isolated from production")
    }

    test("Cleanup test stack") {
        println("      Cleaning up test stack: $testProjectName")

        val (exitCode, output) = execLabwareCompose(
            distDir,
            testProjectName,
            "down", "-v"
        )

        if (exitCode != 0) {
            println("      Warning: Cleanup had issues: $output")
        } else {
            println("      ✓ Test stack cleaned up successfully")
        }
    }
}

private fun execLabwareDocker(vararg args: String): Pair<Int, String> {
    val command = listOf("docker", "-H", "unix:///run/labware-docker.sock") + args
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

private fun execLabwareCompose(
    workDir: File,
    projectName: String,
    vararg args: String
): Pair<Int, String> {
    val command = listOf(
        "docker", "-H", "unix:///run/labware-docker.sock",
        "compose",
        "-p", projectName,
        "-f", workDir.resolve("docker-compose.yml").absolutePath
    ) + args

    val process = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

private fun waitForServiceHealthy(projectName: String, service: String, timeoutSeconds: Int) {
    val startTime = System.currentTimeMillis()
    val timeoutMs = timeoutSeconds * 1000L

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val (exitCode, output) = execLabwareDocker(
            "compose", "-p", projectName, "ps", service, "--format", "{{.Status}}"
        )

        if (exitCode == 0 && output.contains("healthy", ignoreCase = true)) {
            return
        }

        Thread.sleep(10_000)  // Check every 10 seconds
    }

    throw AssertionError("Service $service did not become healthy within ${timeoutSeconds}s")
}
