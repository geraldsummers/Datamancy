package org.datamancy.configgen.templates

import org.datamancy.configgen.model.AutheliaConfig
import org.datamancy.configgen.model.StackConfig

object AutheliaTemplate {
    fun render(stack: StackConfig): String {
        val cfg: AutheliaConfig = stack.services.authelia
        val cookieDomain = cfg.cookieDomain ?: ".${stack.domain}"
        val issuer = cfg.jwtIssuer ?: "https://auth.${stack.domain}"
        val sessionDomain = cfg.sessionDomain ?: stack.domain

        val sb = StringBuilder()
        sb.append("server:\n")
            .append("  address: :9091\n")
        sb.append("session:\n")
            .append("  domain: ${sessionDomain}\n")
        sb.append("identity_providers:\n")
            .append("  oidc:\n")
            .append("    issuer_private_key: \"/secrets/oidc-signing.key\"\n")
            .append("    access_token_lifespan: 1h\n")
            .append("    authorize_code_lifespan: 5m\n")
            .append("    id_token_lifespan: 1h\n")
            .append("    refresh_token_lifespan: 90m\n")
            .append("    minimum_parameter_entropy: 8\n")
            .append("    enforce_pkce: public_clients_only\n")
            .append("    issuer: \"${issuer}\"\n")
            .append("    clients:\n")
        for (c in cfg.oidcClients) {
            sb.append("      - id: \"${c.clientId}\"\n")
                .append("        description: \"${c.clientName}\"\n")
                .append("        secret: \"${'$'}{${c.secretEnvKey}}\"\n")
                .append("        redirect_uris:\n")
            c.redirectUris.forEach { uri -> sb.append("          - \"$uri\"\n") }
            sb.append("        scopes:\n")
            c.scopes.forEach { scope -> sb.append("          - $scope\n") }
        }
        sb.append("storage:\n")
            .append("  encryption_key: \"${'$'}{STACK_ADMIN_PASSWORD}\"\n")
            .append("  postgres:\n")
            .append("    host: ${cfg.storage.host}\n")
            .append("    port: ${cfg.storage.port}\n")
            .append("    database: ${cfg.storage.database}\n")
            .append("    username: ${cfg.storage.username}\n")
            .append("    password: \"${'$'}{${cfg.storage.passwordSecretKey}}\"\n")
        sb.append("cookies:\n")
            .append("  - name: authelia_session\n")
            .append("    domain: ${cookieDomain}\n")
            .append("    same_site: lax\n")

        return sb.toString()
    }
}
