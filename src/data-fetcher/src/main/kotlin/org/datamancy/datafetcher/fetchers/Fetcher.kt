package org.datamancy.datafetcher.fetchers

import org.datamancy.datafetcher.scheduler.FetchResult

/**
 * Base interface for all data fetchers
 */
interface Fetcher {
    /**
     * Perform the actual data fetch
     */
    suspend fun fetch(): FetchResult

    /**
     * Dry-run mode: Verify connectivity and configuration without fetching data
     * Should check:
     * - URLs are reachable (HTTP HEAD requests)
     * - API keys are valid
     * - Database connections work
     * - Required directories exist
     * Returns verification results
     */
    suspend fun dryRun(): DryRunResult
}

/**
 * Result of a dry-run verification
 */
data class DryRunResult(
    val checks: List<DryRunCheck>,
    val success: Boolean = checks.all { it.passed }
) {
    fun summary(): String {
        val passed = checks.count { it.passed }
        val total = checks.size
        return "Dry run: $passed/$total checks passed"
    }
}

data class DryRunCheck(
    val name: String,
    val passed: Boolean,
    val message: String,
    val details: Map<String, Any> = emptyMap()
)
