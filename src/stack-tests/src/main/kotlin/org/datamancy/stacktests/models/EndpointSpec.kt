package org.datamancy.stacktests.models

import kotlinx.serialization.Serializable

/**
 * Represents a discovered HTTP endpoint.
 */
@Serializable
data class EndpointSpec(
    val method: HttpMethod,
    val path: String,
    val serviceUrl: String,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val requiresAuth: Boolean = false,
    val parameters: List<PathParameter> = emptyList(),
    val expectedResponseType: ResponseType = ResponseType.JSON,
    val description: String? = null
) {
    /**
     * Full URL for this endpoint.
     */
    val fullUrl: String
        get() = "$serviceUrl$path"

    /**
     * Test method name (sanitized for Kotlin).
     */
    val testMethodName: String
        get() {
            val sanitizedPath = path
                .replace(Regex("[{}/:]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .joinToString(" ")

            return "${method.name} $sanitizedPath returns valid response"
        }
}

@Serializable
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}

@Serializable
data class PathParameter(
    val name: String,
    val type: ParameterType = ParameterType.STRING
)

@Serializable
enum class ParameterType {
    STRING, INT, UUID
}

@Serializable
enum class ResponseType {
    JSON,
    JSON_ARRAY,
    JSON_OBJECT,
    TEXT,
    HTML,
    XML,
    BINARY,
    EMPTY
}
