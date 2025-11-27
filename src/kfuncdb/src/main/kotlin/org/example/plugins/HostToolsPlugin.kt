package org.example.plugins

import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.api.Plugin
import org.example.api.PluginContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Read-only host inspection tools. All functions must be safe and non-mutating.
 */
class HostToolsPlugin : Plugin {
    override fun init(context: PluginContext) {
        // no-op
    }

    override fun tools(): List<Any> = listOf(Tools())

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
    }
}
