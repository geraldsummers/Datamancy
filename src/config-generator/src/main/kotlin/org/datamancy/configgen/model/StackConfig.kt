package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class StackConfig(
    val envName: String,
    val domain: String,
    val baseSubdomain: String = "",
    val volumesRoot: String,
    val profiles: ProfilesConfig = ProfilesConfig(),
    val networks: NetworkConfig = NetworkConfig(),
    val security: SecurityConfig,
    val services: ServicesConfig
)

@Serializable
data class ProfilesConfig(
    val bootstrap: Boolean = true,
    val databases: Boolean = true,
    val applications: Boolean = true,
    val infrastructure: Boolean = true,
    val vectorDbs: Boolean = true
)

@Serializable
data class NetworkConfig(
    val frontendName: String = "datamancy_frontend",
    val backendName: String = "datamancy_backend",
    val databaseName: String = "datamancy_database"
)

@Serializable
data class SecurityConfig(
    val useSelfSignedCerts: Boolean = true,
    val enableLetsEncrypt: Boolean = false,
    val letsEncryptEmail: String? = null,
    val hstsEnabled: Boolean = true,
    val apiAllowlistCidrs: List<String> = emptyList(),
    val capabilityPolicyFailClosed: Boolean = true
)

@Serializable
data class ServicesConfig(
    val authelia: AutheliaConfig,
    val ldap: LdapConfig,
    val caddy: CaddyConfig = CaddyConfig(),
    val mailu: MailuConfig? = null,
    val grafana: GrafanaConfig? = null,
    val vllm: VllmConfig = VllmConfig(),
    val litellm: LiteLlmConfig = LiteLlmConfig(),
    val agentToolServer: AgentToolServerConfig = AgentToolServerConfig(),
    val probeOrchestrator: ProbeOrchestratorConfig = ProbeOrchestratorConfig(),
    val databases: DatabasesConfig,
    val applications: ApplicationsConfig = ApplicationsConfig()
)

@Serializable
data class CaddyConfig(val enabled: Boolean = true)

@Serializable
data class GrafanaConfig(val enabled: Boolean = true, val adminPasswordEnvKey: String = "GRAFANA_ADMIN_PASSWORD")

@Serializable
data class VllmConfig(val enabled: Boolean = true)

@Serializable
data class LiteLlmConfig(val enabled: Boolean = true, val masterKeyEnvKey: String = "LITELLM_MASTER_KEY")

@Serializable
data class AgentToolServerConfig(val enabled: Boolean = true)

@Serializable
data class ApplicationsConfig(val openWebUiEnabled: Boolean = true)
