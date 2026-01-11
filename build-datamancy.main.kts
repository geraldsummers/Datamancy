#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

// Datamancy Build System
// Simple and focused: Build JARs → Build Docker images → Process configs → Generate .env

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

// ============================================================================
// Configuration
// ============================================================================

data class StorageConfig(
    val vector_dbs: String,
    val non_vector_dbs: String,
    val application_data: String
)

data class RuntimeConfig(
    val domain: String,
    val admin_email: String,
    val admin_user: String
)

data class InstallationConfig(
    val default_path: String
)

data class ImagesConfig(
    val caddy: String? = null,
    val ldap: String? = null,
    val valkey: String? = null,
    val authelia: String? = null,
    val mailserver: String? = null,
    val ldap_account_manager: String? = null,
    val memcached: String? = null,
    val docker_proxy: String? = null,
    val kopia: String? = null,
    val postgres: String? = null,
    val mariadb: String? = null,
    val clickhouse: String? = null,
    val qdrant: String? = null,
    val grafana: String? = null,
    val open_webui: String? = null,
    val vaultwarden: String? = null,
    val bookstack: String? = null,
    val planka: String? = null,
    val forgejo: String? = null,
    val homepage: String? = null,
    val jupyterhub: String? = null,
    val homeassistant: String? = null,
    val qbittorrent: String? = null,
    val synapse: String? = null,
    val element: String? = null,
    val mastodon: String? = null,
    val mastodon_streaming: String? = null,
    val roundcube: String? = null,
    val radicale: String? = null,
    val seafile: String? = null,
    val onlyoffice: String? = null,
    val vllm: String? = null,
    val embedding_service: String? = null,
    val litellm: String? = null,
    val benthos: String? = null,
    val kotlin_runtime: String? = null,
    val temurin_jdk: String? = null
)

data class ResourceLimit(
    val memory: String? = null,
    val cpus: String? = null
)

data class PhaseConfig(
    val order: Int,
    val description: String,
    val timeout_seconds: Int,
    val compose_dirs: List<String>
)

data class DatamancyConfig(
    val installation: InstallationConfig,
    val storage: StorageConfig,
    val runtime: RuntimeConfig,
    val images: ImagesConfig? = null,
    val resources: Map<String, ResourceLimit>? = null,
    val phases: Map<String, PhaseConfig>? = null
)

