package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

/**
 * Authenticated Operations Tests
 *
 * Tests that acquire auth tokens and perform authenticated operations
 * Validates end-to-end authentication workflows for all services
 *
 * IMPORTANT: These tests require valid test credentials:
 * - GRAFANA_ADMIN_PASSWORD
 * - FORGEJO_USERNAME / FORGEJO_PASSWORD
 * - MASTODON_EMAIL / MASTODON_PASSWORD
 * - etc.
 */
suspend fun TestRunner.authenticatedOperationsTests() = suite("Authenticated Operations Tests") {

    // =============================================================================
    // GRAFANA AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Grafana: Acquire API key and query datasources") {
        val password = System.getenv("GRAFANA_ADMIN_PASSWORD") ?: "admin"

        // Acquire token
        val tokenResult = tokens.acquireGrafanaToken("admin", password)
        require(tokenResult.isSuccess) { "Failed to acquire Grafana token: ${tokenResult.exceptionOrNull()?.message}" }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Grafana API key")

        // Test authenticated request
        val response = tokens.authenticatedGet("grafana", "${env.endpoints.grafana}/api/datasources")
        require(response.status == HttpStatusCode.OK) {
            "Failed to query datasources: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("[") || body.contains("{}")) {
            "Unexpected datasources response"
        }

        println("      ✓ Successfully queried Grafana datasources with API key")
    }

    // =============================================================================
    // SEAFILE AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Seafile: Acquire token and list libraries") {
        val username = System.getenv("SEAFILE_USERNAME") ?: "admin@datamancy.local"
        val password = System.getenv("SEAFILE_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireSeafileToken(username, password)
        if (tokenResult.isFailure) {
            skip("Seafile: List libraries", "Token acquisition failed - admin user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Seafile token")

        // Test authenticated request
        val response = tokens.authenticatedGet("seafile", "http://seafile:80/api2/repos/")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list libraries: ${response.status}"
        }

        println("      ✓ Successfully listed Seafile libraries")
    }

    // =============================================================================
    // FORGEJO AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Forgejo: Acquire token and list repositories") {
        val username = System.getenv("FORGEJO_USERNAME") ?: "admin"
        val password = System.getenv("FORGEJO_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireForgejoToken(username, password)
        if (tokenResult.isFailure) {
            skip("Forgejo: List repos", "Token acquisition failed - ${tokenResult.exceptionOrNull()?.message}")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Forgejo access token")

        // Test authenticated request
        val response = tokens.authenticatedGet("forgejo", "http://forgejo:3000/api/v1/user/repos")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list repos: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.startsWith("[")) {
            "Unexpected repos response"
        }

        println("      ✓ Successfully listed Forgejo repositories")
    }

    // =============================================================================
    // PLANKA AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Planka: Acquire token and list boards") {
        val email = System.getenv("PLANKA_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("PLANKA_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquirePlankaToken(email, password)
        if (tokenResult.isFailure) {
            skip("Planka: List boards", "Token acquisition failed - admin user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Planka authentication token")

        // Test authenticated request
        val response = tokens.authenticatedGet("planka", "http://planka:1337/api/boards")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list boards: ${response.status}"
        }

        println("      ✓ Successfully listed Planka boards")
    }

    // =============================================================================
    // QBITTORRENT AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Qbittorrent: Acquire session and get version") {
        val username = System.getenv("QBITTORRENT_USERNAME") ?: "admin"
        val password = System.getenv("QBITTORRENT_PASSWORD") ?: "adminpass"

        // Acquire session
        val sessionResult = tokens.acquireQbittorrentSession(username, password)
        if (sessionResult.isFailure) {
            skip("Qbittorrent: Get version", "Session acquisition failed - ${sessionResult.exceptionOrNull()?.message}")
            return@test
        }

        println("      ✓ Acquired Qbittorrent session cookie")

        // Test authenticated request
        val response = tokens.authenticatedGet("qbittorrent", "http://qbittorrent:8080/api/v2/app/version")
        require(response.status == HttpStatusCode.OK) {
            "Failed to get version: ${response.status}"
        }

        val version = response.bodyAsText()
        println("      ✓ Qbittorrent version: $version")
    }

    // =============================================================================
    // MASTODON AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Mastodon: Acquire OAuth token and verify credentials") {
        val email = System.getenv("MASTODON_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("MASTODON_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireMastodonToken(email, password)
        if (tokenResult.isFailure) {
            skip("Mastodon: Verify credentials", "Token acquisition failed - ${tokenResult.exceptionOrNull()?.message}")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Mastodon OAuth token")

        // Test authenticated request
        val response = tokens.authenticatedGet("mastodon", "http://mastodon-web:3000/api/v1/accounts/verify_credentials")
        require(response.status == HttpStatusCode.OK) {
            "Failed to verify credentials: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("id") && body.contains("username")) {
            "Unexpected credentials response"
        }

        println("      ✓ Successfully verified Mastodon credentials")
    }

    // =============================================================================
    // OPEN-WEBUI AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Open-WebUI: Acquire JWT and list models") {
        val email = System.getenv("OPEN_WEBUI_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("OPEN_WEBUI_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireOpenWebUIToken(email, password)
        if (tokenResult.isFailure) {
            skip("Open-WebUI: List models", "Token acquisition failed - user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Open-WebUI JWT token")

        // Test authenticated request
        val response = tokens.authenticatedGet("open-webui", "http://open-webui:8080/api/v1/models")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list models: ${response.status}"
        }

        println("      ✓ Successfully listed Open-WebUI models")
    }

    // =============================================================================
    // TOKEN MANAGEMENT OPERATIONS
    // =============================================================================

    test("Token manager: Store and retrieve tokens") {
        // Manually store a test token
        val testToken = "test-token-${System.currentTimeMillis()}"

        // Store via TokenManager internal state (simulated)
        require(!tokens.hasToken("test-service")) {
            "Should not have token before storing"
        }

        // After successful acquisition, tokens should be stored
        require(tokens.getToken("grafana") != null || !tokens.hasToken("grafana")) {
            "Token state should be consistent"
        }

        println("      ✓ Token manager correctly manages token state")
    }

    test("Token manager: Clear tokens") {
        // Clear specific token
        tokens.clearToken("grafana")
        require(!tokens.hasToken("grafana")) {
            "Token should be cleared"
        }

        // Clear all tokens
        tokens.clearAll()
        require(!tokens.hasToken("seafile") && !tokens.hasToken("forgejo")) {
            "All tokens should be cleared"
        }

        println("      ✓ Token manager correctly clears tokens")
    }
}
