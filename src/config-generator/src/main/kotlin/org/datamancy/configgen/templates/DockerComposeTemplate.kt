package org.datamancy.configgen.templates

import org.datamancy.configgen.model.StackConfig

object DockerComposeTemplate {
    fun render(stack: StackConfig): String {
        val vol = stack.volumesRoot.trimEnd('/')
        val n = stack.networks
        val sb = StringBuilder()
        sb.append("version: '3.9'\n")
        sb.append("networks:\n")
            .append("  ${n.frontendName}: {}\n")
            .append("  ${n.backendName}: {}\n")
            .append("  ${n.databaseName}: {}\n")
        sb.append("services:\n")

        fun service(
            name: String,
            image: String,
            profiles: List<String>,
            networks: List<String>,
            ports: List<String> = emptyList(),
            env: Map<String, String> = emptyMap(),
            volumes: List<String> = emptyList(),
            healthcheck: String? = null,
            mem: String = "512m"
        ) {
            sb.append("  $name:\n")
            sb.append("    image: $image\n")
            if (profiles.isNotEmpty()) {
                sb.append("    profiles: [${profiles.joinToString(", ") { it }}]\n")
            }
            if (ports.isNotEmpty()) {
                sb.append("    ports:\n")
                ports.forEach { sb.append("      - \"$it\"\n") }
            }
            if (env.isNotEmpty()) {
                sb.append("    environment:\n")
                env.forEach { (k, v) -> sb.append("      $k: $v\n") }
            }
            if (volumes.isNotEmpty()) {
                sb.append("    volumes:\n")
                volumes.forEach { sb.append("      - $it\n") }
            }
            if (networks.isNotEmpty()) {
                sb.append("    networks:\n")
                networks.forEach { sb.append("      - $it\n") }
            }
            sb.append("    deploy:\n")
                .append("      resources:\n")
                .append("        limits:\n")
                .append("          memory: $mem\n")
            healthcheck?.let {
                sb.append("    healthcheck:\n")
                    .append("      test: [\"CMD-SHELL\", \"$it\"]\n")
                    .append("      interval: 30s\n      timeout: 5s\n      retries: 3\n")
            }
        }

        // Postgres (basic)
        service(
            name = "postgres",
            image = "postgres:16",
            profiles = listOf("databases").filter { stack.profiles.databases },
            networks = listOf(n.databaseName),
            ports = emptyList(),
            env = mapOf(
                "POSTGRES_PASSWORD" to "\${'$'}{${stack.services.databases.postgres.initAdminUserEnvKey}}"
            ),
            volumes = listOf("$vol/postgres:/var/lib/postgresql/data")
        )

        // Authelia
        service(
            name = "authelia",
            image = "authelia/authelia:4.38",
            profiles = listOf("applications").filter { stack.profiles.applications },
            networks = listOf(n.backendName, n.databaseName),
            env = mapOf(
                // expose env keys needed by config at runtime
                stack.services.authelia.storage.passwordSecretKey to "\${'$'}{${stack.services.authelia.storage.passwordSecretKey}}",
                "STACK_ADMIN_PASSWORD" to "\${'$'}{STACK_ADMIN_PASSWORD}"
            ),
            volumes = listOf(
                "${'$'}{PWD}/configs.generated/applications/authelia:/config:ro"
            ),
            healthcheck = "wget --spider -q http://localhost:9091/api/health || exit 1"
        )

        // Caddy
        service(
            name = "caddy",
            image = "caddy:2",
            profiles = listOf("infrastructure").filter { stack.profiles.infrastructure },
            networks = listOf(n.frontendName, n.backendName),
            ports = listOf("80:80", "443:443"),
            volumes = listOf(
                "${'$'}{PWD}/configs.generated/infrastructure/caddy/Caddyfile:/etc/caddy/Caddyfile:ro"
            ),
            healthcheck = "wget --spider -q http://localhost:2019 || exit 1"
        )

        // Grafana (optional)
        stack.services.grafana?.let {
            service(
                name = "grafana",
                image = "grafana/grafana:11.2.0",
                profiles = listOf("applications").filter { stack.profiles.applications },
                networks = listOf(n.backendName),
                env = mapOf(
                    "GF_SECURITY_ADMIN_PASSWORD" to "\${'$'}{${it.adminPasswordEnvKey}}"
                ),
                volumes = listOf("$vol/grafana:/var/lib/grafana"),
                healthcheck = "wget --spider -q http://localhost:3000/api/health || exit 1"
            )
        }

        return sb.toString()
    }
}