// Variables that stay as ${VAR} for docker compose runtime substitution
val RUNTIME_VARS = setOf(
    "LDAP_ADMIN_PASSWORD", "STACK_ADMIN_PASSWORD", "STACK_USER_PASSWORD",
    "LITELLM_MASTER_KEY", "AUTHELIA_JWT_SECRET", "AUTHELIA_SESSION_SECRET",
    "AUTHELIA_STORAGE_ENCRYPTION_KEY", "POSTGRES_ROOT_PASSWORD",
    "MARIADB_ROOT_PASSWORD", "QDRANT_API_KEY", "AGENT_LDAP_OBSERVER_PASSWORD",
    "SYNAPSE_DB_PASSWORD", "SYNAPSE_REGISTRATION_SECRET", "SYNAPSE_MACAROON_SECRET",
    "SYNAPSE_FORM_SECRET", "SOGO_DB_PASSWORD", "ROUNDCUBE_DB_PASSWORD",
    "MATRIX_OAUTH_SECRET", "MASTODON_DB_PASSWORD", "MASTODON_OTP_SECRET",
    "MASTODON_SECRET_KEY_BASE", "MASTODON_VAPID_PRIVATE_KEY",
    "MASTODON_VAPID_PUBLIC_KEY", "MASTODON_OIDC_SECRET",
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_PRIMARY_KEY",
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_DETERMINISTIC_KEY",
    "MASTODON_ACTIVE_RECORD_ENCRYPTION_KEY_DERIVATION_SALT",
    "PLANKA_DB_PASSWORD", "PLANKA_SECRET_KEY", "BOOKSTACK_DB_PASSWORD",
    "BOOKSTACK_APP_KEY", "BOOKSTACK_OAUTH_SECRET", "FORGEJO_OAUTH_SECRET",
    "GRAFANA_OAUTH_SECRET", "OPENWEBUI_OAUTH_SECRET", "VAULTWARDEN_ADMIN_TOKEN",
    "VAULTWARDEN_OAUTH_SECRET", "HOMEASSISTANT_OAUTH_SECRET",
    "JUPYTERHUB_OAUTH_SECRET", "JUPYTERHUB_CRYPT_KEY",
    "MARIADB_SEAFILE_PASSWORD", "SEAFILE_JWT_KEY", "ONLYOFFICE_JWT_SECRET",
    "VOLUMES_ROOT", "DEPLOYMENT_ROOT", "API_LITELLM_ALLOWLIST",
    "DOMAIN", "MAIL_DOMAIN", "STACK_ADMIN_EMAIL", "STACK_ADMIN_USER",
    "AUTHELIA_OIDC_HMAC_SECRET", "AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY",
    "GRAFANA_DB_PASSWORD", "OPENWEBUI_DB_PASSWORD", "OPENWEBUI_DB_PASSWORD_ENCODED",
    "VAULTWARDEN_SMTP_PASSWORD", "PLANKA_OAUTH_SECRET", "AGENT_POSTGRES_OBSERVER_PASSWORD",
    "AGENT_CLICKHOUSE_OBSERVER_PASSWORD", "AGENT_MARIADB_OBSERVER_PASSWORD",
    "AGENT_QDRANT_API_KEY", "DATAMANCY_SERVICE_PASSWORD", "HUGGINGFACEHUB_API_TOKEN",
    "AUTHELIA_DB_PASSWORD", "HOMEASSISTANT_DB_PASSWORD", "CLICKHOUSE_ADMIN_PASSWORD",
    "NEXTCLOUD_OAUTH_SECRET", "PGADMIN_OAUTH_SECRET", "SOGO_OAUTH_SECRET",
    "DIM_OAUTH_SECRET", "DOCKER_USER_ID", "DOCKER_GROUP_ID", "DOCKER_SOCKET"
)

// ============================================================================
// Utilities
// ============================================================================

val RESET = "\u001B[0m"
val RED = "\u001B[31m"
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val CYAN = "\u001B[36m"

fun info(msg: String) = println("${GREEN}[INFO]${RESET} $msg")
fun warn(msg: String) = println("${YELLOW}[WARN]${RESET} $msg")
fun error(msg: String) = println("${RED}[ERROR]${RESET} $msg")
fun step(msg: String) = println("\n${CYAN}▸${RESET} $msg")

fun exec(command: String, ignoreError: Boolean = false): Int {
    info("Running: $command")
    val process = ProcessBuilder(*command.split(" ").toTypedArray())
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreError) {
        error("Command failed: $command")
        exitProcess(exitCode)
    }
    return exitCode
}

fun getGitVersion(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            .takeIf { process.waitFor() == 0 && it.isNotBlank() } ?: "unknown"
    } catch (e: Exception) { "unknown" }
}

// ============================================================================
// Build Steps
// ============================================================================

fun buildGradleServices(skipGradle: Boolean) {
    if (skipGradle) {
        warn("Skipping Gradle build")
        return
    }
    if (!File("gradlew").exists()) {
        warn("gradlew not found, skipping")
        return
    }
    step("Building JARs with Gradle")
    val exitCode = exec("./gradlew build -x test", ignoreError = true)
    if (exitCode != 0) {
        warn("Gradle build failed - using existing JARs")
    }
}

