package org.datamancy.stacktests.models

import kotlinx.serialization.Serializable

/**
 * Represents a service in the stack with its discovered endpoints.
 */
@Serializable
data class ServiceSpec(
    val name: String,
    val containerName: String,
    val baseUrl: String,
    val type: ServiceType,
    val requiredServices: List<String> = emptyList(),
    val endpoints: List<EndpointSpec> = emptyList()
) {
    /**
     * Test class name for this service.
     */
    val testClassName: String
        get() = name.split("-")
            .joinToString("") { it.replaceFirstChar { char -> char.uppercase() } } + "ApiTests"
}

@Serializable
enum class ServiceType {
    KOTLIN_KTOR,      // Our Kotlin/Ktor services
    DOCKER_EXTERNAL,  // External services from docker-compose
    UNKNOWN
}

/**
 * Registry of all discovered services and endpoints.
 */
@Serializable
data class StackEndpointsRegistry(
    val services: List<ServiceSpec>,
    val discoveryTimestamp: String,
    val totalEndpoints: Int
) {
    companion object {
        fun empty() = StackEndpointsRegistry(
            services = emptyList(),
            discoveryTimestamp = java.time.Instant.now().toString(),
            totalEndpoints = 0
        )
    }
}
