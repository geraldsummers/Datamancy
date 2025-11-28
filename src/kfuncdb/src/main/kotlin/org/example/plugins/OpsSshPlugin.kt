package org.example.plugins

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.api.Plugin
import org.example.api.PluginContext
import java.io.ByteArrayOutputStream

/**
 * SSH operations plugin. Intentionally minimal: delegates all safety/allowlisting
 * to the host's forced-command wrapper configured for the target user.
 */
class OpsSshPlugin : Plugin {
    private lateinit var cfg: Cfg

    override fun init(context: PluginContext) {
        cfg = Cfg.fromEnv()
    }

    override fun tools(): List<Any> = listOf(Tools(cfg))

    data class Cfg(
        val host: String,
        val user: String,
        val keyPath: String,
        val timeoutMs: Long,
    ) {
        companion object {
            fun fromEnv() = Cfg(
                host = System.getenv("KFUNCDB_SSH_HOST") ?: error("KFUNCDB_SSH_HOST required"),
                user = System.getenv("KFUNCDB_SSH_USER") ?: "stackops",
                keyPath = System.getenv("KFUNCDB_SSH_KEY_PATH") ?: "/app/keys/stackops_ed25519",
                timeoutMs = (System.getenv("KFUNCDB_SSH_TIMEOUT_MS")?.toLongOrNull() ?: 15000L)
            )
        }
    }

    class Tools(private val cfg: Cfg) {
        private fun <T> ssh(block: (SSHClient) -> T): T {
            val ssh = SSHClient()
            // In an internal network; consider pinning real host keys in production
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(cfg.host)
            val kp: KeyProvider = ssh.loadKeys(cfg.keyPath)
            ssh.authPublickey(cfg.user, kp)
            return try { block(ssh) } finally { runCatching { ssh.disconnect() } }
        }

        @LlmTool(
            name = "ssh_exec_whitelisted",
            shortDescription = "Run an allowed host op via SSH forced-command wrapper",
            longDescription = "Executes a command string over SSH. Server-side forced-command wrapper enforces a strict allowlist (e.g., docker logs/restart).",
            paramsSpec = "{" +
                "\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
            params = [
                LlmToolParamDoc(name = "cmd", description = "Command like 'docker logs localai --tail 200' or 'docker restart localai'.")
            ]
        )
        fun ssh_exec_whitelisted(cmd: String): Map<String, Any?> {
            require(cmd.isNotBlank()) { "cmd required" }
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val exit = ssh { client ->
                client.startSession().use { session ->
                    val exec = session.exec(cmd)
                    // Busy-wait with simple timeout
                    val deadline = System.currentTimeMillis() + cfg.timeoutMs
                    while (!exec.isEOF) {
                        if (System.currentTimeMillis() > deadline) {
                            runCatching { exec.close() }
                            throw IllegalStateException("timeout")
                        }
                        Thread.sleep(25)
                    }
                    exec.inputStream.copyTo(out)
                    exec.errorStream.copyTo(err)
                    exec.exitStatus ?: 255
                }
            }
            return mapOf(
                "exitCode" to exit,
                "stdout" to out.toString(Charsets.UTF_8),
                "stderr" to err.toString(Charsets.UTF_8)
            )
        }
    }
}