fun buildDockerImages() {
    step("Building Docker images")
    val datamancyServices = listOf(
        "control-panel", "data-fetcher", "unified-indexer",
        "search-service", "agent-tool-server"
        // "embedding-service" - skipped (large Python/PyTorch deps take 5+ min to download)
    )

    datamancyServices.forEach { service ->
        val dockerfile = File("src/$service/Dockerfile")
        if (!dockerfile.exists()) {
            warn("Skipping $service - no Dockerfile")
            return@forEach
        }

        info("Building datamancy/$service")
        val exitCode = exec(
            "docker build -t datamancy/$service:latest -f src/$service/Dockerfile .",
            ignoreError = true
        )
        if (exitCode != 0) {
            error("Failed to build datamancy/$service")
            exitProcess(exitCode)
        }
    }
}

fun copyComposeFiles(outputDir: File) {
    step("Copying compose files")
    val templatesDir = File("compose.templates")
    if (!templatesDir.exists()) {
        error("compose.templates/ not found")
        exitProcess(1)
    }

    templatesDir.copyRecursively(outputDir, overwrite = true)
    info("Copied compose files to ${outputDir.path}")
}

fun processConfigs(outputDir: File, domain: String, adminEmail: String, adminUser: String) {
    step("Processing config templates")
    val templatesDir = File("configs.templates")
    if (!templatesDir.exists()) {
        warn("configs.templates/ not found, skipping")
        return
    }

    val configsDir = outputDir.resolve("configs")
    var count = 0

    templatesDir.walkTopDown().forEach { source ->
        if (!source.isFile) return@forEach

        val relativePath = source.relativeTo(templatesDir).path
        val target = configsDir.resolve(relativePath.removeSuffix(".template"))
        target.parentFile.mkdirs()

        var content = source.readText()

        // Hardcode domain/admin at build time
        content = content
            .replace("{{DOMAIN}}", domain)
            .replace("{{MAIL_DOMAIN}}", domain)
            .replace("{{STACK_ADMIN_EMAIL}}", adminEmail)
            .replace("{{STACK_ADMIN_USER}}", adminUser)
            .replace("{{GENERATION_TIMESTAMP}}", Instant.now().toString())

        // Special handling for LDAP bootstrap
        if (relativePath.contains("ldap/bootstrap_ldap.ldif")) {
            val adminPwd = System.getenv("STACK_ADMIN_PASSWORD") ?: "changeme"
            val userPwd = System.getenv("STACK_USER_PASSWORD") ?: adminPwd
            content = content
                .replace("{{ADMIN_SSHA_PASSWORD}}", generatePasswordHash(adminPwd))
                .replace("{{USER_SSHA_PASSWORD}}", generatePasswordHash(userPwd))
        } else {
            // Convert {{VAR}} to ${VAR} for runtime vars
            content = content.replace(Regex("""\{\{([A-Z_][A-Z0-9_]*)\}\}""")) { match ->
                val varName = match.groupValues[1]
                if (varName in RUNTIME_VARS) "\${$varName}"
                else {
                    warn("Unknown var: {{$varName}} in $relativePath")
                    match.value
                }
            }
        }

        target.writeText(content)
        if (source.canExecute()) target.setExecutable(true)
        count++
    }

    info("Processed $count config files")
}

fun generatePasswordHash(password: String): String {
    val process = ProcessBuilder(
        "docker", "run", "--rm", "osixia/openldap:1.5.0",
        "slappasswd", "-s", password
    )
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    return process.inputStream.bufferedReader().readText().trim()
}

fun generateSecret(): String {
    val process = ProcessBuilder("openssl", "rand", "-hex", "32")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    return process.inputStream.bufferedReader().readText().trim()
}

fun generateRSAKey(): String {
    val pem = ProcessBuilder("openssl", "genrsa", "4096")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
        .inputStream.bufferedReader().readText().trim()
    return java.util.Base64.getEncoder().encodeToString(pem.toByteArray())
}

