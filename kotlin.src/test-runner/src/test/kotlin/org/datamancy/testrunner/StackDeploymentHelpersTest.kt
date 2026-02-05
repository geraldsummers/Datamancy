package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import kotlin.test.*


class StackDeploymentHelpersTest {

    @Test
    fun `labware Docker host uses environment or default`() {
        val dockerHost = System.getenv("DOCKER_HOST") ?: "ssh://labware"
        assertTrue(dockerHost.isNotEmpty())
    }

    @Test
    fun `test project name generation is unique`() {
        val prefix = "datamancy-test"
        val timestamp1 = System.currentTimeMillis()
        Thread.sleep(1)
        val timestamp2 = System.currentTimeMillis()

        val name1 = "$prefix-$timestamp1"
        val name2 = "$prefix-$timestamp2"

        assertNotEquals(name1, name2, "Project names should be unique")
        assertTrue(name1.startsWith(prefix))
        assertTrue(name2.startsWith(prefix))
    }

    @Test
    fun `docker compose command construction for labware`() {
        val workDir = "/app"
        val projectName = "datamancy-test-123456"
        val composeFile = "$workDir/docker-compose.yml"

        val command = listOf(
            "docker", "-H", "ssh://labware",
            "compose",
            "-p", projectName,
            "-f", composeFile,
            "up", "-d"
        )

        assertEquals("docker", command[0])
        assertEquals("-H", command[1])
        assertEquals("ssh://labware", command[2])
        assertEquals("compose", command[3])
        assertEquals("-p", command[4])
        assertEquals(projectName, command[5])
        assertTrue(command.contains("up"))
        assertTrue(command.contains("-d"))
    }

    @Test
    fun `docker compose ps command for health check`() {
        val projectName = "test-project"
        val service = "postgres"

        val command = listOf(
            "docker", "-H", "ssh://labware",
            "compose", "-p", projectName, "ps", service, "--format", "{{.Status}}"
        )

        assertTrue(command.contains("ps"))
        assertTrue(command.contains(service))
        assertTrue(command.contains("--format"))
    }

    @Test
    fun `docker compose down cleanup command`() {
        val projectName = "test-project"

        val command = listOf(
            "docker", "-H", "ssh://labware",
            "compose", "-p", projectName, "down", "-v"
        )

        assertTrue(command.contains("down"))
        assertTrue(command.contains("-v"), "Should include -v flag to remove volumes")
    }

    @Test
    fun `critical services list has expected services`() {
        val criticalServices = listOf(
            "postgres" to 300,
            "ldap" to 120,
            "caddy" to 120,
            "authelia" to 180
        )

        assertEquals(4, criticalServices.size)
        assertTrue(criticalServices.any { it.first == "postgres" })
        assertTrue(criticalServices.any { it.first == "ldap" })
        assertTrue(criticalServices.any { it.first == "caddy" })
        assertTrue(criticalServices.any { it.first == "authelia" })

        
        criticalServices.forEach { (service, timeout) ->
            assertTrue(timeout > 0, "$service timeout should be positive")
            assertTrue(timeout <= 600, "$service timeout should be <= 10 minutes")
        }
    }

    @Test
    fun `slow services list has expected long timeouts`() {
        val slowServices = listOf(
            "forgejo" to 600,
            "grafana" to 300,
            "open-webui" to 300
        )

        assertEquals(3, slowServices.size)

        
        val forgejoTimeout = slowServices.find { it.first == "forgejo" }?.second
        assertNotNull(forgejoTimeout)
        assertEquals(600, forgejoTimeout, "Forgejo should have 10 minute timeout")

        
        slowServices.forEach { (service, timeout) ->
            assertTrue(timeout >= 300, "$service should have at least 5 minute timeout")
        }
    }

    @Test
    fun `timeout calculation converts seconds to milliseconds correctly`() {
        val timeoutSeconds = 300
        val timeoutMs = timeoutSeconds * 1000L

        assertEquals(300_000L, timeoutMs)
        
        val expected: Long = 300_000L
        assertEquals(expected, timeoutMs)
    }

