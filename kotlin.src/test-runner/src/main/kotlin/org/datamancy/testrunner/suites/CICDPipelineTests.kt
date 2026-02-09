package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.io.File
import java.util.UUID


suspend fun TestRunner.cicdTests() = suite("CI/CD Pipeline Tests") {
    val isolatedDockerVmDockerHost = System.getenv("DOCKER_HOST") ?: "ssh://isolated-docker-vm"

    
    
    
    val registryHost = System.getenv("HOST_IP")?.let { "$it:5000" }
        ?: detectIsolatedDockerVmHostIP(isolatedDockerVmDockerHost)
        ?: "192.168.0.11:5000"  

    val testImagePrefix = "cicd-test"

    
    if (!isIsolatedDockerVmDockerAvailable(isolatedDockerVmDockerHost)) {
        println("      ⚠️  IsolatedDockerVm Docker host not accessible at $isolatedDockerVmDockerHost - skipping CI/CD tests")
        println("      ℹ️  To enable: Set DOCKER_HOST=ssh://your-isolatedDockerVmhost and configure SSH keys")
        return@suite
    }

    test("Build Docker image on isolated-docker-vm socket") {
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

            val (exitCode, output) = execCICDDocker(isolatedDockerVmDockerHost, "build", "-t", imageName, tempDir.absolutePath)
            if (exitCode != 0) {
                throw AssertionError("Docker build failed: $output")
            }

            val (listExitCode, listOutput) = execCICDDocker(isolatedDockerVmDockerHost, "images", imageName, "-q")
            if (listExitCode != 0 || listOutput.trim().isEmpty()) {
                throw AssertionError("Image not found after build")
            }

            
            execCICDDocker(isolatedDockerVmDockerHost, "rmi", "-f", imageName)
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

            val (buildExitCode, buildOutput) = execCICDDocker(isolatedDockerVmDockerHost, "build", "-t", localImageName, tempDir.absolutePath)
            if (buildExitCode != 0) {
                throw AssertionError("Build failed: $buildOutput")
            }

            execCICDDocker(isolatedDockerVmDockerHost, "tag", localImageName, registryImageName)

            val (pushExitCode, pushOutput) = execCICDDocker(isolatedDockerVmDockerHost, "push", registryImageName)
            if (pushExitCode != 0) {
                throw AssertionError("Push to registry failed: $pushOutput")
            }

            
            execCICDDocker(isolatedDockerVmDockerHost, "rmi", "-f", localImageName, registryImageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    test("Verify isolated-docker-vm container isolation") {
        val testId = UUID.randomUUID().toString().substring(0, 8)
        val containerName = "$testImagePrefix-isolation-$testId"

        try {
            val (startExitCode, _) = execCICDDocker(
                isolatedDockerVmDockerHost, "run", "-d", "--name", containerName, "alpine:latest", "sleep", "30"
            )
            if (startExitCode != 0) {
                throw AssertionError("Container start failed")
            }


            val (isolatedDockerVmCheckCode, isolatedDockerVmOutput) = execCICDDocker(
                isolatedDockerVmDockerHost, "ps", "--filter", "name=$containerName", "--format", "{{.Names}}"
            )
            if (isolatedDockerVmCheckCode != 0 || !isolatedDockerVmOutput.contains(containerName)) {
                throw AssertionError("Container not found on isolated-docker-vm")
            }

            
            val prodProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()

            if (prodOutput.contains(containerName)) {
                throw AssertionError("Container visible on production - isolation breach!")
            }

            
            execCICDDocker(isolatedDockerVmDockerHost, "rm", "-f", containerName)
        } catch (e: Exception) {
            execCICDDocker(isolatedDockerVmDockerHost, "rm", "-f", containerName)
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

            val (buildExitCode, buildOutput) = execCICDDocker(isolatedDockerVmDockerHost, "build", "-t", imageName, tempDir.absolutePath)
            if (buildExitCode != 0) {
                throw AssertionError("Multi-stage build failed: $buildOutput")
            }

            val (runExitCode, runOutput) = execCICDDocker(isolatedDockerVmDockerHost, "run", "--rm", imageName)
            if (runExitCode != 0 || !runOutput.contains("Building artifact")) {
                throw AssertionError("Multi-stage container output incorrect")
            }

            
            execCICDDocker(isolatedDockerVmDockerHost, "rmi", "-f", imageName)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}


private fun detectIsolatedDockerVmHostIP(dockerHost: String): String? {
    return try {
        val (exitCode, output) = execCICDDocker(
            dockerHost,
            "run", "--rm", "alpine:latest",
            "sh", "-c", "ip route show default | awk '{print \$3}'"
        )

        if (exitCode == 0) {
            val gateway = output.trim()
            
            System.getenv("HOST_IP") ?: gateway
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun execCICDDocker(dockerHost: String, vararg args: String): Pair<Int, String> {
    val command = listOf("docker", "-H", dockerHost) + args
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

private fun isIsolatedDockerVmDockerAvailable(dockerHost: String): Boolean {
    return try {
        val (exitCode, _) = execCICDDocker(dockerHost, "info")
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}


object CICDPipelineTests {

    val ISOLATED_DOCKER_VM_DOCKER_HOST = System.getenv("DOCKER_HOST") ?: "ssh://isolated-docker-vm"
    const val REGISTRY_HOST = "registry:5000"
    const val TEST_IMAGE_PREFIX = "cicd-test"

    private val tempDirs = mutableListOf<File>()
    private val testImages = mutableListOf<String>()
    private val testContainers = mutableListOf<String>()

    fun isIsolatedDockerVmDockerAvailable(): Boolean {
        return try {
            val (exitCode, _) = execIsolatedDockerVmDocker("info")
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun execIsolatedDockerVmDocker(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", ISOLATED_DOCKER_VM_DOCKER_HOST) + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return exitCode to output
    }

    fun cleanup() {
        testImages.forEach { image -> execIsolatedDockerVmDocker("rmi", "-f", image) }
        testImages.clear()

        testContainers.forEach { container -> execIsolatedDockerVmDocker("rm", "-f", container) }
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

        if (!isIsolatedDockerVmDockerAvailable()) {
            println("❌ IsolatedDockerVm Docker host not accessible at $ISOLATED_DOCKER_VM_DOCKER_HOST")
            println("These tests must be run with DOCKER_HOST set and SSH keys configured.")
            return
        }

        var passed = 0
        var failed = 0

        
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

            val (exitCode, output) = execIsolatedDockerVmDocker("build", "-t", imageName, tempDir.absolutePath)
            require(exitCode == 0) { "Docker build failed: $output" }

            val (listExitCode, listOutput) = execIsolatedDockerVmDocker("images", imageName, "-q")
            require(listExitCode == 0 && listOutput.trim().isNotEmpty()) { "Image not listed" }

            println("✅ Built image '$imageName' on isolated-docker-vm socket")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Build image test failed: ${e.message}")
            failed++
            cleanup()
        }

        
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

            val (buildExitCode, _) = execIsolatedDockerVmDocker("build", "-t", localImageName, tempDir.absolutePath)
            require(buildExitCode == 0) { "Build failed" }

            val (tagExitCode, _) = execIsolatedDockerVmDocker("tag", localImageName, registryImageName)
            require(tagExitCode == 0) { "Tag failed" }

            val (pushExitCode, pushOutput) = execIsolatedDockerVmDocker("push", registryImageName)
            require(pushExitCode == 0) { "Push failed: $pushOutput" }

            println("✅ Pushed image '$registryImageName' to registry")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Push to registry test failed: ${e.message}")
            failed++
            cleanup()
        }

        
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

            execIsolatedDockerVmDocker("build", "-t", imageName, tempDir.absolutePath)
            val (pushExitCode, _) = execIsolatedDockerVmDocker("push", imageName)
            require(pushExitCode == 0) { "Push failed" }

            execIsolatedDockerVmDocker("rmi", imageName)

            val (runExitCode, runOutput) = execIsolatedDockerVmDocker("run", "--name", containerName, "--rm", imageName)
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

        
        try {
            val testId = UUID.randomUUID().toString().substring(0, 8)
            val containerName = "$TEST_IMAGE_PREFIX-isolation-$testId"
            testContainers.add(containerName)

            val (startExitCode, _) = execIsolatedDockerVmDocker(
                "run", "-d", "--name", containerName, "alpine:latest", "sleep", "30"
            )
            require(startExitCode == 0) { "Container start failed" }

            val (isolatedDockerVmCheckCode, isolatedDockerVmOutput) = execIsolatedDockerVmDocker(
                "ps", "--filter", "name=$containerName", "--format", "{{.Names}}"
            )
            require(isolatedDockerVmCheckCode == 0 && isolatedDockerVmOutput.contains(containerName)) {
                "Container not found on isolated-docker-vm"
            }

            val prodProcess = ProcessBuilder("docker", "ps", "--filter", "name=$containerName", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()

            require(!prodOutput.contains(containerName)) { "Container visible on production!" }

            println("✅ Verified isolation: container '$containerName' exists on isolated-docker-vm only")
            passed++
            cleanup()
        } catch (e: Exception) {
            println("❌ Isolation test failed: ${e.message}")
            failed++
            cleanup()
        }

        
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

            val (buildExitCode, buildOutput) = execIsolatedDockerVmDocker("build", "-t", imageName, tempDir.absolutePath)
            require(buildExitCode == 0) { "Multi-stage build failed: $buildOutput" }

            val (runExitCode, runOutput) = execIsolatedDockerVmDocker("run", "--rm", imageName)
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
