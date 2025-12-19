package org.datamancy.stacktests.discovery

import com.charleskorn.kaml.Yaml
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.datamancy.stacktests.models.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Parses docker-compose.yml to discover external service healthcheck endpoints.
 */
class DockerComposeParser(private val composeFile: File) {

    private val yaml = Yaml.default

    /**
     * Parse docker-compose.yml and extract service healthcheck URLs.
     */
    fun parseServices(): List<ServiceSpec> {
        if (!composeFile.exists()) {
            logger.warn { "docker-compose.yml not found: $composeFile" }
            return emptyList()
        }

        logger.info { "Parsing docker-compose.yml: $composeFile" }

        val content = composeFile.readText()
        val compose = yaml.decodeFromString(DockerCompose.serializer(), content)

        val services = mutableListOf<ServiceSpec>()

        compose.services.forEach { (serviceName, serviceConfig) ->
            val endpoints = extractHealthcheckEndpoint(serviceName, serviceConfig)
            if (endpoints.isNotEmpty()) {
                services.add(
                    ServiceSpec(
                        name = serviceName,
                        containerName = serviceConfig.containerName ?: serviceName,
                        baseUrl = "http://$serviceName",
                        type = ServiceType.DOCKER_EXTERNAL,
                        requiredServices = serviceConfig.dependsOn?.keys?.toList() ?: emptyList(),
                        endpoints = endpoints
                    )
                )
            }
        }

        logger.info { "Discovered ${services.size} external services with healthchecks" }

        return services
    }

    /**
     * Extract healthcheck endpoint from service configuration.
     */
    private fun extractHealthcheckEndpoint(
        serviceName: String,
        config: ServiceConfig
    ): List<EndpointSpec> {
        val healthcheck = config.healthcheck ?: return emptyList()
        val testCommand = healthcheck.test ?: return emptyList()

        // Parse test command array: ["CMD", "curl", "-f", "http://localhost:8080/health"]
        val commandStr = testCommand.joinToString(" ")

        // Extract URL from various healthcheck patterns
        val urlPatterns = listOf(
            Regex("""https?://[^\s"']+"""),                    // Full URL
            Regex("""curl.*?(https?://[^\s"']+)"""),          // curl with URL
            Regex("""wget.*?(https?://[^\s"']+)"""),          // wget with URL
            Regex("""localhost:(\d+)(/[^\s"']*)?""")          // localhost:port/path
        )

        for (pattern in urlPatterns) {
            val match = pattern.find(commandStr)
            if (match != null) {
                var url = match.groupValues[0]

                // Replace localhost with service name
                url = url.replace("localhost", serviceName)
                url = url.replace("127.0.0.1", serviceName)

                // Ensure URL starts with http://
                if (!url.startsWith("http")) {
                    url = "http://$serviceName:$url"
                }

                // Extract path from URL
                val urlParts = url.removePrefix("http://").removePrefix("https://").split("/", limit = 2)
                val hostPort = urlParts[0]
                val path = if (urlParts.size > 1) "/${urlParts[1]}" else "/"

                // Determine response type from path
                val responseType = when {
                    path.contains("/health") -> ResponseType.JSON
                    path.contains("/ready") -> ResponseType.JSON
                    path.contains("/ping") -> ResponseType.TEXT
                    path == "/" -> ResponseType.HTML
                    else -> ResponseType.JSON
                }

                logger.debug { "Extracted healthcheck for $serviceName: GET $path" }

                return listOf(
                    EndpointSpec(
                        method = HttpMethod.GET,
                        path = path,
                        serviceUrl = "http://$serviceName",
                        sourceFile = "docker-compose.yml",
                        expectedResponseType = responseType,
                        description = "Healthcheck endpoint"
                    )
                )
            }
        }

        logger.debug { "Could not extract healthcheck URL from: $commandStr" }
        return emptyList()
    }
}

// Kotlin serialization models for docker-compose.yml

@Serializable
data class DockerCompose(
    val services: Map<String, ServiceConfig> = emptyMap(),
    val networks: Map<String, NetworkConfig>? = null,
    val volumes: Map<String, VolumeConfig>? = null
)

@Serializable
data class ServiceConfig(
    @SerialName("container_name")
    val containerName: String? = null,
    val image: String? = null,
    val build: BuildConfig? = null,
    @SerialName("depends_on")
    val dependsOn: Map<String, DependencyConfig>? = null,
    val healthcheck: HealthcheckConfig? = null,
    val ports: List<String>? = null,
    @Contextual
    val networks: Any? = null,  // Can be list or map
    @Contextual
    val environment: Any? = null  // Can be list or map
)

@Serializable
data class BuildConfig(
    val context: String? = null,
    val dockerfile: String? = null
)

@Serializable
data class DependencyConfig(
    val condition: String? = null
)

@Serializable
data class HealthcheckConfig(
    val test: List<String>? = null,
    val interval: String? = null,
    val timeout: String? = null,
    val retries: Int? = null,
    @SerialName("start_period")
    val startPeriod: String? = null
)

@Serializable
data class NetworkConfig(
    val driver: String? = null,
    @Contextual
    val ipam: Any? = null  // Can be complex, we don't need it for endpoint discovery
)

@Serializable
data class VolumeConfig(
    val driver: String? = null,
    @SerialName("driver_opts")
    @Contextual
    val driverOpts: Any? = null  // Can be map
)
