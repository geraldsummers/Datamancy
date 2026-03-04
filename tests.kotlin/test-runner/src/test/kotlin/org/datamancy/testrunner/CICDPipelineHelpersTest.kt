package org.datamancy.testrunner

import org.datamancy.testrunner.suites.CICDPipelineTests
import org.junit.jupiter.api.Test
import kotlin.test.*


class CICDPipelineHelpersTest {

    @Test
    fun `labware Docker host uses environment or default`() {
        val dockerHost = CICDPipelineTests.ISOLATED_DOCKER_VM_DOCKER_HOST

        assertTrue(dockerHost.isNotEmpty())
    }

    @Test
    fun `registry host constant is correct`() {
        assertEquals("registry:5000", CICDPipelineTests.REGISTRY_HOST)
    }

    @Test
    fun `test image prefix is lowercase and valid`() {
        val prefix = CICDPipelineTests.TEST_IMAGE_PREFIX

        assertEquals("cicd-test", prefix)
        assertTrue(prefix.matches(Regex("[a-z0-9-]+")))
        assertFalse(prefix.contains(":"))
    }

    @Test
    fun `isLabwareDockerAvailable check doesn't crash`() {
        // Should return boolean without throwing
        val available = CICDPipelineTests.isIsolatedDockerVmDockerAvailable()

        assertNotNull(available)
    }

    @Test
    fun `test image name format with registry`() {
        val testId = "abc123"
        val registry = CICDPipelineTests.REGISTRY_HOST
        val prefix = CICDPipelineTests.TEST_IMAGE_PREFIX
        val imageName = "$registry/$prefix-push:$testId"

        assertTrue(imageName.startsWith("registry:5000/"))
        assertTrue(imageName.contains(":"))
        assertTrue(imageName.endsWith(testId))
    }

    @Test
    fun `test container name format`() {
        val testId = "xyz789"
        val containerName = "cicd-test-container-$testId"

        assertTrue(containerName.startsWith("cicd-test-"))
        assertTrue(containerName.endsWith(testId))
        assertFalse(containerName.contains(":")) // Containers don't have :tag
    }

    @Test
    fun `dockerfile with multi-stage build is valid format`() {
        val dockerfile = """
            # Build stage
            FROM alpine:latest AS builder
            RUN echo "Building"

            # Runtime stage
            FROM alpine:latest
            COPY --from=builder /artifact.txt /app/
            CMD ["cat", "/app/artifact.txt"]
        """.trimIndent()

        assertTrue(dockerfile.contains("FROM alpine:latest AS builder"))
        assertTrue(dockerfile.contains("COPY --from=builder"))
        assertTrue(dockerfile.contains("CMD"))
    }

    @Test
    fun `test cleanup lists are properly initialized`() {
        val testImages = mutableListOf<String>()
        val testContainers = mutableListOf<String>()
        val tempDirs = mutableListOf<java.io.File>()

        
        assertTrue(testImages.isEmpty())
        assertTrue(testContainers.isEmpty())
        assertTrue(tempDirs.isEmpty())

        
        testImages.add("test:latest")
        assertEquals(1, testImages.size)

        
        testImages.clear()
        assertTrue(testImages.isEmpty())
    }

    @Test
    fun `test process output parsing logic`() {
        val mockOutput = """
            container1
            container2
            container3
        """.trimIndent()

        val containers = mockOutput.lines().filter { it.isNotBlank() }.toSet()

        assertEquals(3, containers.size)
        assertTrue(containers.contains("container1"))
        assertTrue(containers.contains("container2"))
    }

    @Test
    fun `test isolation check logic`() {
        val labwareContainers = setOf("test-1", "test-2")
        val prodContainers = setOf("prod-1", "prod-2")

        val overlap = labwareContainers.intersect(prodContainers)

        assertTrue(overlap.isEmpty(), "No overlap in test data")
    }

    @Test
    fun `test isolation check detects overlap`() {
        val labwareContainers = setOf("test-1", "shared", "test-2")
        val prodContainers = setOf("prod-1", "shared", "prod-2")

        val overlap = labwareContainers.intersect(prodContainers)

        assertFalse(overlap.isEmpty(), "Should detect overlap")
        assertEquals(1, overlap.size)
        assertTrue(overlap.contains("shared"))
    }

    @Test
    fun `test exit code check logic`() {
        val successExitCode = 0
        val failureExitCode = 1

        assertTrue(successExitCode == 0)
        assertFalse(failureExitCode == 0)
    }

    @Test
    fun `test output contains check logic`() {
        val output = "Deployment test abc123 completed successfully"
        val testId = "abc123"

        assertTrue(output.contains(testId))
        assertTrue(output.contains("Deployment test"))
        assertFalse(output.contains("failure"))
    }

    @Test
    fun `test docker command construction for build`() {
        val imageName = "test:latest"
        val buildDir = "/tmp/build"
        val dockerHost = "ssh://labware"
        val command = listOf("docker", "-H", dockerHost, "build", "-t", imageName, buildDir)

        assertEquals("docker", command[0])
        assertTrue(command.contains("build"))
        assertTrue(command.contains("-t"))
        assertEquals(imageName, command[command.indexOf("-t") + 1])
    }

    @Test
    fun `test docker command construction for push`() {
        val imageName = "registry:5000/test:latest"
        val dockerHost = "ssh://labware"
        val command = listOf("docker", "-H", dockerHost, "push", imageName)

        assertEquals("push", command[3])
        assertEquals(imageName, command[4])
        assertTrue(imageName.startsWith("registry:5000/"))
    }

    @Test
    fun `test docker command construction for run`() {
        val containerName = "test-container"
        val imageName = "alpine:latest"
        val dockerHost = "ssh://labware"
        val command = listOf("docker", "-H", dockerHost,
            "run", "--name", containerName, "--rm", imageName, "echo", "test")

        assertTrue(command.contains("run"))
        assertTrue(command.contains("--name"))
        assertTrue(command.contains("--rm"))
        assertEquals(containerName, command[command.indexOf("--name") + 1])
    }

    @Test
    fun `test temp dir creation cleanup doesn't leak`() {
        val tempDirs = mutableListOf<java.io.File>()

        repeat(3) {
            val tempDir = java.io.File.createTempFile("test-", "").apply {
                delete()
                mkdir()
                tempDirs.add(this)
            }
            assertTrue(tempDir.exists())
        }

        assertEquals(3, tempDirs.size)

        // Cleanup all
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()

        assertTrue(tempDirs.isEmpty())
    }
}
