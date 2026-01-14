package org.datamancy.datafetcher.validation

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

/**
 * URL validation utility to prevent security vulnerabilities and ensure data quality.
 *
 * Validates URLs for:
 * - Correct format and syntax
 * - Allowed schemes (http/https)
 * - Protection against SSRF attacks
 * - Protection against path traversal
 */
object UrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    // Private IP ranges (RFC 1918)
    private val PRIVATE_IP_PATTERNS = listOf(
        Regex("^10\\..*"),
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"),
        Regex("^192\\.168\\..*"),
        Regex("^127\\..*"),  // Localhost
        Regex("^169\\.254\\..*"),  // Link-local
        Regex("^::1$"),  // IPv6 localhost
        Regex("^fe80:.*"),  // IPv6 link-local
        Regex("^fc00:.*"),  // IPv6 private
        Regex("^fd00:.*")   // IPv6 private
    )

    // SSRF-prone endpoints
    private val SSRF_PATTERNS = listOf(
        Regex("169\\.254\\.169\\.254"),  // AWS metadata
        Regex("metadata\\.google\\.internal"),  // GCP metadata
        Regex("100\\.100\\.100\\.200")  // Oracle Cloud metadata
    )

    /**
     * Validation result with details
     */
    data class ValidationResult(
        val isValid: Boolean,
        val sanitizedUrl: String? = null,
        val errorMessage: String? = null,
        val warnings: List<String> = emptyList()
    ) {
        companion object {
            fun valid(sanitizedUrl: String, warnings: List<String> = emptyList()) =
                ValidationResult(true, sanitizedUrl, null, warnings)

            fun invalid(message: String) =
                ValidationResult(false, null, message)
        }
    }

    /**
     * Validation options
     */
    data class ValidationOptions(
        val allowPrivateIps: Boolean = false,
        val allowLocalhost: Boolean = false,
        val maxUrlLength: Int = 2048,
        val requireHttps: Boolean = false
    )

    /**
     * Validate and sanitize a URL
     *
     * @param url The URL string to validate
     * @param options Validation options
     * @return ValidationResult with isValid, sanitizedUrl, or errorMessage
     */
    fun validate(url: String, options: ValidationOptions = ValidationOptions()): ValidationResult {
        // Check length
        if (url.length > options.maxUrlLength) {
            return ValidationResult.invalid("URL exceeds maximum length of ${options.maxUrlLength}")
        }

        // Check for null bytes (path traversal attack)
        if (url.contains('\u0000')) {
            return ValidationResult.invalid("URL contains null bytes")
        }

        // Parse URL
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return ValidationResult.invalid("Malformed URL: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ValidationResult.invalid("Invalid URL: ${e.message}")
        }

        // Check scheme
        val scheme = uri.scheme?.lowercase()
        if (scheme == null) {
            return ValidationResult.invalid("URL must have a scheme (http:// or https://)")
        }

        if (scheme !in ALLOWED_SCHEMES) {
            return ValidationResult.invalid("URL scheme '$scheme' not allowed. Must be http or https")
        }

        if (options.requireHttps && scheme != "https") {
            return ValidationResult.invalid("HTTPS is required")
        }

        // Check host
        val host = uri.host
        if (host.isNullOrBlank()) {
            return ValidationResult.invalid("URL must have a valid host")
        }

        val warnings = mutableListOf<String>()

        // Check for private IPs / localhost
        if (!options.allowLocalhost && (host == "localhost" || host == "127.0.0.1" || host == "[::1]")) {
            return ValidationResult.invalid("Localhost URLs are not allowed")
        }

        if (!options.allowPrivateIps) {
            for (pattern in PRIVATE_IP_PATTERNS) {
                if (pattern.matches(host)) {
                    return ValidationResult.invalid("Private IP addresses are not allowed: $host")
                }
            }
        }

        // Check for SSRF-prone endpoints
        for (pattern in SSRF_PATTERNS) {
            if (pattern.containsMatchIn(host)) {
                return ValidationResult.invalid("URL targets known metadata service: $host")
            }
        }

        // Normalize URL (remove trailing slash, etc.)
        val normalizedUrl = normalizeUrl(uri)

        // Check for suspicious patterns
        if (url.contains("..")) {
            warnings.add("URL contains '..' which may indicate path traversal")
        }

        if (url.contains("@")) {
            warnings.add("URL contains '@' which may indicate authentication or redirect")
        }

        return ValidationResult.valid(normalizedUrl, warnings)
    }

    /**
     * Normalize a URL for consistency
     */
    fun normalizeUrl(uri: URI): String {
        val path = uri.path?.removeSuffix("/") ?: ""
        val query = if (uri.query != null) "?${uri.query}" else ""
        val fragment = if (uri.fragment != null) "#${uri.fragment}" else ""

        return buildString {
            append(uri.scheme)
            append("://")
            append(uri.host)
            if (uri.port != -1 && uri.port != getDefaultPort(uri.scheme)) {
                append(":")
                append(uri.port)
            }
            append(path)
            append(query)
            append(fragment)
        }
    }

    /**
     * Normalize a URL string
     */
    fun normalizeUrl(url: String): String? {
        return try {
            val uri = URI(url)
            normalizeUrl(uri)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to normalize URL: $url" }
            null
        }
    }

    private fun getDefaultPort(scheme: String?): Int {
        return when (scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    /**
     * Quick validation check (returns boolean only)
     */
    fun isValid(url: String, options: ValidationOptions = ValidationOptions()): Boolean {
        return validate(url, options).isValid
    }

    /**
     * Validate or throw exception
     */
    fun validateOrThrow(url: String, options: ValidationOptions = ValidationOptions()): String {
        val result = validate(url, options)
        if (!result.isValid) {
            throw IllegalArgumentException("Invalid URL: ${result.errorMessage}")
        }
        return result.sanitizedUrl!!
    }
}
