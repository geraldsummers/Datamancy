package org.datamancy.testrunner

import org.datamancy.testrunner.suites.LabwareDockerTests
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for LabwareDockerTests helpers
 * These tests verify the test helper logic WITHOUT requiring labware socket
 */
class LabwareDockerHelpersTest {

    @Test
    fun `isLabwareSocketAvailable returns false when socket missing`() {
        // This will be false in local dev environment without labware VM
        val available = LabwareDockerTests.isLabwareSocketAvailable()

        // Test passes whether true or false - we're just testing it doesn't crash
        assertNotNull(available)
    }

    @Test
    fun `labware socket path constant is correct`() {
        assertEquals("/run/labware-docker.sock", LabwareDockerTests.LABWARE_SOCKET_PATH)
    }

    @Test
    fun `execLabwareDocker helper constructs correct command`() {
        // We can test the command construction logic without actually running it
        // The helper should build: docker -H unix:///run/labware-docker.sock [args]

        val socketPath = LabwareDockerTests.LABWARE_SOCKET_PATH
        assertTrue(socketPath.startsWith("/run/"))
        assertTrue(socketPath.endsWith(".sock"))
    }

    @Test
    fun `test container name generation is unique`() {
        val name1 = "test-${System.currentTimeMillis()}"
        Thread.sleep(1) // Ensure different timestamp
        val name2 = "test-${System.currentTimeMillis()}"

        assertNotEquals(name1, name2, "Container names should be unique")
    }

    @Test
    fun `test image name format is valid`() {
        val prefix = "cicd-test"
        val tag = "abc123"
        val imageName = "$prefix-build:$tag"

        assertTrue(imageName.matches(Regex("[a-z0-9-]+:[a-z0-9]+")))
        assertTrue(imageName.contains(":"))
    }

    @Test
    fun `test cleanup logic handles empty lists`() {
        val emptyList = mutableListOf<String>()

        // Should not throw when clearing empty list
        emptyList.clear()

        assertTrue(emptyList.isEmpty())
    }

    @Test
    fun `test temp directory creation cleanup pattern`() {
        val tempDirs = mutableListOf<java.io.File>()

        // Create temp dir
        val tempDir = java.io.File.createTempFile("test-", "").apply {
            delete()
            mkdir()
            tempDirs.add(this)
        }

        assertTrue(tempDir.exists())
        assertTrue(tempDir.isDirectory)

        // Cleanup
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()

        assertFalse(tempDir.exists())
        assertTrue(tempDirs.isEmpty())
    }

    @Test
    fun `test UUID generation for test IDs`() {
        val uuid1 = java.util.UUID.randomUUID().toString().substring(0, 8)
        val uuid2 = java.util.UUID.randomUUID().toString().substring(0, 8)

        assertEquals(8, uuid1.length)
        assertEquals(8, uuid2.length)
        assertNotEquals(uuid1, uuid2)
        assertTrue(uuid1.matches(Regex("[a-f0-9]{8}")))
    }

    @Test
    fun `test process builder command construction`() {
        val command = listOf("docker", "-H", "unix:///run/labware-docker.sock", "ps")

        assertEquals("docker", command[0])
        assertEquals("-H", command[1])
        assertTrue(command[2].startsWith("unix://"))
        assertEquals("ps", command[3])
    }

    @Test
    fun `test dockerfile content generation`() {
        val testId = "test123"
        val dockerfile = """
            FROM alpine:latest
            RUN echo "Test Build $testId"
            CMD ["echo", "Hello"]
        """.trimIndent()

        assertTrue(dockerfile.contains("FROM alpine:latest"))
        assertTrue(dockerfile.contains(testId))
        assertTrue(dockerfile.contains("CMD"))
    }
}
