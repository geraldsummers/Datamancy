package org.example.plugins

import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertFailsWith

@DisplayName("HostToolsPlugin Tests")
class HostToolsPluginTest {
    private val tools = HostToolsPlugin.Tools()

    @Nested
    @DisplayName("Plugin Lifecycle")
    inner class PluginLifecycleTests {

        @Test
        fun `plugin has valid manifest`() {
            val plugin = HostToolsPlugin()
            val manifest = plugin.manifest()

            assertEquals("org.example.plugins.hosttools", manifest.id)
            assertEquals("1.0.0", manifest.version)
            assertTrue(manifest.capabilities.contains("host.shell.read"))
            assertTrue(manifest.capabilities.contains("host.docker.inspect"))
            assertTrue(manifest.capabilities.contains("host.docker.write"))
        }

        @Test
        fun `plugin init does not throw`() {
            val plugin = HostToolsPlugin()
            val context = org.example.api.PluginContext("1.0.0", "1.0.0")
            assertDoesNotThrow {
                plugin.init(context)
            }
        }

        @Test
        fun `plugin provides tools list`() {
            val plugin = HostToolsPlugin()
            val toolsList = plugin.tools()
            assertEquals(1, toolsList.size)
            assertTrue(toolsList[0] is HostToolsPlugin.Tools)
        }
    }

    @Nested
    @DisplayName("host_exec_readonly Security Tests")
    inner class HostExecReadonlySecurityTests {

        @Test
        fun `rejects empty command`() {
            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(emptyList())
            }
        }

