package org.datamancy.testrunner.suites

import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel

/**
 * Labware Docker Socket Tests
 *
 * Standalone integration tests for the labware virtualized Docker socket at /run/labware-docker.sock
 * These tests verify isolated Docker daemon access for CI/CD sandbox deployments.
 *
 * Run manually on deployed stack where labware socket is available.
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
