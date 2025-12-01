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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * SSH operations plugin. Intentionally minimal: delegates all safety/allowlisting
 * to the host's forced-command wrapper configured for the target user.
 */
class OpsSshPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.ops",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.OpsSshPlugin",
        capabilities = listOf("host.network.ssh"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )
    private lateinit var cfg: Cfg

    override fun init(context: PluginContext) {
        cfg = Cfg.fromEnv()
    }

    override fun tools(): List<Any> = listOf(Tools(cfg))

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(cfg)
        registry.register(
            ToolDefinition(
                name = "ssh_exec_whitelisted",
                description = "Run an allowed host op via SSH forced-command wrapper",
                shortDescription = "Run an allowed host op via SSH forced-command wrapper",
                longDescription = "Executes a command string over SSH. Server-side forced-command wrapper enforces a strict allowlist (e.g., docker logs/restart).",
                parameters = listOf(
                    ToolParam(name = "cmd", type = "string", required = true, description = "Command like 'docker logs vllm --tail 200' or 'docker restart vllm'.")
                ),
                paramsSpec = "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val cmd = args.get("cmd")?.asText() ?: throw IllegalArgumentException("cmd required")
                tools.ssh_exec_whitelisted(cmd)
            }
        )
    }

    data class Cfg(
        val host: String,
        val user: String,
        val keyPath: String,
        val timeoutMs: Long,
    ) {
        companion object {
            fun fromEnv() = Cfg(
                host = System.getenv("TOOLSERVER_SSH_HOST") ?: error("TOOLSERVER_SSH_HOST required"),
                user = System.getenv("TOOLSERVER_SSH_USER") ?: "stackops",
                keyPath = System.getenv("TOOLSERVER_SSH_KEY_PATH") ?: "/app/keys/stackops_ed25519",
                timeoutMs = (System.getenv("TOOLSERVER_SSH_TIMEOUT_MS")?.toLongOrNull() ?: 15000L)
            )
        }
    }

    class Tools(private val cfg: Cfg) {
        private fun runSshCommand(cmd: String): Triple<Int, ByteArray, ByteArray> {
            // Use system ssh client to avoid external dependencies.
            val fullCmd = listOf(
                "ssh",
                "-o", "StrictHostKeyChecking=no",
                "-i", cfg.keyPath,
                "${cfg.user}@${cfg.host}",
                cmd
            )
            val pb = ProcessBuilder(fullCmd)
            val p = pb.start()
            val completed = p.waitFor(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                runCatching { p.destroyForcibly() }
                throw IllegalStateException("timeout")
            }
            val out = p.inputStream.readAllBytes()
            val err = p.errorStream.readAllBytes()
            val code = p.exitValue()
            return Triple(code, out, err)
        }

        @LlmTool(
            name = "ssh_exec_whitelisted",
            shortDescription = "Run an allowed host op via SSH forced-command wrapper",
            longDescription = "Executes a command string over SSH. Server-side forced-command wrapper enforces a strict allowlist (e.g., docker logs/restart).",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
            params = [
                LlmToolParamDoc(name = "cmd", description = "Command like 'docker logs vllm --tail 200' or 'docker restart vllm'.")
            ]
        )
        fun ssh_exec_whitelisted(cmd: String): Map<String, Any?> {
            require(cmd.isNotBlank()) { "cmd required" }
            val (code, outBytes, errBytes) = runSshCommand(cmd)
            return mapOf(
                "exitCode" to code,
                "stdout" to outBytes.toString(Charsets.UTF_8),
                "stderr" to errBytes.toString(Charsets.UTF_8)
            )
        }
    }
}