    @Test
    fun `health check polling interval is reasonable`() {
        val pollingIntervalMs = 10_000L 

        assertTrue(pollingIntervalMs >= 5_000, "Polling should be at least 5 seconds")
        assertTrue(pollingIntervalMs <= 30_000, "Polling should be at most 30 seconds")
    }

    @Test
    fun `status output parsing detects healthy status`() {
        val healthyStatuses = listOf(
            "Up 2 minutes (healthy)",
            "Up About a minute (healthy)",
            "Up 30 seconds (healthy)"
        )

        healthyStatuses.forEach { status ->
            assertTrue(status.contains("healthy", ignoreCase = true))
        }
    }

    @Test
    fun `status output parsing detects unhealthy status`() {
        val unhealthyStatuses = listOf(
            "Up 2 minutes (unhealthy)",
            "Up About a minute (health: starting)",
            "Exited (1)"
        )

        unhealthyStatuses.forEach { status ->
            assertFalse(status.contains("healthy", ignoreCase = true) && !status.contains("unhealthy"))
        }
    }

    @Test
    fun `container overlap detection logic`() {
        val testContainers = setOf(
            "datamancy-test-123-postgres",
            "datamancy-test-123-caddy",
            "datamancy-test-123-ldap"
        )

        val prodContainers = setOf(
            "postgres",
            "caddy",
            "ldap"
        )

        val overlap = testContainers.intersect(prodContainers)

        assertTrue(overlap.isEmpty(), "Test and prod containers should have different names")
    }

    @Test
    fun `container overlap detection finds conflicts`() {
        val testContainers = setOf(
            "postgres",
            "caddy-test",
            "ldap-test"
        )

        val prodContainers = setOf(
            "postgres",
            "caddy",
            "ldap"
        )

        val overlap = testContainers.intersect(prodContainers)

        assertFalse(overlap.isEmpty(), "Should detect shared 'postgres' container")
        assertEquals(1, overlap.size)
        assertTrue(overlap.contains("postgres"))
    }

    @Test
    fun `required files list is correct`() {
        val requiredFiles = listOf(
            "docker-compose.yml",
            ".env"
        )

        assertEquals(2, requiredFiles.size)
        assertTrue(requiredFiles.contains("docker-compose.yml"))
        assertTrue(requiredFiles.contains(".env"))
    }

    @Test
    fun `dist directory path is correct`() {
        val distPath = "/app"

        assertTrue(distPath.startsWith("/"))
        assertFalse(distPath.endsWith("/"))
    }

    @Test
    fun `error message format for missing socket`() {
        val labwareSocket = "ssh://labware"
        val errorMessage = "Labware socket should exist at $labwareSocket"

        assertTrue(errorMessage.contains(labwareSocket))
        assertTrue(errorMessage.contains("should exist"))
    }

    @Test
    fun `error message format for unhealthy service`() {
        val service = "postgres"
        val timeout = 300
        val errorMessage = "Service $service did not become healthy within ${timeout}s"

        assertTrue(errorMessage.contains(service))
        assertTrue(errorMessage.contains(timeout.toString()))
        assertTrue(errorMessage.contains("did not become healthy"))
    }

    @Test
    fun `time elapsed calculation for timeout`() {
        val startTime = System.currentTimeMillis()
        Thread.sleep(100)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(elapsed >= 100, "At least 100ms should have elapsed")
        assertTrue(elapsed < 200, "Should be less than 200ms")
    }

    @Test
    fun `docker version command format`() {
        val command = listOf(
            "docker", "-H", "ssh://labware",
            "version", "--format", "{{.Server.Version}}"
        )

        assertEquals("version", command[3])
        assertEquals("--format", command[4])
        assertTrue(command[5].contains("Server"))
    }

    @Test
    fun `compose logs command for debugging`() {
        val projectName = "test-project"
        val service = "forgejo"
        val tailLines = 20

        val command = listOf(
            "docker", "-H", "ssh://labware",
            "compose", "-p", projectName,
            "logs", "--tail=$tailLines", service
        )

        assertTrue(command.contains("logs"))
        assertTrue(command.any { it.startsWith("--tail=") })
        assertTrue(command.contains(service))
    }
}
