package services

import config.ConfigLoader
import java.io.InputStream

class ServiceRegistry(private val services: List<ServiceDefinition>) {
    fun all(): List<ServiceDefinition> = services

    companion object {
        fun fromYaml(input: InputStream): ServiceRegistry {
            val loaded = ConfigLoader.loadServices(input)
            return ServiceRegistry(loaded.services.map { it.expandEnv() })
        }
    }
}

private fun ServiceDefinition.expandEnv(): ServiceDefinition = copy(
    url = url.expand(),
    credentials = credentials?.let { BasicCredentials(it.username.expand(), it.password.expand()) },
    token = token?.expand()
)

private fun String.expand(): String =
    Regex("\\$\\{([A-Za-z0-9_]+)}").replace(this) { match ->
        val key = match.groupValues[1]
        System.getenv(key) ?: match.value
    }
