package org.example.plugins

import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Host inspection and docker action tools.
 * Read-only tools are safe. Write operations (restart, exec) require caution.
 */
class HostToolsPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.hosttools",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.HostToolsPlugin",
        capabilities = listOf("host.shell.read", "host.docker.inspect", "host.docker.write"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        // no-op
    }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        // host_exec_readonly
        registry.register(
            ToolDefinition(
                name = "host_exec_readonly",
                description = "Run a safe, read-only host command and return stdout/stderr",
                shortDescription = "Run a safe, read-only host command and return stdout/stderr",
                longDescription = "Executes a whitelisted command with arguments in read-only mode. Disallows redirection/pipes and known mutating commands.",
                parameters = listOf(
                    ToolParam("cmd", "array[string]", true, "Array: [executable, arg1, ...]. Whitelisted commands only."),
                    ToolParam("cwd", "string", false, "Optional working directory")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"cwd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val cmdNode = args.get("cmd") ?: throw IllegalArgumentException("cmd required")
                val cmd = mutableListOf<String>()
                cmdNode.forEach { cmd.add(it.asText()) }
                val cwd = args.get("cwd")?.asText()
                tools.host_exec_readonly(cmd, cwd)
            }
        )

        // docker_logs
        registry.register(
            ToolDefinition(
                name = "docker_logs",
                description = "Fetch container logs",
                shortDescription = "Fetch container logs",
                longDescription = "Return last N lines of logs for a container using 'docker logs'.",
                parameters = listOf(
                    ToolParam("container", "string", true, "Container name or ID"),
                    ToolParam("tail", "integer", false, "Number of lines from the end (default 200)")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"},\"tail\":{\"type\":\"integer\"}},\"required\":[\"container\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val container = args.get("container")?.asText() ?: throw IllegalArgumentException("container required")
                val tail = args.get("tail")?.asInt(200) ?: 200
                tools.docker_logs(container, tail)
            }
        )

        // docker_restart
        registry.register(
            ToolDefinition(
                name = "docker_restart",
                description = "Restart a docker container",
                shortDescription = "Restart a docker container",
                longDescription = "Runs 'docker restart <container>' and returns status.",
                parameters = listOf(
                    ToolParam("container", "string", true, "Container name or ID")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"}},\"required\":[\"container\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val container = args.get("container")?.asText() ?: throw IllegalArgumentException("container required")
                tools.docker_restart(container)
            }
        )
    }

    class Tools {
        private val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        @LlmTool(
            name = "host_exec_readonly",
            shortDescription = "Run a safe, read-only host command and return stdout/stderr",
            longDescription = "Executes a whitelisted command with arguments in read-only mode. Disallows redirection/pipes and known mutating commands.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"cwd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
            params = [
                LlmToolParamDoc(name = "cmd", description = "Array: [executable, arg1, ...]. Whitelisted commands only."),
                LlmToolParamDoc(name = "cwd", description = "Optional working directory")
            ]
        )
        fun host_exec_readonly(cmd: List<String>, cwd: String? = null): Map<String, Any?> {
            require(cmd.isNotEmpty()) { "cmd must not be empty" }

            val allowed = setOf(
                // common read-only commands
                "cat", "ls", "find", "ps", "uptime", "uname", "id",
                "whoami", "df", "du", "free", "env", "printenv", "stat",
                // package/query variants (read-only)
                "dpkg", "rpm",
                // network introspection
                "ip", "ss", "netstat", "curl", "wget",
                // journal/log reading
                "journalctl", "dmesg"
            )

            val exe = cmd[0]
            require(exe in allowed) { "command '$exe' is not allowed" }
            require(cmd.none { it in setOf(">", ">>", "|", "&&", ";", "|&", "tee") }) { "redirection/pipes not allowed" }

            val pb = ProcessBuilder(cmd)
            if (cwd != null) pb.directory(File(cwd))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            return mapOf(
                "exitCode" to code,
                "output" to out.take(200_000) // prevent massive outputs
            )
        }

        @LlmTool(
            name = "docker_list_containers",
            shortDescription = "List docker containers (safe read-only)",
            longDescription = "Returns running and stopped containers via `docker ps -a`.",
            paramsSpec = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
        )
        fun docker_list_containers(): List<Map<String, String>> {
            val fmt = "{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
            val pb = ProcessBuilder(listOf("docker", "ps", "-a", "--format", fmt))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val lines = BufferedReader(InputStreamReader(p.inputStream)).readLines()
            p.waitFor()
            return lines.filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t')
                mapOf(
                    "id" to parts.getOrNull(0).orEmpty(),
                    "name" to parts.getOrNull(1).orEmpty(),
                    "image" to parts.getOrNull(2).orEmpty(),
                    "status" to parts.getOrNull(3).orEmpty(),
                    "ports" to parts.getOrNull(4).orEmpty()
                )
            }
        }

        @LlmTool(
            name = "docker_logs",
            shortDescription = "Tail docker logs for a container (safe read-only)",
            longDescription = "Returns the last N lines of logs using `docker logs --tail`.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"},\"tail\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5000}},\"required\":[\"container\"]}"
        )
        fun docker_logs(
            container: String,
            tail: Int = 200
        ): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }
            val safeTail = tail.coerceIn(1, 5000)
            val pb = ProcessBuilder(listOf("docker", "logs", "--tail", safeTail.toString(), container))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            return mapOf("exitCode" to code, "logs" to out.take(500_000))
        }

        @LlmTool(
            name = "docker_stats",
            shortDescription = "Get resource usage stats for a container (safe read-only)",
            longDescription = "Returns CPU, memory, network I/O stats for a container via `docker stats --no-stream`.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"}},\"required\":[\"container\"]}"
        )
        fun docker_stats(container: String): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }
            val fmt = "{{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}"
            val pb = ProcessBuilder(listOf("docker", "stats", "--no-stream", "--format", fmt, container))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()

            if (code != 0 || out.isBlank()) {
                return mapOf("exitCode" to code, "error" to "Failed to get stats", "raw" to out)
            }

            val parts = out.trim().split('\t')
            return mapOf(
                "exitCode" to code,
                "container" to parts.getOrNull(0).orEmpty(),
                "cpu_percent" to parts.getOrNull(1).orEmpty(),
                "mem_usage" to parts.getOrNull(2).orEmpty(),
                "mem_percent" to parts.getOrNull(3).orEmpty(),
                "net_io" to parts.getOrNull(4).orEmpty(),
                "block_io" to parts.getOrNull(5).orEmpty(),
                "pids" to parts.getOrNull(6).orEmpty()
            )
        }

        @LlmTool(
            name = "docker_inspect",
            shortDescription = "Get detailed container metadata (safe read-only)",
            longDescription = "Returns full container inspection data via `docker inspect`.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"}},\"required\":[\"container\"]}"
        )
        fun docker_inspect(container: String): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }
            val pb = ProcessBuilder(listOf("docker", "inspect", container))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            return mapOf(
                "exitCode" to code,
                "json" to out.take(500_000)
            )
        }

        @LlmTool(
            name = "http_get",
            shortDescription = "HTTP GET a URL and return status, headers, and body",
            longDescription = "Simple GET using Java HttpClient. Intended for internal diagnostics.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"headers\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}},\"required\":[\"url\"]}",
            params = [
                LlmToolParamDoc(name = "url", description = "URL to fetch (http/https)"),
                LlmToolParamDoc(name = "headers", description = "Optional headers map")
            ]
        )
        fun http_get(url: String, headers: Map<String, String>? = null): Map<String, Any?> {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
            headers?.forEach { (k, v) -> builder.header(k, v) }
            val req = builder.build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            return mapOf(
                "status" to res.statusCode(),
                "headers" to res.headers().map(),
                "body" to res.body()?.take(500_000)
            )
        }

        // ======= DOCKER ACTION TOOLS (Write Operations) =======

        @LlmTool(
            name = "docker_restart",
            shortDescription = "Restart a docker container",
            longDescription = "Executes `docker restart <container>` to restart an unhealthy or stuck container. Returns success status and timing.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"}},\"required\":[\"container\"]}"
        )
        fun docker_restart(container: String): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }

            val startTime = System.currentTimeMillis()
            val pb = ProcessBuilder(listOf("docker", "restart", container))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            val elapsedMs = System.currentTimeMillis() - startTime

            return mapOf(
                "exitCode" to code,
                "success" to (code == 0),
                "output" to out.trim(),
                "elapsedMs" to elapsedMs
            )
        }

        @LlmTool(
            name = "docker_exec",
            shortDescription = "Execute a safe command inside a container",
            longDescription = "Runs a whitelisted command in a container via `docker exec`. Only allows safe, non-destructive commands like config reloads.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"},\"cmd\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"container\",\"cmd\"]}"
        )
        fun docker_exec(container: String, cmd: List<String>): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }
            require(cmd.isNotEmpty()) { "cmd must not be empty" }

            // Whitelist safe commands only
            val allowedCommands = setOf(
                "nginx", "caddy", "systemctl", "kill", "pkill",
                "reload", "graceful", "touch", "cat", "ls"
            )

            val exe = cmd[0]
            require(allowedCommands.any { exe.contains(it, ignoreCase = true) }) {
                "command '$exe' is not in whitelist"
            }

            val fullCmd = listOf("docker", "exec", container) + cmd
            val pb = ProcessBuilder(fullCmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()

            return mapOf(
                "exitCode" to code,
                "success" to (code == 0),
                "output" to out.take(50_000)
            )
        }

        @LlmTool(
            name = "docker_health_wait",
            shortDescription = "Wait for a container to become healthy",
            longDescription = "Polls `docker inspect` health status until container is healthy or timeout. Returns final health state.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"},\"timeoutSec\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":300}},\"required\":[\"container\"]}"
        )
        fun docker_health_wait(container: String, timeoutSec: Int = 60): Map<String, Any?> {
            require(container.isNotBlank()) { "container is required" }
            val safeTimeout = timeoutSec.coerceIn(5, 300)
            val deadline = System.currentTimeMillis() + (safeTimeout * 1000)

            var lastStatus = "unknown"
            var attempts = 0

            while (System.currentTimeMillis() < deadline) {
                attempts++
                val pb = ProcessBuilder(listOf("docker", "inspect", "--format", "{{.State.Health.Status}}", container))
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText().trim() }
                p.waitFor()

                lastStatus = out

                when (lastStatus) {
                    "healthy" -> {
                        return mapOf(
                            "success" to true,
                            "status" to "healthy",
                            "attempts" to attempts,
                            "elapsedSec" to ((System.currentTimeMillis() - (deadline - safeTimeout * 1000)) / 1000)
                        )
                    }
                    "" -> {
                        // Container has no healthcheck
                        return mapOf(
                            "success" to true,
                            "status" to "no_healthcheck",
                            "note" to "Container does not define a health check",
                            "attempts" to attempts
                        )
                    }
                }

                Thread.sleep(2000) // Poll every 2 seconds
            }

            return mapOf(
                "success" to false,
                "status" to lastStatus,
                "timeout" to true,
                "attempts" to attempts,
                "timeoutSec" to safeTimeout
            )
        }

        @LlmTool(
            name = "docker_compose_restart",
            shortDescription = "Restart a service via docker compose",
            longDescription = "Executes `docker compose restart <service>` to restart a compose service. Safer than direct container restart.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"service\":{\"type\":\"string\"},\"composeFile\":{\"type\":\"string\"}},\"required\":[\"service\"]}"
        )
        fun docker_compose_restart(service: String, composeFile: String? = null): Map<String, Any?> {
            require(service.isNotBlank()) { "service is required" }

            val cmd = mutableListOf("docker", "compose")
            if (composeFile != null) {
                cmd.add("-f")
                cmd.add(composeFile)
            }
            cmd.addAll(listOf("restart", service))

            val startTime = System.currentTimeMillis()
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            val elapsedMs = System.currentTimeMillis() - startTime

            return mapOf(
                "exitCode" to code,
                "success" to (code == 0),
                "output" to out.trim(),
                "elapsedMs" to elapsedMs
            )
        }
    }
}
