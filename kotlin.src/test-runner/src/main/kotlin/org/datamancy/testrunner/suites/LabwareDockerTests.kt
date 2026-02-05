package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/**
 * Labware Docker Tests
 *
 * Integration tests for the labware Docker host via SSH
 * These tests verify isolated Docker daemon access for CI/CD sandbox deployments.
 */
suspend fun TestRunner.labwareTests() = suite("Labware Docker Tests") {
    val dockerHost = System.getenv("DOCKER_HOST") ?: "ssh://labware"

    // Check if labware Docker host is available - skip suite if not
    if (!isLabwareDockerAvailable(dockerHost)) {
        println("      ⚠️  Labware Docker host not accessible at $dockerHost - skipping labware tests")
        println("      ℹ️  To enable: Set DOCKER_HOST=ssh://your-labware-host and configure SSH keys")
        return@suite
    }

    test("Labware Docker is accessible") {
        val (exitCode, _) = execLabwareDocker(dockerHost, "info")
        exitCode shouldBe 0
    }

    test("Labware Docker version responds") {
        val (exitCode, output) = execLabwareDocker(dockerHost, "version", "--format", "{{.Server.Version}}")
        exitCode shouldBe 0
        output.trim().isNotEmpty() shouldBe true
    }

    test("Labware Docker ps command works") {
        val (exitCode, _) = execLabwareDocker(dockerHost, "ps", "--format", "{{.ID}}")
        exitCode shouldBe 0
    }

    test("Labware containers isolated from production") {
        val (_, labwareOutput) = execLabwareDocker(dockerHost, "ps", "--format", "{{.Names}}")
        val labwareContainers = labwareOutput.lines().filter { it.isNotBlank() }.toSet()

        val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
        val prodOutput = prodProcess.inputStream.bufferedReader().readText()
        prodProcess.waitFor()
        val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

        val overlap = labwareContainers.intersect(prodContainers)
        if (overlap.isNotEmpty()) {
            throw AssertionError("Isolation breach: Found overlapping containers: $overlap")
        }
    }

    test("Labware Docker images command works") {
        val (exitCode, _) = execLabwareDocker(dockerHost, "images", "--format", "{{.Repository}}:{{.Tag}}")
        exitCode shouldBe 0
    }

    test("Labware can create and run containers") {
        val containerName = "labware-test-${System.currentTimeMillis()}"
        val (runExitCode, output) = execLabwareDocker(
            dockerHost, "run", "--name", containerName, "--rm", "alpine:latest", "echo", "test"
        )
        runExitCode shouldBe 0
        output shouldContain "test"
    }
}

private fun execLabwareDocker(dockerHost: String, vararg args: String): Pair<Int, String> {
    val command = listOf("docker", "-H", dockerHost) + args
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

private fun isLabwareDockerAvailable(dockerHost: String): Boolean {
    return try {
        val (exitCode, _) = execLabwareDocker(dockerHost, "info")
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Standalone entry point for manual testing
 */
object LabwareDockerTests {

    val LABWARE_DOCKER_HOST = System.getenv("DOCKER_HOST") ?: "ssh://labware"

    fun isLabwareDockerAvailable(): Boolean {
        return try {
            val (exitCode, _) = execLabwareDocker("info")
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun execLabwareDocker(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", LABWARE_DOCKER_HOST) + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return exitCode to output
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(80))
        println("Labware Docker Integration Tests")
        println("=".repeat(80))

        if (!isLabwareDockerAvailable()) {
            println("❌ Labware Docker host not accessible at $LABWARE_DOCKER_HOST")
            println("These tests must be run with DOCKER_HOST set and SSH keys configured.")
            return
        }

        var passed = 0
        var failed = 0

        // Test 1: Docker info
        try {
            val (exitCode, _) = execLabwareDocker("info")
            require(exitCode == 0) { "Docker info command should succeed" }
            println("✅ Docker host is accessible")
            passed++
        } catch (e: Exception) {
            println("❌ Docker accessibility test failed: ${e.message}")
            failed++
        }

        // Test 3: Docker version
        try {
            val (exitCode, output) = execLabwareDocker("version", "--format", "{{.Server.Version}}")
            require(exitCode == 0) { "Docker version command should succeed" }
            require(output.trim().isNotEmpty()) { "Version output should not be empty" }
            println("✅ Docker version: ${output.trim()}")
            passed++
        } catch (e: Exception) {
            println("❌ Docker version test failed: ${e.message}")
            failed++
        }

        // Test 4: List containers
        try {
            val (exitCode, _) = execLabwareDocker("ps", "--format", "{{.ID}}")
            require(exitCode == 0) { "Docker ps should succeed" }
            println("✅ Docker ps command succeeded")
            passed++
        } catch (e: Exception) {
            println("❌ Docker ps test failed: ${e.message}")
            failed++
        }

        // Test 5: Isolation from production
        try {
            val (_, labwareOutput) = execLabwareDocker("ps", "--format", "{{.Names}}")
            val labwareContainers = labwareOutput.lines().filter { it.isNotBlank() }.toSet()

            val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()
            val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

            val overlap = labwareContainers.intersect(prodContainers)
            require(overlap.isEmpty()) { "Found overlapping containers: $overlap" }
            println("✅ Isolation verified: ${labwareContainers.size} labware containers, ${prodContainers.size} production containers, 0 overlap")
            passed++
        } catch (e: Exception) {
            println("❌ Isolation test failed: ${e.message}")
            failed++
        }

        // Test 6: Image operations
        try {
            val (exitCode, _) = execLabwareDocker("images", "--format", "{{.Repository}}:{{.Tag}}")
            require(exitCode == 0) { "Docker images should succeed" }
            println("✅ Docker images command succeeded")
            passed++
        } catch (e: Exception) {
            println("❌ Image operations test failed: ${e.message}")
            failed++
        }

        // Test 7: Container creation
        try {
            val containerName = "labware-test-${System.currentTimeMillis()}"
            val (runExitCode, _) = execLabwareDocker(
                "run", "--name", containerName, "--rm", "alpine:latest", "echo", "test"
            )
            require(runExitCode == 0) { "Container run should succeed" }
            println("✅ Container creation and execution succeeded")
            passed++
        } catch (e: Exception) {
            println("❌ Container creation test failed: ${e.message}")
            failed++
        }

        println("=".repeat(80))
        println("Results: $passed passed, $failed failed")
        println("=".repeat(80))

        if (failed > 0) {
            System.exit(1)
        }
    }
}
