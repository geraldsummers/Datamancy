package org.datamancy.datafetcher.fetchers

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Utility functions for dry-run verification
 */
object DryRunUtils {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Verify a URL is reachable with HTTP HEAD request
     */
    fun checkUrl(url: String, name: String = url): DryRunCheck {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (compatible; DatamancyBot/1.0; +https://datamancy.org)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..399) {
                    DryRunCheck(
                        name = "URL: $name",
                        passed = true,
                        message = "HTTP ${response.code}",
                        details = mapOf(
                            "url" to url,
                            "statusCode" to response.code
                        )
                    )
                } else {
                    DryRunCheck(
                        name = "URL: $name",
                        passed = false,
                        message = "HTTP ${response.code}: ${response.message}",
                        details = mapOf(
                            "url" to url,
                            "statusCode" to response.code
                        )
                    )
                }
            }
        } catch (e: Exception) {
            DryRunCheck(
                name = "URL: $name",
                passed = false,
                message = "Failed: ${e.message}",
                details = mapOf("url" to url, "error" to (e.message ?: "Unknown error"))
            )
        }
    }

    /**
     * Verify API endpoint with optional authentication
     */
    fun checkApiEndpoint(url: String, apiKey: String? = null, name: String = url): DryRunCheck {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .get()

            if (apiKey != null && apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isSuccessful -> DryRunCheck(
                        name = "API: $name",
                        passed = true,
                        message = "API accessible (${response.code})",
                        details = mapOf(
                            "url" to url,
                            "statusCode" to response.code,
                            "authenticated" to (apiKey != null)
                        )
                    )
                    response.code == 401 -> DryRunCheck(
                        name = "API: $name",
                        passed = false,
                        message = "Authentication failed (invalid API key)",
                        details = mapOf("url" to url, "statusCode" to 401)
                    )
                    else -> DryRunCheck(
                        name = "API: $name",
                        passed = false,
                        message = "HTTP ${response.code}",
                        details = mapOf("url" to url, "statusCode" to response.code)
                    )
                }
            }
        } catch (e: Exception) {
            DryRunCheck(
                name = "API: $name",
                passed = false,
                message = "Failed: ${e.message}",
                details = mapOf("url" to url)
            )
        }
    }

    /**
     * Verify database connection
     */
    fun checkDatabase(jdbcUrl: String, user: String, password: String, name: String): DryRunCheck {
        return try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                if (conn.isValid(5)) {
                    DryRunCheck(
                        name = "Database: $name",
                        passed = true,
                        message = "Connection successful",
                        details = mapOf(
                            "jdbcUrl" to jdbcUrl.substringBefore("?"),
                            "user" to user
                        )
                    )
                } else {
                    DryRunCheck(
                        name = "Database: $name",
                        passed = false,
                        message = "Connection invalid",
                        details = mapOf("jdbcUrl" to jdbcUrl.substringBefore("?"))
                    )
                }
            }
        } catch (e: Exception) {
            DryRunCheck(
                name = "Database: $name",
                passed = false,
                message = "Connection failed: ${e.message}",
                details = mapOf("jdbcUrl" to jdbcUrl.substringBefore("?"))
            )
        }
    }

    /**
     * Check if a directory exists and is writable
     */
    fun checkDirectory(path: String, name: String): DryRunCheck {
        return try {
            val dir = java.io.File(path)

            when {
                !dir.exists() -> {
                    // Try to create it
                    val created = dir.mkdirs()
                    if (created) {
                        DryRunCheck(
                            name = "Directory: $name",
                            passed = true,
                            message = "Created directory",
                            details = mapOf("path" to path, "created" to true)
                        )
                    } else {
                        DryRunCheck(
                            name = "Directory: $name",
                            passed = false,
                            message = "Failed to create directory",
                            details = mapOf("path" to path)
                        )
                    }
                }
                !dir.isDirectory -> DryRunCheck(
                    name = "Directory: $name",
                    passed = false,
                    message = "Path exists but is not a directory",
                    details = mapOf("path" to path)
                )
                !dir.canWrite() -> DryRunCheck(
                    name = "Directory: $name",
                    passed = false,
                    message = "Directory not writable",
                    details = mapOf("path" to path)
                )
                else -> DryRunCheck(
                    name = "Directory: $name",
                    passed = true,
                    message = "Directory exists and is writable",
                    details = mapOf("path" to path)
                )
            }
        } catch (e: Exception) {
            DryRunCheck(
                name = "Directory: $name",
                passed = false,
                message = "Check failed: ${e.message}",
                details = mapOf("path" to path)
            )
        }
    }

    /**
     * Verify API key is not empty
     */
    fun checkApiKey(apiKey: String, name: String): DryRunCheck {
        return if (apiKey.isBlank()) {
            DryRunCheck(
                name = "API Key: $name",
                passed = false,
                message = "API key not configured",
                details = emptyMap()
            )
        } else {
            DryRunCheck(
                name = "API Key: $name",
                passed = true,
                message = "API key configured (${apiKey.length} chars)",
                details = mapOf("length" to apiKey.length, "preview" to apiKey.take(4) + "...")
            )
        }
    }

    /**
     * Check configuration value is present
     */
    fun checkConfig(value: String?, name: String, required: Boolean = true): DryRunCheck {
        return if (value.isNullOrBlank()) {
            DryRunCheck(
                name = "Config: $name",
                passed = !required,
                message = if (required) "Required configuration missing" else "Optional configuration not set",
                details = emptyMap()
            )
        } else {
            DryRunCheck(
                name = "Config: $name",
                passed = true,
                message = "Configuration present",
                details = mapOf("value" to value)
            )
        }
    }
}