fun generateEnvFile(file: File, domain: String, adminEmail: String, adminUser: String) {
    step("Generating .env with secrets")

    file.writeText("""
# Datamancy Configuration
# Generated by build-datamancy.main.kts at ${Instant.now()}

# ============================================================================
# Paths
# ============================================================================
VOLUMES_ROOT=/mnt/btrfs_raid_1_01_docker
DEPLOYMENT_ROOT=/mnt/btrfs_raid_1_01_docker

# ============================================================================
# Domain and Admin
# ============================================================================
DOMAIN=$domain
MAIL_DOMAIN=$domain
STACK_ADMIN_EMAIL=$adminEmail
STACK_ADMIN_USER=$adminUser
DOCKER_USER_ID=1000
DOCKER_GROUP_ID=1000
DOCKER_SOCKET=/var/run/docker.sock

# ============================================================================
# Secrets (Auto-generated)
# ============================================================================

# LDAP
LDAP_ADMIN_PASSWORD=${generateSecret()}
STACK_ADMIN_PASSWORD=${generateSecret()}
STACK_USER_PASSWORD=${generateSecret()}
AGENT_LDAP_OBSERVER_PASSWORD=${generateSecret()}

# Authentication
AUTHELIA_JWT_SECRET=${generateSecret()}
AUTHELIA_SESSION_SECRET=${generateSecret()}
AUTHELIA_STORAGE_ENCRYPTION_KEY=${generateSecret()}
AUTHELIA_OIDC_HMAC_SECRET=${generateSecret()}
AUTHELIA_IDENTITY_PROVIDERS_OIDC_ISSUER_PRIVATE_KEY=${generateRSAKey()}

# Databases
POSTGRES_ROOT_PASSWORD=${generateSecret()}
MARIADB_ROOT_PASSWORD=${generateSecret()}
CLICKHOUSE_ADMIN_PASSWORD=${generateSecret()}
AUTHELIA_DB_PASSWORD=${generateSecret()}
SYNAPSE_DB_PASSWORD=${generateSecret()}
MASTODON_DB_PASSWORD=${generateSecret()}
PLANKA_DB_PASSWORD=${generateSecret()}
OPENWEBUI_DB_PASSWORD=${generateSecret()}
VAULTWARDEN_DB_PASSWORD=${generateSecret()}
FORGEJO_DB_PASSWORD=${generateSecret()}
GRAFANA_DB_PASSWORD=${generateSecret()}
HOMEASSISTANT_DB_PASSWORD=${generateSecret()}
SOGO_DB_PASSWORD=${generateSecret()}
ROUNDCUBE_DB_PASSWORD=${generateSecret()}
BOOKSTACK_DB_PASSWORD=${generateSecret()}
MARIADB_SEAFILE_PASSWORD=${generateSecret()}

# AI Services
LITELLM_MASTER_KEY=${generateSecret()}
QDRANT_API_KEY=${generateSecret()}

# Application Secrets
SYNAPSE_REGISTRATION_SECRET=${generateSecret()}
SYNAPSE_MACAROON_SECRET=${generateSecret()}
SYNAPSE_FORM_SECRET=${generateSecret()}
MATRIX_OAUTH_SECRET=${generateSecret()}
MASTODON_OTP_SECRET=${generateSecret()}
MASTODON_SECRET_KEY_BASE=${generateSecret()}
MASTODON_VAPID_PRIVATE_KEY=${generateSecret()}
MASTODON_VAPID_PUBLIC_KEY=${generateSecret()}
MASTODON_OIDC_SECRET=${generateSecret()}
MASTODON_ACTIVE_RECORD_ENCRYPTION_PRIMARY_KEY=${generateSecret()}
MASTODON_ACTIVE_RECORD_ENCRYPTION_DETERMINISTIC_KEY=${generateSecret()}
MASTODON_ACTIVE_RECORD_ENCRYPTION_KEY_DERIVATION_SALT=${generateSecret()}
BOOKSTACK_APP_KEY=${generateSecret()}
BOOKSTACK_OAUTH_SECRET=${generateSecret()}
PLANKA_SECRET_KEY=${generateSecret()}
PLANKA_OAUTH_SECRET=${generateSecret()}
FORGEJO_OAUTH_SECRET=${generateSecret()}
GRAFANA_OAUTH_SECRET=${generateSecret()}
OPENWEBUI_OAUTH_SECRET=${generateSecret()}
OPENWEBUI_DB_PASSWORD_ENCODED=${generateSecret()}
VAULTWARDEN_ADMIN_TOKEN=${generateSecret()}
VAULTWARDEN_OAUTH_SECRET=${generateSecret()}
VAULTWARDEN_SMTP_PASSWORD=${generateSecret()}
HOMEASSISTANT_OAUTH_SECRET=${generateSecret()}
JUPYTERHUB_OAUTH_SECRET=${generateSecret()}
JUPYTERHUB_CRYPT_KEY=${generateSecret()}
SEAFILE_JWT_KEY=${generateSecret()}
ONLYOFFICE_JWT_SECRET=${generateSecret()}
NEXTCLOUD_OAUTH_SECRET=${generateSecret()}
PGADMIN_OAUTH_SECRET=${generateSecret()}
SOGO_OAUTH_SECRET=${generateSecret()}
DIM_OAUTH_SECRET=${generateSecret()}

# Agent Observer Accounts
AGENT_POSTGRES_OBSERVER_PASSWORD=${generateSecret()}
AGENT_CLICKHOUSE_OBSERVER_PASSWORD=${generateSecret()}
AGENT_MARIADB_OBSERVER_PASSWORD=${generateSecret()}
AGENT_QDRANT_API_KEY=${generateSecret()}

# Datamancy Services
DATAMANCY_SERVICE_PASSWORD=${generateSecret()}

# External APIs (set manually)
HUGGINGFACEHUB_API_TOKEN=

# API Configuration
API_LITELLM_ALLOWLIST=127.0.0.1 172.16.0.0/12 192.168.0.0/16

""".trimIndent())

    info("Generated .env")
}

