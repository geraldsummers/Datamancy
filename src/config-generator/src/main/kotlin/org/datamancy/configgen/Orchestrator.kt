package org.datamancy.configgen

import kotlinx.serialization.Serializable
import org.datamancy.configgen.model.StackConfig
import org.datamancy.configgen.secrets.SecretsProvider
import org.datamancy.configgen.templates.AutheliaTemplate
import org.datamancy.configgen.templates.CaddyTemplate
import org.datamancy.configgen.templates.DockerComposeTemplate
import org.datamancy.configgen.templates.LdapBootstrapTemplate
import org.datamancy.configgen.validators.ValidationResult
import org.datamancy.configgen.validators.Validators
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
data class ConfigGenerationReport(
    val env: String,
    val mode: String,
    val generatedFiles: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

class ConfigGenerator(
    private val secrets: SecretsProvider
) {
    enum class Mode { generate, dry_run, validate, diff }

    data class Targets(val outDir: File) {
        val compose = File(outDir, "docker-compose.generated.yml")
        val configsGenerated = File(outDir, "configs.generated")
        val caddyfile = File(configsGenerated, "infrastructure/caddy/Caddyfile")
        val autheliaCfg = File(configsGenerated, "applications/authelia/configuration.yml")
        val ldapLdif = File(outDir, "bootstrap_ldap.generated.ldif")
    }

    fun run(stack: StackConfig, mode: Mode, outDir: File): ConfigGenerationReport {
        val targets = Targets(outDir)

        val validation: ValidationResult = Validators.validateAll(stack, secrets)
        if (!validation.ok && mode != Mode.validate) {
            return ConfigGenerationReport(stack.envName, mode.name, errors = validation.errors, warnings = validation.warnings)
        }

        if (mode == Mode.validate) {
            return ConfigGenerationReport(stack.envName, mode.name, warnings = validation.warnings, errors = validation.errors)
        }

        // compute contents
        val compose = DockerComposeTemplate.render(stack)
        val caddy = CaddyTemplate.render(stack)
        val authelia = AutheliaTemplate.render(stack)
        val ldap = LdapBootstrapTemplate.render(stack, secrets)

        val files: List<Pair<File, String>> = listOf(
            targets.compose to compose,
            targets.caddyfile to caddy,
            targets.autheliaCfg to authelia,
            targets.ldapLdif to ldap
        )

        return when (mode) {
            Mode.dry_run -> {
                ConfigGenerationReport(
                    env = stack.envName,
                    mode = mode.name,
                    generatedFiles = files.map { it.first.relativeToOrSelf(outDir).path },
                    warnings = validation.warnings
                )
            }
            Mode.diff -> {
                val diffs = mutableListOf<String>()
                for ((file, content) in files) {
                    val status = when {
                        !file.exists() -> "NEW ${file.relativeToOrSelf(outDir).path}"
                        hash(file.readText()) != hash(content) -> "CHANGED ${file.relativeToOrSelf(outDir).path}"
                        else -> "SAME ${file.relativeToOrSelf(outDir).path}"
                    }
                    diffs += status
                }
                ConfigGenerationReport(
                    env = stack.envName,
                    mode = mode.name,
                    generatedFiles = diffs,
                    warnings = validation.warnings
                )
            }
            Mode.generate -> {
                // write files
                for ((file, content) in files) {
                    ensureParent(file.toPath())
                    file.writeText(content)
                }
                ConfigGenerationReport(
                    env = stack.envName,
                    mode = mode.name,
                    generatedFiles = files.map { it.first.relativeToOrSelf(outDir).path },
                    warnings = validation.warnings
                )
            }
            Mode.validate -> ConfigGenerationReport(stack.envName, mode.name, warnings = validation.warnings, errors = validation.errors)
        }
    }

    private fun ensureParent(path: Path) {
        val parent = path.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
