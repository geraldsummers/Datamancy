package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.io.File
import java.util.UUID

/**
 * CI/CD Pipeline Integration Tests
 *
 * Tests for the complete CI/CD pipeline using labware Docker socket.
 * Tests image building, registry operations, deployments, and isolation.
 */
suspend fun TestRunner.cicdTests() = suite("CI/CD Pipeline Tests") {
    val labwareSocket = "/run/labware-docker.sock"
    val registryHost = "registry:5000"
    val testImagePrefix = "cicd-test"

    // Check prerequisites
    test("CI/CD prerequisites available") {
        val socketFile = File(labwareSocket)
        if (!socketFile.exists()) {
            throw AssertionError("Labware socket not found at $labwareSocket - CI/CD infrastructure not configured")
        }
    }

    test("Build Docker image on labware socket") {
        val testId = UUID.randomUUID().toString().substring(0, 8)
        val imageName = "$testImagePrefix-build:$testId"

        val tempDir = File.createTempFile("dockerfile-", "").apply {
            delete()
            mkdir()
        }

        try {
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest
                RUN echo "CI/CD Test Build $testId"
                CMD ["echo", "Hello from CI/CD test"]
            """.trimIndent())

            val (exitCode, output) = execCICDDocker(labwareSocket, "build", "-t", imageName, tempDir.absolutePath)
            if (exitCode != 0) {
                throw AssertionError("Docker build failed: $output")
            }

            val (listExitCode, listOutput) = execCICDDocker(labwareSocket, "images", imageName, "-q")
            if (listExitCode != 0 || listOutput.trim().isEmpty()) {
                throw AssertionError("Image not found after build")
            }

            // Cleanup
            execCICDDocker(labwareSocket, "rmi", "-f", imageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("Push image to registry") {
        val testId = UUID.randomUUID().toString().substring(0, 8)
        val localImageName = "$testImagePrefix-push:$testId"
        val registryImageName = "$registryHost/$testImagePrefix-push:$testId"

        val tempDir = File.createTempFile("dockerfile-", "").apply {
            delete()
            mkdir()
        }

        try {
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest
                LABEL test.id="$testId"
                CMD ["echo", "Registry test"]
            """.trimIndent())

            val (buildExitCode, buildOutput) = execCICDDocker(labwareSocket, "build", "-t", localImageName, tempDir.absolutePath)
            if (buildExitCode != 0) {
                throw AssertionError("Build failed: $buildOutput")
            }

            execCICDDocker(labwareSocket, "tag", localImageName, registryImageName)

            val (pushExitCode, pushOutput) = execCICDDocker(labwareSocket, "push", registryImageName)
            if (pushExitCode != 0) {
                throw AssertionError("Push to registry failed: $pushOutput")
            }

            // Cleanup
            execCICDDocker(labwareSocket, "rmi", "-f", localImageName, registryImageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("Verify labware container isolation") {
        val testId = UUID.randomUUID().toString().substring(0, 8)
        val containerName = "$testImagePrefix-isolation-$testId"

        try {
            val (startExitCode, _) = execCICDDocker(
                labwareSocket, "run", "-d", "--name", containerName, "alpine:latest", "sleep", "30"
            )
            if (startExitCode != 0) {
                throw AssertionError("Container start failed")
            }

            // Check container exists on labware
            val (labwareCheckCode, labwareOutput) = execCICDDocker(
                labwareSocket, "ps", "--filter", "name=$containerName", "--format", "{{.Names}}"
            )
            if (labwareCheckCode != 0 || !labwareOutput.contains(containerName)) {
                throw AssertionError("Container not found on labware")
            }

            // Check container NOT visible on production
            val prodProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()

            if (prodOutput.contains(containerName)) {
                throw AssertionError("Container visible on production - isolation breach!")
            }

            // Cleanup
            execCICDDocker(labwareSocket, "rm", "-f", containerName)
        } catch (e: Exception) {
            execCICDDocker(labwareSocket, "rm", "-f", containerName)
            throw e
        }
    }

    test("Multi-stage Docker build") {
        val testId = UUID.randomUUID().toString().substring(0, 8)
        val imageName = "$testImagePrefix-multistage:$testId"

        val tempDir = File.createTempFile("dockerfile-", "").apply {
            delete()
            mkdir()
        }

        try {
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest AS builder
                RUN echo "Building artifact..." > /artifact.txt

                FROM alpine:latest
                COPY --from=builder /artifact.txt /app/artifact.txt
                CMD ["cat", "/app/artifact.txt"]
            """.trimIndent())

            val (buildExitCode, buildOutput) = execCICDDocker(labwareSocket, "build", "-t", imageName, tempDir.absolutePath)
            if (buildExitCode != 0) {
                throw AssertionError("Multi-stage build failed: $buildOutput")
            }

            val (runExitCode, runOutput) = execCICDDocker(labwareSocket, "run", "--rm", imageName)
            if (runExitCode != 0 || !runOutput.contains("Building artifact")) {
                throw AssertionError("Multi-stage container output incorrect")
            }

            // Cleanup
            execCICDDocker(labwareSocket, "rmi", "-f", imageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

private fun execCICDDocker(socketPath: String, vararg args: String): Pair<Int, String> {
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
object CICDPipelineTests {

    const val LABWARE_SOCKET = "/run/labware-docker.sock"
    const val REGISTRY_HOST = "registry:5000"
    const val TEST_IMAGE_PREFIX = "cicd-test"

    private val tempDirs = mutableListOf<File>()
    private val testImages = mutableListOf<String>()
    private val testContainers = mutableListOf<String>()

    fun isLabwareSocketAvailable(): Boolean {
        return File(LABWARE_SOCKET).exists()
    }

    fun execLabwareDocker(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", "unix://$LABWARE_SOCKET") + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return exitCode to output
    }

    fun cleanup() {
        testImages.forEach { image -> execLabwareDocker("rmi", "-f", image) }
        testImages.clear()

        testContainers.forEach { container -> execLabwareDocker("rm", "-f", container) }
        testContainers.clear()

        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    fun createTempDir(): File {
        return File.createTempFile("dockerfile-", "").apply {
            delete()
            mkdir()
            tempDirs.add(this)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(80))
        println("CI/CD Pipeline Integration Tests")
        println("=".repeat(80))

        if (!isLabwareSocketAvailable()) {
            println("❌ Labware socket not available at $LABWARE_SOCKET")
            println("These tests must be run on a deployed stack with labware VM configured.")
            return
        }

        var passed = 0
        var failed = 0

        // Test 1: Build image
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val imageName = "$TEST_IMAGE_PREFIX-build:$testId"
            testImages.add(imageName)

            val tempDir = createTempDir()
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest
                RUN echo "CI/CD Test Build $testId"
                CMD ["echo", "Hello from CI/CD test"]
            """.trimIndent())

            val (exitCode, output) = execLabwareDocker("build", "-t", imageName, tempDir.absolutePath)
            require(exitCode == 0) { "Docker build failed: $output" }

            val (listExitCode, listOutput) = execLabwareDocker("images", imageName, "-q")
            require(listExitCode == 0 && listOutput.trim().isNotEmpty()) { "Image not listed" }

            println("✅ Built image '$imageName' on labware socket")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Build image test failed: ${e.message}")
            failed++
            cleanup()
        }

        // Test 2: Push to registry
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val localImageName = "$TEST_IMAGE_PREFIX-push:$testId"
            val registryImageName = "$REGISTRY_HOST/$TEST_IMAGE_PREFIX-push:$testId"
            testImages.addAll(listOf(localImageName, registryImageName))

            val tempDir = createTempDir()
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest
                LABEL test.id="$testId"
                CMD ["echo", "Registry test"]
            """.trimIndent())

            val (buildExitCode, _) = execLabwareDocker("build", "-t", localImageName, tempDir.absolutePath)
            require(buildExitCode == 0) { "Build failed" }

            val (tagExitCode, _) = execLabwareDocker("tag", localImageName, registryImageName)
            require(tagExitCode == 0) { "Tag failed" }

            val (pushExitCode, pushOutput) = execLabwareDocker("push", registryImageName)
            require(pushExitCode == 0) { "Push failed: $pushOutput" }

            println("✅ Pushed image '$registryImageName' to registry")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Push to registry test failed: ${e.message}")
            failed++
            cleanup()
        }

        // Test 3: Deploy from registry
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val imageName = "$REGISTRY_HOST/$TEST_IMAGE_PREFIX-deploy:$testId"
            val containerName = "$TEST_IMAGE_PREFIX-container-$testId"
            testImages.add(imageName)
            testContainers.add(containerName)

            val tempDir = createTempDir()
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest
                CMD ["sh", "-c", "echo 'Deployment test $testId'"]
            """.trimIndent())

            execLabwareDocker("build", "-t", imageName, tempDir.absolutePath)
            val (pushExitCode, _) = execLabwareDocker("push", imageName)
            require(pushExitCode == 0) { "Push failed" }

            execLabwareDocker("rmi", imageName)

            val (runExitCode, runOutput) = execLabwareDocker("run", "--name", containerName, "--rm", imageName)
            require(runExitCode == 0) { "Container run failed" }
            require(runOutput.contains("Deployment test $testId")) { "Wrong output" }

            println("✅ Deployed container '$containerName' from registry")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Deploy from registry test failed: ${e.message}")
            failed++
            cleanup()
        }

        // Test 4: Isolation
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val containerName = "$TEST_IMAGE_PREFIX-isolation-$testId"
            testContainers.add(containerName)

            val (startExitCode, _) = execLabwareDocker(
                "run", "-d", "--name", containerName, "alpine:latest", "sleep", "30"
            )
            require(startExitCode == 0) { "Container start failed" }

            val (labwareCheckCode, labwareOutput) = execLabwareDocker(
                "ps", "--filter", "name=$containerName", "--format", "{{.Names}}"
            )
            require(labwareCheckCode == 0 && labwareOutput.contains(containerName)) {
                "Container not found on labware"
            }

            val prodProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()

            require(!prodOutput.contains(containerName)) { "Container visible on production!" }

            println("✅ Verified isolation: container '$containerName' exists on labware only")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Isolation test failed: ${e.message}")
            failed++
            cleanup()
        }

        // Test 5: Multi-stage build
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val imageName = "$TEST_IMAGE_PREFIX-multistage:$testId"
            testImages.add(imageName)

            val tempDir = createTempDir()
            File(tempDir, "Dockerfile").writeText("""
                FROM alpine:latest AS builder
                RUN echo "Building artifact..." > /artifact.txt

                FROM alpine:latest
                COPY --from=builder /artifact.txt /app/artifact.txt
                CMD ["cat", "/app/artifact.txt"]
            """.trimIndent())

            val (buildExitCode, buildOutput) = execLabwareDocker("build", "-t", imageName, tempDir.absolutePath)
            require(buildExitCode == 0) { "Multi-stage build failed: $buildOutput" }

            val (runExitCode, runOutput) = execLabwareDocker("run", "--rm", imageName)
            require(runExitCode == 0 && runOutput.contains("Building artifact")) {
                "Multi-stage container failed"
            }

            println("✅ Multi-stage build '$imageName' succeeded")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Multi-stage build test failed: ${e.message}")
            failed++
            cleanup()
        }

        println("=".repeat(80))
        println("Results: $passed passed, $failed failed")
        println("=".repeat(80))

        if (failed > 0) {
            System.exit(1)
        }
    }
}
