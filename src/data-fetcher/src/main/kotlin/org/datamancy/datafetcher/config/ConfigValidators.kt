package org.datamancy.datafetcher.config

/**
 * Validation functions for configuration objects.
 * These are extracted for easy unit testing.
 */
object ConfigValidators {

    /**
     * Validates RSS feed configuration.
     */
    fun validateRssFeed(feed: RssFeed): ValidationResult {
        val errors = mutableListOf<String>()

        if (feed.url.isBlank()) {
            errors.add("Feed URL cannot be blank")
        }

        if (!feed.url.startsWith("http://") && !feed.url.startsWith("https://")) {
            errors.add("Feed URL must start with http:// or https://")
        }

        if (feed.category.isBlank()) {
            errors.add("Feed category cannot be blank")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates that API key is not obviously invalid.
     */
    fun validateApiKey(key: String, keyName: String = "API key"): ValidationResult {
        val errors = mutableListOf<String>()

        if (key.isBlank()) {
            errors.add("$keyName cannot be blank")
        }

        if (key.length < 8) {
            errors.add("$keyName appears too short (< 8 characters)")
        }

        if (key.contains(" ")) {
            errors.add("$keyName should not contain spaces")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates market data symbol.
     */
    fun validateSymbol(symbol: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (symbol.isBlank()) {
            errors.add("Symbol cannot be blank")
        }

        if (symbol.length > 10) {
            errors.add("Symbol appears too long (> 10 characters)")
        }

        if (!symbol.matches(Regex("[A-Za-z0-9.-]+"))) {
            errors.add("Symbol contains invalid characters")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates search query.
     */
    fun validateSearchQuery(query: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (query.isBlank()) {
            errors.add("Query cannot be blank")
        }

        if (query.length < 2) {
            errors.add("Query too short (< 2 characters)")
        }

        if (query.length > 500) {
            errors.add("Query too long (> 500 characters)")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates URL format.
     */
    fun validateUrl(url: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (url.isBlank()) {
            errors.add("URL cannot be blank")
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            errors.add("URL must start with http:// or https://")
        }

        try {
            java.net.URL(url)
        } catch (e: Exception) {
            errors.add("URL format is invalid: ${e.message}")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Validates schedule interval format.
     */
    fun validateScheduleInterval(interval: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (interval.isBlank()) {
            errors.add("Interval cannot be blank")
        }

        // Check format: number + unit (e.g., "5m", "1h", "30s")
        val regex = Regex("^(\\d+)([smhd])$")
        if (!regex.matches(interval)) {
            errors.add("Interval must be in format: <number><unit> (e.g., 5m, 1h, 30s, 1d)")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
}

/**
 * Result of a validation operation.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errorMessages: List<String>) : ValidationResult()

    fun isValid(): Boolean = this is Valid
    fun isInvalid(): Boolean = this is Invalid
    fun getErrors(): List<String> = if (this is Invalid) errorMessages else emptyList()
}
