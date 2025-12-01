package org.datamancy.configgen.templates

import org.datamancy.configgen.model.StackConfig

object CaddyTemplate {
    fun render(stack: StackConfig): String {
        val sb = StringBuilder()
        val domain = stack.domain
        // Global options
        sb.append("{\n")
        if (stack.security.enableLetsEncrypt) {
            val email = stack.security.letsEncryptEmail ?: "admin@${domain}"
            sb.append("  email $email\n")
        }
        sb.append("}\n\n")

        // Example sites (extend as needed)
        fun vhost(sub: String, upstream: String): String {
            val host = if (stack.baseSubdomain.isNotBlank()) "$sub.${stack.baseSubdomain}.$domain" else "$sub.$domain"
            return buildString {
                append("$host {\n")
                append("  encode gzip zstd\n")
                if (stack.security.hstsEnabled) {
                    append("  header {\n")
                    append("    Strict-Transport-Security \"max-age=31536000; includeSubDomains\"\n")
                    append("    X-Frame-Options DENY\n")
                    append("    X-Content-Type-Options nosniff\n")
                    append("    Referrer-Policy no-referrer\n")
                    append("  }\n")
                }
                append("  reverse_proxy $upstream\n")
                append("}\n\n")
            }
        }

        // Minimal set based on services that might exist
        if (stack.services.grafana != null) {
            sb.append(vhost("grafana", "grafana:3000"))
        }
        if (stack.services.applications.openWebUiEnabled) {
            sb.append(vhost("open-webui", "open-webui:8080"))
        }
        // Authelia as auth provider site
        sb.append(vhost("auth", "authelia:9091"))

        return sb.toString()
    }
}