// ============================================================================
// Main
// ============================================================================

fun main(args: Array<String>) {
    val skipGradle = args.contains("--skip-gradle")
    val distDir = File("dist")

    println("""
${CYAN}╔════════════════════════════════════════╗
║  Datamancy Build System                ║
╚════════════════════════════════════════╝${RESET}
""")

    // Load config
    step("Loading datamancy.config.yaml")
    val configFile = File("datamancy.config.yaml")
    if (!configFile.exists()) {
        error("datamancy.config.yaml not found")
        exitProcess(1)
    }

    val mapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
    val config = mapper.readValue<DatamancyConfig>(configFile)

    info("Domain: ${config.runtime.domain}")
    info("Admin: ${config.runtime.admin_user} <${config.runtime.admin_email}>")

    // Clean dist/
    if (distDir.exists()) {
        step("Cleaning dist/")
        distDir.deleteRecursively()
    }
    distDir.mkdirs()

    // Build steps
    buildGradleServices(skipGradle)
    buildDockerImages()
    copyComposeFiles(distDir)
    processConfigs(distDir, config.runtime.domain, config.runtime.admin_email, config.runtime.admin_user)
    generateEnvFile(distDir.resolve(".env"), config.runtime.domain, config.runtime.admin_email, config.runtime.admin_user)

    // Build info
    val version = getGitVersion()
    distDir.resolve(".build-info").writeText("""
version: $version
built_at: ${Instant.now()}
built_by: ${System.getProperty("user.name")}
""".trimIndent())

    println("""
${GREEN}✓ Build complete!${RESET}

${CYAN}Output:${RESET} ${distDir.absolutePath}
${CYAN}Version:${RESET} $version

${GREEN}Deploy:${RESET}
  cd dist
  vim .env
  docker compose up -d

""")
}

main(args)
