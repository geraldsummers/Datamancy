package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/**
 * Labware Docker Socket Tests
 *
 * Integration tests for the labware virtualized Docker socket at /run/labware-docker.sock
 * These tests verify isolated Docker daemon access for CI/CD sandbox deployments.
 */
suspend fun TestRunner.labwareTests() = suite("Labware Docker Socket Tests") {
    val socketPath = "/run/labware-docker.sock"

    test("Labware socket file exists") {
        val socketFile = File(socketPath)
        if (!socketFile.exists()) {
            throw AssertionError("Labware socket not found at $socketPath - labware VM may not be configured")
        }
        socketFile.canRead() shouldBe true
    }

    test("Labware socket is connectable") {
        val socketAddress = UnixDomainSocketAddress.of(socketPath)
        SocketChannel.open(socketAddress).use { channel ->
            channel.isConnected shouldBe true
        }
    }

    test("Labware Docker version responds") {
        val (exitCode, output) = execLabwareDocker(socketPath, "version", "--format", "{{.Server.Version}}")
        exitCode shouldBe 0
        output.trim().isNotEmpty() shouldBe true
    }

    test("Labware Docker ps command works") {
        val (exitCode, _) = execLabwareDocker(socketPath, "ps", "--format", "{{.ID}}")
        exitCode shouldBe 0
    }

    test("Labware containers isolated from production") {
        val (_, labwareOutput) = execLabwareDocker(socketPath, "ps", "--format", "{{.Names}}")
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
        val (exitCode, _) = execLabwareDocker(socketPath, "images", "--format", "{{.Repository}}:{{.Tag}}")
        exitCode shouldBe 0
    }

    test("Labware can create and run containers") {
        val containerName = "labware-test-${System.currentTimeMillis()}"
        val (runExitCode, output) = execLabwareDocker(
            socketPath, "run", "--name", containerName, "--rm", "alpine:latest", "echo", "test"
        )
        runExitCode shouldBe 0
        output shouldContain "test"
    }
}

private fun execLabwareDocker(socketPath: String, vararg args: String): Pair<Int, String> {
    val command = listOf("docker", "-H", "unix://$socketPath") + args
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

/**
 * Standalone entry point for manual testing
 */
object LabwareDockerTests {

    const val LABWARE_SOCKET_PATH = "/run/labware-docker.sock"

    fun isLabwareSocketAvailable(): Boolean {
        return File(LABWARE_SOCKET_PATH).exists()
    }

    fun execLabwareDocker(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", "unix://$LABWARE_SOCKET_PATH") + args
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
        println("Labware Docker Socket Integration Tests")
        println("=".repeat(80))

        if (!isLabwareSocketAvailable()) {
            println("❌ Labware socket not available at $LABWARE_SOCKET_PATH")
            println("These tests must be run on a deployed stack with labware VM configured.")
            return
        }

        var passed = 0
        var failed = 0

        // Test 1: Socket file exists
        try {
            val socketFile = File(LABWARE_SOCKET_PATH)
            require(socketFile.exists()) { "Socket file should exist" }
            require(socketFile.canRead()) { "Socket file should be readable" }
            println("✅ Socket file exists and is readable")
            passed++
        } catch (e: Exception) {
            println("❌ Socket file test failed: ${e.message}")
            failed++
        }

        // Test 2: Socket is connectable
        try {
            val socketAddress = UnixDomainSocketAddress.of(LABWARE_SOCKET_PATH)
            SocketChannel.open(socketAddress).use { channel ->
                require(channel.isConnected) { "Should connect to socket" }
            }
            println("✅ Socket is connectable")
            passed++
        } catch (e: Exception) {
            println("❌ Socket connection test failed: ${e.message}")
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
