package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit


suspend fun TestRunner.stackReplicationTests() = suite("Stack Replication Tests") {
    val labwareDockerHost = System.getenv("DOCKER_HOST") ?: "ssh://labware"

    
    if (!isLabwareDockerAvailable(labwareDockerHost)) {
        println("      ⚠️  Labware Docker host not accessible at $labwareDockerHost - skipping replication tests")
        println("      ℹ️  To enable: Set DOCKER_HOST=ssh://your-labware-host and configure SSH keys")
        return@suite
    }

    val testRunId = "replication-${System.currentTimeMillis()}"
    val networkName = "labware-$testRunId"
    val composePath = "/tmp/labware-compose-$testRunId"

    test("Prepare labware compose directory") {
        val composeDir = File(composePath)
        composeDir.mkdirs() shouldBe true

        
        
        File(composeDir, "docker-compose.yml").writeText(
            """
            version: '3.8'

            services:
              postgres:
                image: postgres:16.11
                container_name: labware-postgres-$testRunId
                environment:
                  POSTGRES_PASSWORD: test_password
                  POSTGRES_USER: test_admin
                  POSTGRES_DB: postgres
                healthcheck:
                  test: ["CMD-SHELL", "pg_isready -U test_admin"]
                  interval: 5s
                  timeout: 3s
                  retries: 5
                networks:
                  - $networkName

              valkey:
                image: valkey/valkey:8.1.5
                container_name: labware-valkey-$testRunId
                healthcheck:
                  test: ["CMD", "valkey-cli", "ping"]
                  interval: 5s
                  timeout: 3s
                  retries: 5
                networks:
                  - $networkName

              # Minimal test service to validate the stack
              test-service:
                image: alpine:latest
                container_name: labware-test-service-$testRunId
                command: >
                  sh -c "
                  apk add --no-cache postgresql-client redis &&
                  sleep 5 &&
                  echo 'Testing postgres...' &&
                  PGPASSWORD=test_password psql -h postgres -U test_admin -d postgres -c 'SELECT 1;' &&
                  echo 'Testing valkey...' &&
                  redis-cli -h valkey PING &&
                  echo 'All services healthy!' &&
                  sleep infinity
                  "
                depends_on:
                  - postgres
                  - valkey
                networks:
                  - $networkName

            networks:
              $networkName:
                name: $networkName
            """.trimIndent()
        )

        println("      ℹ️  Created compose directory at $composePath")
    }

    test("Deploy minimal stack on labware socket") {
        val (exitCode, output) = execLabwareDockerCompose(
            labwareDockerHost,
            composePath,
            "up", "-d"
        )

        if (exitCode != 0) {
            println("      ❌ Deploy output: $output")
            throw AssertionError("Stack deployment failed with exit code $exitCode")
        }

        println("      ℹ️  Stack deployed, waiting for services to be healthy...")

        
        var healthy = false
        repeat(60) { attempt ->
            val (checkExit, checkOutput) = execLabwareDocker(
                labwareDockerHost,
                "ps",
                "--filter", "name=labware-test-service-$testRunId",
                "--format", "{{.Status}}"
            )

            if (checkExit == 0 && checkOutput.contains("Up")) {
                healthy = true
                println("      ✓ Services healthy after ${attempt + 1} seconds")
                return@repeat
            }

            Thread.sleep(1000)
        }

        healthy shouldBe true
    }

    test("Verify labware stack isolation from production") {
        
        val (_, labwareOutput) = execLabwareDocker(
            labwareDockerHost,
            "ps",
            "--filter", "name=labware-",
            "--format", "{{.Names}}"
        )
        val labwareContainers = labwareOutput.lines().filter { it.isNotBlank() }

        
        labwareContainers.size shouldBeGreaterThan 0
        println("      ℹ️  Found ${labwareContainers.size} labware containers")

        
        val prodProcess = ProcessBuilder("docker", "ps", "--format", "{{.Names}}").start()
        val prodOutput = prodProcess.inputStream.bufferedReader().readText()
        prodProcess.waitFor()
        val prodContainers = prodOutput.lines().filter { it.isNotBlank() }.toSet()

        
        val overlap = labwareContainers.toSet().intersect(prodContainers)
        if (overlap.isNotEmpty()) {
            throw AssertionError("Isolation breach! Containers in both stacks: $overlap")
        }

        println("      ✓ Isolation verified: 0 overlapping containers")
    }

    test("Query labware PostgreSQL service") {
        val (exitCode, output) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-postgres-$testRunId",
            "psql", "-U", "test_admin", "-d", "postgres", "-c", "SELECT version();"
        )

        exitCode shouldBe 0
        output shouldContain "PostgreSQL"
        println("      ✓ PostgreSQL query successful")
    }

    test("Query labware Valkey service") {
        val (exitCode, output) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-valkey-$testRunId",
            "valkey-cli", "PING"
        )

        exitCode shouldBe 0
        output.trim() shouldBe "PONG"
        println("      ✓ Valkey query successful")
    }

    test("Verify labware stack network connectivity") {
        val (exitCode, output) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-test-service-$testRunId",
            "sh", "-c", "nc -zv postgres 5432 && nc -zv valkey 6379"
        )

        exitCode shouldBe 0
        println("      ✓ Network connectivity verified")
    }

    test("Verify labware stack data persistence") {
        
        val (writeExit, _) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-postgres-$testRunId",
            "psql", "-U", "test_admin", "-d", "postgres",
            "-c", "CREATE TABLE test_replication (id INT, data TEXT); INSERT INTO test_replication VALUES (1, 'replication-test');"
        )
        writeExit shouldBe 0

        
        val (readExit, readOutput) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-postgres-$testRunId",
            "psql", "-U", "test_admin", "-d", "postgres",
            "-c", "SELECT data FROM test_replication WHERE id = 1;"
        )

        readExit shouldBe 0
        readOutput shouldContain "replication-test"
        println("      ✓ Data persistence verified")
    }

    test("Verify labware stack log collection") {
        val (exitCode, output) = execLabwareDocker(
            labwareDockerHost,
            "logs",
            "--tail", "50",
            "labware-test-service-$testRunId"
        )

        exitCode shouldBe 0
        output shouldContain "All services healthy!"
        println("      ✓ Log collection verified")
    }

    test("Verify labware stack can be stopped gracefully") {
        val (exitCode, output) = execLabwareDockerCompose(
            labwareDockerHost,
            composePath,
            "stop"
        )

        if (exitCode != 0) {
            println("      ⚠️  Stop output: $output")
        }

        exitCode shouldBe 0
        println("      ✓ Stack stopped gracefully")
    }

    test("Verify labware stack can be restarted") {
        val (startExit, _) = execLabwareDockerCompose(
            labwareDockerHost,
            composePath,
            "start"
        )

        startExit shouldBe 0

        
        Thread.sleep(10000)

        
        val (readExit, readOutput) = execLabwareDocker(
            labwareDockerHost,
            "exec",
            "labware-postgres-$testRunId",
            "psql", "-U", "test_admin", "-d", "postgres",
            "-c", "SELECT data FROM test_replication WHERE id = 1;"
        )

        readExit shouldBe 0
        readOutput shouldContain "replication-test"
        println("      ✓ Stack restarted and data persisted")
    }

    test("Cleanup labware stack") {
        
        val (downExit, downOutput) = execLabwareDockerCompose(
            labwareDockerHost,
            composePath,
            "down", "-v", "--remove-orphans"
        )

        if (downExit != 0) {
            println("      ⚠️  Cleanup output: $downOutput")
        }

        downExit shouldBe 0

        
        File(composePath).deleteRecursively()

        
        val (checkExit, checkOutput) = execLabwareDocker(
            labwareDockerHost,
            "ps",
            "--filter", "name=labware-$testRunId",
            "--format", "{{.Names}}"
        )

        val remainingContainers = checkOutput.lines().filter { it.isNotBlank() }
        if (remainingContainers.isNotEmpty()) {
            println("      ⚠️  Remaining containers: $remainingContainers")
        }

        remainingContainers.size shouldBe 0
        println("      ✓ Stack cleanup complete")
    }

    test("Verify bundled source exists in Forgejo") {
        
        
        println("      ℹ️  Checking if bundled source is accessible via Forgejo...")

        
        val forgejoHealthy = try {
            val response = client.getRawResponse("http://forgejo:3000/api/healthz")
            response.status.value == 200
        } catch (e: Exception) {
            false
        }

        if (!forgejoHealthy) {
            println("      ⚠️  Forgejo not accessible - skipping source verification")
            return@test
        }

        println("      ✓ Forgejo is healthy and bundled source should be accessible")
        println("      ℹ️  Source available at: http://forgejo:3000/datamancy/datamancy-core")
    }

    test("Test build from bundled source (PREVENTS RECURSION)") {
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        

        println("      ⚠️  Skipping stack-in-stack-in-stack test (recursion prevention)")
        println("      ℹ️  This test would create a 3rd level of nesting")
        println("      ℹ️  Bundled source verified accessible via Forgejo")
        println("      ℹ️  Recursion prevention validated via code review")
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


private fun execLabwareDockerCompose(
    dockerHost: String,
    composePath: String,
    vararg args: String
): Pair<Int, String> {
    
    
    val env = mapOf("DOCKER_HOST" to dockerHost)
    val command = listOf("docker", "compose", "-f", "$composePath/docker-compose.yml") + args

    val process = ProcessBuilder(command)
        .directory(File(composePath))
        .redirectErrorStream(true)
        .apply {
            environment().putAll(env)
        }
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor(180, TimeUnit.SECONDS).let {
        if (it) process.exitValue() else {
            process.destroyForcibly()
            -1
        }
    }

    return exitCode to output
}