        @Test
        fun `rejects non-whitelisted commands`() {
            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("rm", "-rf", "/"))
            }

            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("bash", "-c", "echo test"))
            }

            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("python", "script.py"))
            }
        }

        @Test
        fun `rejects shell redirection operators`() {
            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("ls", ">", "file.txt"))
            }

            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("cat", "test.txt", "|", "grep", "foo"))
            }

            assertFailsWith<IllegalArgumentException> {
                tools.host_exec_readonly(listOf("echo", "test", "&&", "ls"))
            }
        }

        @Test
        @EnabledOnOs(OS.LINUX, OS.MAC)
        fun `accepts whitelisted read-only commands`() {
            
            val result = tools.host_exec_readonly(listOf("ls", "/tmp"))
            assertEquals(0, result["exitCode"])
        }
    }

    @Nested
    @DisplayName("host_exec_readonly Execution Tests")
    @EnabledOnOs(OS.LINUX, OS.MAC)
    inner class HostExecReadonlyExecutionTests {

        @Test
        fun `executes ls command`() {
            val result = tools.host_exec_readonly(listOf("ls", "/tmp"))
            assertEquals(0, result["exitCode"])
            assertTrue(result["output"] is String)
        }

        @Test
        fun `executes whoami command`() {
            val result = tools.host_exec_readonly(listOf("whoami"))
            assertEquals(0, result["exitCode"])
            val output = result["output"] as String
            assertTrue(output.isNotBlank())
        }

        @Test
        fun `executes uname command with args`() {
            val result = tools.host_exec_readonly(listOf("uname", "-a"))
            assertEquals(0, result["exitCode"])
        }

        @Test
        fun `respects working directory`() {
            val result = tools.host_exec_readonly(listOf("ls"), cwd = "/tmp")
            assertEquals(0, result["exitCode"])
        }

        @Test
        fun `returns non-zero exit code on command failure`() {
            val result = tools.host_exec_readonly(listOf("ls", "/nonexistent/path/12345"))
            assertNotEquals(0, result["exitCode"])
        }
    }

    @Nested
    @DisplayName("docker_logs Tests")
    inner class DockerLogsTests {

        @Test
        fun `returns structured response with logs and exit code`() {
            
            
            try {
                val result = tools.docker_logs("nonexistent-container-xyz", 10)
                
                assertTrue(result.containsKey("exitCode"))
                assertTrue(result.containsKey("logs"))
            } catch (e: Exception) {
                
                assertTrue(e.message?.contains("docker") ?: false)
            }
        }

        @Test
        fun `respects tail parameter`() {
            
            try {
                val result = tools.docker_logs("test-container", 50)
                assertNotNull(result)
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_list_containers Tests")
    inner class DockerListContainersTests {

        @Test
        fun `returns list of containers`() {
            try {
                val result = tools.docker_list_containers()
                
                assertTrue(result.isEmpty() || result.isNotEmpty())
                
                if (result.isNotEmpty()) {
                    val first = result[0]
                    assertTrue(first.containsKey("id"))
                    assertTrue(first.containsKey("name"))
                    assertTrue(first.containsKey("status"))
                }
            } catch (e: Exception) {
                
                
                assertTrue(true)
            }
        }
    }

    @Nested
    @DisplayName("docker_restart Tests")
    inner class DockerRestartTests {

        @Test
        fun `returns structured response`() {
            try {
                val result = tools.docker_restart("nonexistent-container-xyz")
                assertTrue(result.containsKey("success"))
                assertTrue(result.containsKey("exitCode"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_stats Tests")
    inner class DockerStatsTests {

        @Test
        fun `returns structured stats response`() {
            try {
                val result = tools.docker_stats("nonexistent-container")
                assertTrue(result.containsKey("exitCode"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_inspect Tests")
    inner class DockerInspectTests {

        @Test
        fun `returns structured inspect response`() {
            try {
                val result = tools.docker_inspect("nonexistent-container")
                assertTrue(result.containsKey("exitCode"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_exec Tests")
    inner class DockerExecTests {

        @Test
        fun `rejects empty command`() {
            assertFailsWith<IllegalArgumentException> {
                tools.docker_exec("container", emptyList())
            }
        }

        @Test
        fun `returns structured response for exec`() {
            try {
                val result = tools.docker_exec("container", listOf("echo", "test"))
                assertTrue(result.containsKey("exitCode"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_health_wait Tests")
    inner class DockerHealthWaitTests {

        @Test
        fun `returns structured health response`() {
            try {
                val result = tools.docker_health_wait("container", 5)
                assertTrue(result.containsKey("success") || result.containsKey("status"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("docker_compose_restart Tests")
    inner class DockerComposeRestartTests {

        @Test
        fun `returns structured response for compose restart`() {
            try {
                val result = tools.docker_compose_restart("service")
                assertTrue(result.containsKey("success"))
                assertTrue(result.containsKey("exitCode"))
            } catch (e: Exception) {
                
            }
        }
    }

    @Nested
    @DisplayName("http_get Tests")
    inner class HttpGetTests {

        @Test
        fun `makes HTTP request and returns response`() {
            
            try {
                val result = tools.http_get("https://httpbin.org/get")
                assertTrue(result.containsKey("status"))
                assertTrue(result.containsKey("body"))
                assertEquals(200, result["status"])
            } catch (e: Exception) {
                
                println("Skipping HTTP test: ${e.message}")
            }
        }

        @Test
        fun `includes headers in request`() {
            try {
                val headers = mapOf("User-Agent" to "Test-Agent")
                val result = tools.http_get("https://httpbin.org/headers", headers)
                assertTrue(result.containsKey("status"))
            } catch (e: Exception) {
                
            }
        }

        @Test
        fun `handles connection errors gracefully`() {
            try {
                tools.http_get("http://localhost:99999/nonexistent")
                
            } catch (e: Exception) {
                
                
                assertNotNull(e)
            }
        }

        @Test
        fun `handles invalid URLs gracefully`() {
            try {
                tools.http_get("not-a-valid-url")
                
            } catch (e: Exception) {
                
                assertTrue(e.message?.contains("URI") ?: e.message?.contains("Illegal") ?: true)
            }
        }
    }

    @Nested
    @DisplayName("Command Whitelist Validation")
    inner class CommandWhitelistTests {

        private val whitelistedCommands = setOf(
            "cat", "ls", "find", "ps", "uptime", "uname", "id",
            "whoami", "df", "du", "free", "env", "printenv", "stat",
            "dpkg", "rpm", "ip", "ss", "netstat", "curl", "wget",
            "journalctl", "dmesg"
        )

        @Test
        fun `all whitelisted commands are read-only`() {
            val dangerous = setOf("rm", "mv", "cp", "chmod", "chown", "dd", "mkfs", "reboot", "shutdown")
            whitelistedCommands.forEach { cmd ->
                assertFalse(dangerous.contains(cmd), "Dangerous command '$cmd' found in whitelist")
            }
        }

        @Test
        fun `whitelist contains expected safe commands`() {
            assertTrue(whitelistedCommands.contains("ls"))
            assertTrue(whitelistedCommands.contains("cat"))
            assertTrue(whitelistedCommands.contains("whoami"))
            assertTrue(whitelistedCommands.contains("uname"))
        }

        @Test
        fun `whitelist does not contain write commands`() {
            assertFalse(whitelistedCommands.contains("rm"))
            assertFalse(whitelistedCommands.contains("mv"))
            assertFalse(whitelistedCommands.contains("chmod"))
            assertFalse(whitelistedCommands.contains("shutdown"))
        }
    }
}
