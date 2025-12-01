package org.datamancy.configgen.validators

import org.datamancy.configgen.model.OidcClientConfig
import org.datamancy.configgen.model.StackConfig
import org.datamancy.configgen.secrets.SecretsProvider

data class ValidationResult(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

object Validators {
    fun validateAll(stack: StackConfig, secrets: SecretsProvider): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Domain basic sanity
        if (stack.domain.isBlank() || !stack.domain.contains('.')) {
            errors += "Domain must be non-empty and contain at least one dot."
        }

        // Secrets presence checks (scan known *EnvKey fields)
        // Authelia storage password
        runCatching { secrets.getRequired(stack.services.authelia.storage.passwordSecretKey) }
            .onFailure { errors += "Missing required secret: ${stack.services.authelia.storage.passwordSecretKey}" }

        // Postgres superuser
        runCatching { secrets.getRequired(stack.services.databases.postgres.initAdminUserEnvKey) }
            .onFailure { errors += "Missing required secret: ${stack.services.databases.postgres.initAdminUserEnvKey}" }

        // Postgres dbs
        stack.services.databases.postgres.dbs.forEach { db ->
            runCatching { secrets.getRequired(db.passwordEnvKey) }
                .onFailure { errors += "Missing required secret: ${db.passwordEnvKey} for DB ${db.name}" }
        }

        // LDAP
        runCatching { secrets.getRequired(stack.services.ldap.adminPasswordEnvKey) }
            .onFailure { errors += "Missing required secret: ${stack.services.ldap.adminPasswordEnvKey}" }
        stack.services.ldap.bootstrapUsers.forEach { u ->
            u.passwordEnvKey?.let { key ->
                runCatching { secrets.getRequired(key) }
                    .onFailure { errors += "Missing required secret: ${key} for LDAP user ${u.uid}" }
            }
        }

        // OIDC redirect URIs must match domain
        fun checkClient(c: OidcClientConfig) {
            c.redirectUris.forEach { uri ->
                if (!uri.contains(stack.domain)) {
                    errors += "OIDC redirect URI $uri must contain domain ${stack.domain}"
                }
            }
        }
        stack.services.authelia.oidcClients.forEach(::checkClient)

        // Security warnings
        if (stack.security.apiAllowlistCidrs.any { it.trim() == "0.0.0.0/0" }) {
            warnings += "API allowlist contains 0.0.0.0/0 (wide open)."
        }

        // Placeholder strings
        fun hasBadPlaceholder(s: String): Boolean =
            s.contains("PLACEHOLDER", ignoreCase = true) ||
            s.contains("changeme", ignoreCase = true) ||
            s.contains("DatamancyTest2025!", ignoreCase = true)

        if (hasBadPlaceholder(stack.domain)) warnings += "Domain contains placeholder-like value."

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }
}
