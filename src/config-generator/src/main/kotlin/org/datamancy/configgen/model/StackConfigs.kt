package org.datamancy.configgen.model

object StackConfigs {
    fun lab(volumesRoot: String? = null): StackConfig {
        val root = volumesRoot ?: (System.getProperty("user.dir") + "/volumes")
        return StackConfig(
            envName = "lab",
            domain = "lab.example.com",
            volumesRoot = root,
            security = SecurityConfig(
                useSelfSignedCerts = true,
                enableLetsEncrypt = false,
                letsEncryptEmail = null,
                hstsEnabled = true,
                apiAllowlistCidrs = listOf("127.0.0.1/32"),
                capabilityPolicyFailClosed = true
            ),
            services = ServicesConfig(
                authelia = AutheliaConfig(
                    oidcClients = listOf(
                        OidcClientConfig(
                            clientId = "open-webui",
                            clientName = "Open WebUI",
                            redirectUris = listOf("https://open-webui.lab.example.com/auth/callback"),
                            secretEnvKey = "OPEN_WEBUI_OIDC_SECRET",
                            scopes = listOf("openid", "profile", "email")
                        )
                    )
                ),
                ldap = LdapConfig(
                    bootstrapUsers = listOf(
                        LdapBootstrapUser(
                            uid = "gerald",
                            cn = "Gerald User",
                            sn = "User",
                            mail = "gerald@lab.example.com",
                            isAdmin = true,
                            groups = listOf("admins")
                        )
                    )
                ),
                grafana = GrafanaConfig(),
                databases = DatabasesConfig(
                    postgres = PostgresConfig(
                        dbs = listOf(
                            PostgresDbEntry("authelia", "authelia", "AUTHELIA_DB_PASSWORD"),
                            PostgresDbEntry("mailu", "mailu", "MAILU_DB_PASSWORD")
                        )
                    )
                )
            )
        )
    }
}
