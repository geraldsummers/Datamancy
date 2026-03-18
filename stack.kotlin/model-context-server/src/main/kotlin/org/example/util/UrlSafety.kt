package org.example.util

import java.net.InetAddress
import java.net.URI

object UrlSafety {
    private val metadataHosts = setOf(
        "169.254.169.254",
        "169.254.170.2",
        "metadata.google.internal"
    )

    fun parseAllowedHosts(raw: String?): List<String> {
        return (raw ?: "")
            .split(',', ';', '|', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    fun envFlag(name: String, defaultValue: Boolean): Boolean {
        return when ((System.getProperty(name) ?: System.getenv(name) ?: "").trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    fun validateHttpTarget(
        rawUrl: String,
        allowedHosts: List<String>,
        allowPrivateNets: Boolean
    ): URI {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "url must not be empty" }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("URL scheme is required")
        require(scheme == "http" || scheme == "https") { "Only http/https URLs are allowed" }
        require(uri.userInfo.isNullOrBlank()) { "URL userinfo is not allowed" }

        val host = uri.host?.trim()?.lowercase()
            ?: throw IllegalArgumentException("URL host is required")
        require(host.isNotBlank()) { "URL host is required" }

        if (isExplicitlyBlockedHost(host)) {
            throw IllegalArgumentException("Host '$host' is blocked")
        }

        val allowlisted = allowedHosts.any { pattern -> hostMatchesPattern(host, pattern) }
        if (!allowlisted) {
            val resolved = try {
                InetAddress.getAllByName(host).toList()
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to resolve host '$host': ${e.message}")
            }

            resolved.forEach { address ->
                val rawAddress = address.hostAddress.lowercase()
                if (isExplicitlyBlockedHost(rawAddress)) {
                    throw IllegalArgumentException("Resolved address '$rawAddress' for '$host' is blocked")
                }
                if (!allowPrivateNets && isPrivateAddress(address)) {
                    throw IllegalArgumentException("Private/link-local host '$host' is blocked")
                }
            }
        }

        return uri
    }

    private fun isExplicitlyBlockedHost(hostOrIp: String): Boolean {
        val value = hostOrIp.trim().lowercase()
        return value == "localhost" ||
            value == "host.docker.internal" ||
            value in metadataHosts
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
    }

    private fun hostMatchesPattern(host: String, patternRaw: String): Boolean {
        val pattern = patternRaw.trim().lowercase()
        if (pattern.isEmpty()) return false
        if (pattern == "*") return true
        if (pattern.startsWith("*.")) {
            val suffix = pattern.removePrefix("*.")
            return host == suffix || host.endsWith(".$suffix")
        }
        return host == pattern
    }
}
