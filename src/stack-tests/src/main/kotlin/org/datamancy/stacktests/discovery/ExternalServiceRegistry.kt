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
            service("couchdb", "http://couchdb:5984", "/_up"),
            service("clickhouse", "http://clickhouse:8123", "/ping"),

            // Authentication & Security
            service("authelia", "http://authelia:9091", "/api/health"),
            service("ldap-account-manager", "http://ldap-account-manager", "/lam/"),

            // AI/ML Services
            service("vllm", "http://vllm:8000", "/health"),
            service("embedding-service", "http://embedding-service:8080", "/health"),
            service("litellm", "http://litellm:4000", "/health"),

            // Monitoring & Observability
            service("grafana", "http://grafana:3000", "/api/health"),

            // Applications
            service("open-webui", "http://open-webui:8080", "/health"),
            service("vaultwarden", "http://vaultwarden:80", "/alive"),
            service("planka", "http://planka:1337", "/api/health"),
            service("bookstack", "http://bookstack:80", "/"),
            service("seafile", "http://seafile:8000", "/"),
            service("onlyoffice", "http://onlyoffice:80", "/healthcheck"),
            service("radicale", "http://radicale:5232", "/"),
            service("roundcube", "http://roundcube", "/"),
            service("forgejo", "http://forgejo:3000", "/"),
            service("qbittorrent", "http://qbittorrent:8080", "/"),

            // Matrix
            service("synapse", "http://synapse:8008", "/health"),
            service("element", "http://element", "/"),

            // Mastodon
            service("mastodon-web", "http://mastodon-web:3000", "/health"),
            service("mastodon-streaming", "http://mastodon-streaming:4000", "/api/v1/streaming/health"),

            // Jupyter & Collaboration
            service("jupyterhub", "http://jupyterhub:8000", "/hub/health"),
            service("homepage", "http://homepage:3000", "/"),
            service("homeassistant", "http://homeassistant:8123", "/"),

            // Infrastructure
            service("benthos", "http://benthos:4195", "/ready"),
            service("docker-proxy", "http://docker-proxy:2375", "/version"),
            service("qdrant", "http://qdrant:6333", "/"),

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
