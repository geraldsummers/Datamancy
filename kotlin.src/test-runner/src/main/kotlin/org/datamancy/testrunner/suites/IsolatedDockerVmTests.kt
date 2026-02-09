package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*


suspend fun TestRunner.isolatedDockerVmTests() = suite("Isolated Docker VM Tests") {
    val dockerHost = System.getenv("DOCKER_HOST") ?: "tcp://docker-proxy:2375"


    if (!isIsolatedDockerVmAvailable(dockerHost)) {
        println("      ⚠️  Isolated Docker VM not accessible at $dockerHost - skipping isolated-docker-vm tests")
        println("      ℹ️  To enable: Set DOCKER_HOST=ssh://your-isolated-docker-vm and configure SSH keys")
        return@suite
    }

    test("Isolated Docker VM is accessible") {
        val (exitCode, _) = execIsolatedDockerVm(dockerHost, "info")
        exitCode shouldBe 0
    }

    test("Isolated Docker VM version responds") {
        val (exitCode, output) = execIsolatedDockerVm(dockerHost, "version", "--format", "{{.Server.Version}}")
        exitCode shouldBe 0
        output.trim().isNotEmpty() shouldBe true
    }

    test("Isolated Docker VM ps command works") {
        val (exitCode, _) = execIsolatedDockerVm(dockerHost, "ps", "--format", "{{.ID}}")
        exitCode shouldBe 0
    }

    test("Isolated VM containers isolated from production") {
        val (_, isolatedOutput) = execIsolatedDockerVm(dockerHost, "ps", "--format", "{{.Names}}")
        val isolatedContainers = isolatedOutput.lines().filter { it.isNotBlank() }.toSet()

        val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
        val prodOutput = prodProcess.inputStream.bufferedReader().readText()
        prodProcess.waitFor()
        val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

        val overlap = isolatedContainers.intersect(prodContainers)
        if (overlap.isNotEmpty()) {
            throw AssertionError("Isolation breach: Found overlapping containers: $overlap")
        }
    }

    test("Isolated Docker VM images command works") {
        val (exitCode, _) = execIsolatedDockerVm(dockerHost, "images", "--format", "{{.Repository}}:{{.Tag}}")
        exitCode shouldBe 0
    }

    test("Isolated VM can create and run containers") {
        val containerName = "isolated-vm-test-${System.currentTimeMillis()}"
        val (runExitCode, output) = execIsolatedDockerVm(
            dockerHost, "run", "--name", containerName, "--rm", "alpine:latest", "echo", "test"
        )
        runExitCode shouldBe 0
        output shouldContain "test"
    }
}

private fun execIsolatedDockerVm(dockerHost: String, vararg args: String): Pair<Int, String> {
    val command = listOf("docker", "-H", dockerHost) + args
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    return exitCode to output
}

private fun isIsolatedDockerVmAvailable(dockerHost: String): Boolean {
    return try {
        val (exitCode, _) = execIsolatedDockerVm(dockerHost, "info")
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}


object IsolatedDockerVmTests {

    val ISOLATED_DOCKER_VM_HOST = System.getenv("DOCKER_HOST") ?: "tcp://docker-proxy:2375"

    fun isIsolatedDockerVmAvailable(): Boolean {
        return try {
            val (exitCode, _) = execIsolatedDockerVm("info")
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun execIsolatedDockerVm(vararg args: String): Pair<Int, String> {
        val command = listOf("docker", "-H", ISOLATED_DOCKER_VM_HOST) + args
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
        println("Isolated Docker VM Integration Tests")
        println("=".repeat(80))

        if (!isIsolatedDockerVmAvailable()) {
            println("❌ Isolated Docker VM not accessible at $ISOLATED_DOCKER_VM_HOST")
            println("These tests must be run with DOCKER_HOST set and SSH keys configured.")
            return
        }

        var passed = 0
        var failed = 0


        try {
            val (exitCode, _) = execIsolatedDockerVm("info")
            require(exitCode == 0) { "Docker info command should succeed" }
            println("✅ Docker host is accessible")
            passed++
        } catch (e: Exception) {
            println("❌ Docker accessibility test failed: ${e.message}")
            failed++
        }


        try {
            val (exitCode, output) = execIsolatedDockerVm("version", "--format", "{{.Server.Version}}")
            require(exitCode == 0) { "Docker version command should succeed" }
            require(output.trim().isNotEmpty()) { "Version output should not be empty" }
            println("✅ Docker version: ${output.trim()}")
            passed++
        } catch (e: Exception) {
            println("❌ Docker version test failed: ${e.message}")
            failed++
        }


        try {
            val (exitCode, _) = execIsolatedDockerVm("ps", "--format", "{{.ID}}")
            require(exitCode == 0) { "Docker ps should succeed" }
            println("✅ Docker ps command succeeded")
            passed++
        } catch (e: Exception) {
            println("❌ Docker ps test failed: ${e.message}")
            failed++
        }


        try {
            val (_, isolatedOutput) = execIsolatedDockerVm("ps", "--format", "{{.Names}}")
            val isolatedContainers = isolatedOutput.lines().filter { it.isNotBlank() }.toSet()

            val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
            val prodOutput = prodProcess.inputStream.bufferedReader().readText()
            prodProcess.waitFor()
            val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

            val overlap = isolatedContainers.intersect(prodContainers)
            require(overlap.isEmpty()) { "Found overlapping containers: $overlap" }
            println("✅ Isolation verified: ${isolatedContainers.size} isolated VM containers, ${prodContainers.size} production containers, 0 overlap")
            passed++
        } catch (e: Exception) {
            println("❌ Isolation test failed: ${e.message}")
            failed++
        }


        try {
            val (exitCode, _) = execIsolatedDockerVm("images", "--format", "{{.Repository}}:{{.Tag}}")
            require(exitCode == 0) { "Docker images should succeed" }
            println("✅ Docker images command succeeded")
            passed++
        } catch (e: Exception) {
            println("❌ Image operations test failed: ${e.message}")
            failed++
        }


        try {
            val containerName = "isolated-vm-test-${System.currentTimeMillis()}"
            val (runExitCode, _) = execIsolatedDockerVm(
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
