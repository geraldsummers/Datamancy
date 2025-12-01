package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class AutheliaConfig(
    val storage: AutheliaStorageConfig = AutheliaStorageConfig(),
    val sessionDomain: String? = null,
    val oidcClients: List<OidcClientConfig> = emptyList(),
    val jwtIssuer: String? = null,
    val cookieDomain: String? = null
)

@Serializable
data class AutheliaStorageConfig(
    val host: String = "postgres",
    val port: Int = 5432,
    val database: String = "authelia",
    val username: String = "authelia",
    val passwordSecretKey: String = "AUTHELIA_DB_PASSWORD"
)

@Serializable
data class OidcClientConfig(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val secretEnvKey: String,
    val scopes: List<String> = listOf("openid", "profile", "email"),
    val tokenLifespanSeconds: Int = 3600
)
