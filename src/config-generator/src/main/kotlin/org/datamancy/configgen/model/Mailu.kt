package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class MailuConfig(
    val enabled: Boolean = true,
    val domain: String,
    val secretKeyEnvKey: String = "MAILU_SECRET_KEY",
    val initialAdminEnvKey: String = "MAILU_ADMIN_PW",
    val db: DbBindingConfig = DbBindingConfig()
)

@Serializable
data class DbBindingConfig(
    val engine: DbEngine = DbEngine.Postgres,
    val host: String = "postgres",
    val database: String = "mailu",
    val username: String = "mailu",
    val passwordEnvKey: String = "MAILU_DB_PASSWORD"
)

@Serializable
enum class DbEngine { Postgres, MariaDb }
