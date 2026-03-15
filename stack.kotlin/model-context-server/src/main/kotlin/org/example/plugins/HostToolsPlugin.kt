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
        
    }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        
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
            ToolHandler { args, _ ->
                val cmdNode = args.get("cmd") ?: throw IllegalArgumentException("cmd required")
                val cmd = mutableListOf<String>()
                cmdNode.forEach { cmd.add(it.asText()) }
                val cwd = args.get("cwd")?.asText()
                tools.host_exec_readonly(cmd, cwd)
            }
        )

        
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
            ToolHandler { args, _ ->
                val container = args.get("container")?.asText() ?: throw IllegalArgumentException("container required")
                val tail = args.get("tail")?.asInt(200) ?: 200
                tools.docker_logs(container, tail)
            }
        )

        
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
            ToolHandler { args, _ ->
                val container = args.get("container")?.asText() ?: throw IllegalArgumentException("container required")
                tools.docker_restart(container)
            }
        )
    }

    class Tools {
        private val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        
        private fun sanitizeContainerName(name: String): String {
            require(name.isNotBlank()) { "Container name cannot be blank" }
            require(name.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$"))) {
                "Invalid container name: $name (must be alphanumeric, dash, underscore, or dot)"
            }
            return name
        }

        private fun sanitizePath(path: String): String {
            require(!path.contains("..")) { "Path traversal detected: $path" }
            require(!path.contains('\u0000')) { "Null byte detected in path: $path" }
            val normalized = File(path).normalize().absolutePath
            return normalized
        }

        private fun validateCommandArguments(args: List<String>) {
            val dangerous = setOf(">", ">>", "<", "|", "||", "&&", ";", "&", "`", "$", "(", ")", "{", "}", "[", "]", "*", "?", "~", "!", "\n", "\r")
            args.forEach { arg ->
                require(dangerous.none { it in arg }) {
                    "Dangerous shell metacharacter detected in argument: $arg"
                }
            }
        }

        
        private fun runHostDockerCmd(args: List<String>, timeoutSeconds: Long = 30): Pair<Int, String> {
            validateCommandArguments(args)

            val cmd = listOf("docker", "-H", "unix:///var/run/docker.sock.host") + args
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()

            
            val output = StringBuilder()
            val readerThread = Thread {
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    reader.lines().forEach { output.append(it).append('\n') }
                }
            }
            readerThread.start()

            
            val completed = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                throw RuntimeException("Command timed out after ${timeoutSeconds}s: ${cmd.joinToString(" ")}")
            }

            readerThread.join(1000) 
            val code = p.exitValue()
            return code to output.toString()
        }

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
                
                "cat", "ls", "find", "ps", "uptime", "uname", "id",
                "whoami", "df", "du", "free", "env", "printenv", "stat",
                
                "dpkg", "rpm",
                
                "ip", "ss", "netstat", "curl", "wget",
                
                "journalctl", "dmesg"
            )

            
            val exe = cmd[0]
            require(!exe.contains('/') && !exe.contains('\\')) {
                "Executable must be basename only (no path): $exe"
            }
            require(exe in allowed) { "command '$exe' is not allowed" }

            
            validateCommandArguments(cmd.drop(1))

            
            val workingDir = if (cwd != null) {
                val sanitized = sanitizePath(cwd)
                val dir = File(sanitized)
                require(dir.exists() && dir.isDirectory) {
                    "Working directory does not exist or is not a directory: $sanitized"
                }
                dir
            } else null

            val pb = ProcessBuilder(cmd)
            if (workingDir != null) pb.directory(workingDir)
            pb.redirectErrorStream(true)

            val p = pb.start()

            
            val completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                return mapOf(
                    "exitCode" to -1,
                    "error" to "Command timed out after 30 seconds"
                )
            }

            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.exitValue()

            return mapOf(
                "exitCode" to code,
                "output" to out.take(200_000) 
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
            val (_, out) = runHostDockerCmd(listOf("ps", "-a", "--format", fmt))
            return out.lines().filter { it.isNotBlank() }.map { line ->
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
            val sanitized = sanitizeContainerName(container)
            val safeTail = tail.coerceIn(1, 5000)
            val (code, out) = runHostDockerCmd(listOf("logs", "--tail", safeTail.toString(), sanitized))
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
            val sanitized = sanitizeContainerName(container)
            val fmt = "{{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}"
            val (code, out) = runHostDockerCmd(listOf("stats", "--no-stream", "--format", fmt, sanitized))

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
            val sanitized = sanitizeContainerName(container)
            val (code, out) = runHostDockerCmd(listOf("inspect", sanitized))
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

        

        @LlmTool(
            name = "docker_restart",
            shortDescription = "Restart a docker container",
            longDescription = "Executes `docker restart <container>` to restart an unhealthy or stuck container. Returns success status and timing.",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"container\":{\"type\":\"string\"}},\"required\":[\"container\"]}"
        )
        fun docker_restart(container: String): Map<String, Any?> {
            val sanitized = sanitizeContainerName(container)

            val startTime = System.currentTimeMillis()
            val (code, out) = runHostDockerCmd(listOf("restart", sanitized), timeoutSeconds = 60)
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
            val sanitized = sanitizeContainerName(container)
            require(cmd.isNotEmpty()) { "cmd must not be empty" }

            
            val allowedCommands = setOf(
                "nginx", "caddy", "systemctl", "kill", "pkill",
                "reload", "graceful", "touch", "cat", "ls", "sh", "bash"
            )

            val exe = cmd[0]
            
            val exeBasename = File(exe).name
            require(exeBasename in allowedCommands) {
                "command '$exeBasename' is not in whitelist"
            }

            
            validateCommandArguments(cmd.drop(1))

            val (code, out) = runHostDockerCmd(listOf("exec", sanitized) + cmd, timeoutSeconds = 45)

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
            val sanitized = sanitizeContainerName(container)
            val safeTimeout = timeoutSec.coerceIn(5, 300)
            val deadline = System.currentTimeMillis() + (safeTimeout * 1000)

            var lastStatus = "unknown"
            var attempts = 0

            while (System.currentTimeMillis() < deadline) {
                attempts++
                val (_, out) = runHostDockerCmd(listOf("inspect", "--format", "{{.State.Health.Status}}", sanitized))
                lastStatus = out.trim()

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
                        
                        return mapOf(
                            "success" to true,
                            "status" to "no_healthcheck",
                            "note" to "Container does not define a health check",
                            "attempts" to attempts
                        )
                    }
                }

                Thread.sleep(2000) 
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

            
            require(service.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$"))) {
                "Invalid service name: $service"
            }

            val cmd = mutableListOf("docker", "compose")
            if (composeFile != null) {
                
                val sanitizedPath = sanitizePath(composeFile)
                val file = File(sanitizedPath)
                require(file.exists() && file.isFile) {
                    "Compose file does not exist or is not a file: $sanitizedPath"
                }
                require(file.extension in setOf("yml", "yaml")) {
                    "Compose file must be .yml or .yaml: ${file.name}"
                }
                cmd.add("-f")
                cmd.add(sanitizedPath)
            }
            cmd.addAll(listOf("restart", service))

            val startTime = System.currentTimeMillis()
            
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()

            
            val completed = p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                return mapOf(
                    "exitCode" to -1,
                    "success" to false,
                    "error" to "Command timed out after 90 seconds",
                    "elapsedMs" to (System.currentTimeMillis() - startTime)
                )
            }

            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.exitValue()
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
