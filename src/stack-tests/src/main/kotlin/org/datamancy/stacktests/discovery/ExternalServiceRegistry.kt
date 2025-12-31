package org.datamancy.stacktests.discovery

import org.datamancy.stacktests.models.*

/**
 * Registry of known external service healthcheck endpoints.
 *
 * This is a curated list based on docker-compose.yml analysis.
 * These services are external (non-Kotlin) services that expose HTTP healthcheck endpoints.
 */
object ExternalServiceRegistry {

    /**
     * Get all known external services with testable HTTP endpoints.
     */
    fun getExternalServices(): List<ServiceSpec> {
        return listOf(
            // Databases
            service("couchdb", "http://localhost:15984", "/_up"),
            service("clickhouse", "http://localhost:18123", "/ping"),

            // Authentication & Security
            service("authelia", "http://localhost:19091", "/api/health"),
            service("ldap-account-manager", "http://localhost:10080", "/lam/"),

            // AI/ML Services
            service("vllm", "http://localhost:18000", "/health"),
            service("embedding-service", "http://localhost:18080", "/health"),
            service("litellm", "http://localhost:14000", "/health"),

            // Monitoring & Observability
            service("grafana", "http://localhost:13000", "/api/health"),

            // Applications
            service("open-webui", "http://localhost:18081", "/health"),
            service("vaultwarden", "http://localhost:10080", "/alive"),
            service("planka", "http://localhost:11337", "/api/health"),
            service("bookstack", "http://localhost:10081", "/"),
            service("seafile", "http://localhost:18001", "/"),
            service("onlyoffice", "http://localhost:10082", "/healthcheck"),
            service("radicale", "http://localhost:15232", "/"),
            service("roundcube", "http://localhost:10083", "/"),
            service("forgejo", "http://localhost:13001", "/"),
            service("qbittorrent", "http://localhost:18082", "/"),

            // Matrix
            service("synapse", "http://localhost:18008", "/health"),
            service("element", "http://localhost:10084", "/"),

            // Mastodon
            service("mastodon-web", "http://localhost:13002", "/health"),
            service("mastodon-streaming", "http://localhost:14000", "/api/v1/streaming/health"),

            // Jupyter & Collaboration
            service("jupyterhub", "http://localhost:18002", "/hub/health"),
            service("homepage", "http://localhost:13003", "/"),
            service("homeassistant", "http://localhost:18124", "/"),

            // Infrastructure
            service("docker-proxy", "http://localhost:12375", "/version"),
            service("qdrant", "http://localhost:16333", "/"),

            // Vector database (Qdrant) has a healthcheck endpoint but uses TCP check in compose
            // We can still test the root endpoint
        )
    }

    private fun service(
        name: String,
        baseUrl: String,
        healthPath: String,
        requiredServices: List<String> = emptyList()
    ): ServiceSpec {
        return ServiceSpec(
            name = name,
            containerName = name,
            baseUrl = baseUrl,
            type = ServiceType.DOCKER_EXTERNAL,
            requiredServices = requiredServices,
            endpoints = listOf(
                EndpointSpec(
                    method = HttpMethod.GET,
                    path = healthPath,
                    serviceUrl = baseUrl,
                    sourceFile = "docker-compose.yml",
                    expectedResponseType = when {
                        healthPath.contains("/health") || healthPath.contains("/alive") -> ResponseType.JSON
                        healthPath.contains("/ping") || healthPath.contains("/_up") -> ResponseType.TEXT
                        healthPath == "/" -> ResponseType.HTML
                        else -> ResponseType.JSON
                    },
                    description = "Health check endpoint"
                )
            )
        )
    }
}
