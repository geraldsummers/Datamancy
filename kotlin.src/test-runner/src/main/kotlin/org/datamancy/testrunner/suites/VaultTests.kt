package org.datamancy.testrunner.suites

import io.ktor.http.*
import org.datamancy.testrunner.framework.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Vault Multi-User Integration Tests
 *
 * Validates:
 * - LDAP authentication with Vault
 * - Per-user secret isolation (users cannot read other users' secrets)
 * - Shared service secret access (read-only)
 * - Admin policy enforcement (full access)
 * - Concurrent multi-user operations
 * - Policy-based access control
 *
 * Architecture:
 * - Users authenticate with LDAP credentials → get Vault token
 * - Each user gets isolated path: secret/users/{username}/ *
 * - Shared secrets at: secret/services/ * (read-only for users)
 * - Admins get full access via admin policy
 */
suspend fun TestRunner.vaultTests() = suite("Vault Multi-User Tests") {

    // ═══════════════════════════════════════════════════════════════
    // Phase 1: Health & Connectivity
    // ═══════════════════════════════════════════════════════════════

    test("Vault server is healthy and unsealed") {
        val health = vaultHelper.healthCheck()
        health.initialized shouldBe true
        health.sealed shouldBe false
    }

    test("Vault API endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.vault}/v1/sys/health")
        response.status shouldBe HttpStatusCode.OK
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 2: LDAP Authentication
    // ═══════════════════════════════════════════════════════════════

    test("LDAP auth backend is enabled") {
        // Try to access LDAP login endpoint (doesn't require auth)
        val response = client.getRawResponse("${env.endpoints.vault}/v1/auth/ldap/login/test")
        // Should return 400 (bad request) not 404 (not found)
        response.status.value shouldBeOneOf listOf(400, 401)
    }

    test("Admin user can authenticate with LDAP credentials") {
        val token = vaultHelper.loginWithLdap("sysadmin", env.adminPassword)
        token.isNotBlank() shouldBe true

        // Verify token has admin policy
        val tokenInfo = vaultHelper.lookupSelf(token)
        require("admin" in tokenInfo.policies) { "Expected admin policy in ${tokenInfo.policies}" }
    }

    test("Regular user can authenticate with LDAP credentials") {
        // Skip if LDAP helper not available
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)
            token.isNotBlank() shouldBe true

            // Verify token has user-template policy
            val tokenInfo = vaultHelper.lookupSelf(token)
            require("user-template" in tokenInfo.policies) { "Expected user-template policy in ${tokenInfo.policies}" }
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("Invalid LDAP credentials are rejected") {
        val exception = runCatching {
            vaultHelper.loginWithLdap("nonexistent-user-${UUID.randomUUID()}", "wrongpassword")
        }.exceptionOrNull()

        require(exception != null) { "Expected exception for invalid credentials" }
        require(exception.message?.contains("LDAP login failed") == true) { "Expected 'LDAP login failed' in ${exception.message}" }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3: Per-User Secret Isolation
    // ═══════════════════════════════════════════════════════════════

    test("User can write secrets to own path") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)

            // Write to own path
            vaultHelper.writeSecret(
                token,
                "users/${testUser.username}/api-keys/test-exchange",
                mapOf(
                    "api_key" to "test-key-123",
                    "api_secret" to "test-secret-456"
                )
            )

            // Read back to verify
            val data = vaultHelper.readSecret(token, "users/${testUser.username}/api-keys/test-exchange")
            require(data != null) { "Expected data to be non-null" }
            data["api_key"] shouldBe "test-key-123"
            data["api_secret"] shouldBe "test-secret-456"
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("User can update their own secrets") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)

            // Write initial secret
            vaultHelper.writeSecret(
                token,
                "users/${testUser.username}/config",
                mapOf("version" to "1")
            )

            // Update secret
            vaultHelper.writeSecret(
                token,
                "users/${testUser.username}/config",
                mapOf("version" to "2", "updated" to "true")
            )

            // Verify update
            val data = vaultHelper.readSecret(token, "users/${testUser.username}/config")
            data!!["version"] shouldBe "2"
            data["updated"] shouldBe "true"
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("User can delete their own secrets") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)

            // Write secret
            vaultHelper.writeSecret(
                token,
                "users/${testUser.username}/temp",
                mapOf("data" to "temporary")
            )

            // Delete secret
            vaultHelper.deleteSecret(token, "users/${testUser.username}/temp")

            // Verify deletion
            val data = vaultHelper.readSecret(token, "users/${testUser.username}/temp")
            data shouldBe null
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("User CANNOT read other users' secrets") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val alice = ldapHelper.createEphemeralUser().getOrThrow()
        val bob = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val aliceToken = vaultHelper.loginWithLdap(alice.username, alice.password)
            val bobToken = vaultHelper.loginWithLdap(bob.username, bob.password)

            // Alice writes her secret
            vaultHelper.writeSecret(
                aliceToken,
                "users/${alice.username}/private",
                mapOf("secret" to "alice-only-data")
            )

            // Bob tries to read Alice's secret (should fail)
            val exception = runCatching {
                vaultHelper.readSecret(bobToken, "users/${alice.username}/private")
            }.exceptionOrNull()

            require(exception != null) { "Expected exception when Bob tries to read Alice's secret" }
            require(exception.message?.contains("Forbidden") == true) { "Expected 'Forbidden' in ${exception.message}" }
        } finally {
            ldapHelper.deleteTestUser(alice.username)
            ldapHelper.deleteTestUser(bob.username)
        }
    }

    test("User CANNOT write to other users' paths") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val alice = ldapHelper.createEphemeralUser().getOrThrow()
        val bob = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val bobToken = vaultHelper.loginWithLdap(bob.username, bob.password)

            // Bob tries to write to Alice's path (should fail)
            val exception = runCatching {
                vaultHelper.writeSecret(
                    bobToken,
                    "users/${alice.username}/hacked",
                    mapOf("malicious" to "data")
                )
            }.exceptionOrNull()

            require(exception != null) { "Expected exception when Bob tries to write to Alice's path" }
            require(exception.message?.contains("permission denied") == true) { "Expected 'permission denied' in ${exception.message}" }
        } finally {
            ldapHelper.deleteTestUser(alice.username)
            ldapHelper.deleteTestUser(bob.username)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 4: Shared Service Secrets
    // ═══════════════════════════════════════════════════════════════

    test("User can READ shared service secrets") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)

            // User should be able to read example service secret
            val data = vaultHelper.readSecret(token, "services/example")
            require(data != null) { "Expected to read service secret" }
            data["key"] shouldBe "example-value"
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("User CANNOT write to service secrets path") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)

            // Try to overwrite service secret (should fail)
            val exception = runCatching {
                vaultHelper.writeSecret(
                    token,
                    "services/postgres",
                    mapOf("password" to "hacked")
                )
            }.exceptionOrNull()

            require(exception != null) { "Expected exception when user tries to write service secret" }
            require(exception.message?.contains("permission denied") == true) { "Expected 'permission denied' in ${exception.message}" }
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 5: Admin Policy Enforcement
    // ═══════════════════════════════════════════════════════════════

    test("Admin can read any user's secrets") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val userToken = vaultHelper.loginWithLdap(testUser.username, testUser.password)
            val adminToken = vaultHelper.loginWithLdap("sysadmin", env.adminPassword)

            // User writes their secret
            vaultHelper.writeSecret(
                userToken,
                "users/${testUser.username}/data",
                mapOf("info" to "user-data")
            )

            // Admin reads user's secret (should succeed)
            val data = vaultHelper.readSecret(adminToken, "users/${testUser.username}/data")
            require(data != null) { "Expected admin to read user's secret" }
            data["info"] shouldBe "user-data"
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }

    test("Admin can write to service secrets") {
        val adminToken = vaultHelper.loginWithLdap("sysadmin", env.adminPassword)

        // Admin creates a new service secret
        vaultHelper.writeSecret(
            adminToken,
            "services/test-service",
            mapOf("key" to "test-value")
        )

        // Verify it was written
        val data = vaultHelper.readSecret(adminToken, "services/test-service")
        require(data != null) { "Expected to read test service secret" }
        data["key"] shouldBe "test-value"

        // Clean up
        vaultHelper.deleteSecret(adminToken, "services/test-service")
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 6: Concurrent Multi-User Access
    // ═══════════════════════════════════════════════════════════════

    test("Multiple users can access their secrets concurrently") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val alice = ldapHelper.createEphemeralUser().getOrThrow()
        val bob = ldapHelper.createEphemeralUser().getOrThrow()
        val charlie = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            coroutineScope {
                val aliceTask = async {
                    val token = vaultHelper.loginWithLdap(alice.username, alice.password)
                    vaultHelper.writeSecret(token, "users/${alice.username}/test", mapOf("user" to "alice"))
                    vaultHelper.readSecret(token, "users/${alice.username}/test")
                }

                val bobTask = async {
                    val token = vaultHelper.loginWithLdap(bob.username, bob.password)
                    vaultHelper.writeSecret(token, "users/${bob.username}/test", mapOf("user" to "bob"))
                    vaultHelper.readSecret(token, "users/${bob.username}/test")
                }

                val charlieTask = async {
                    val token = vaultHelper.loginWithLdap(charlie.username, charlie.password)
                    vaultHelper.writeSecret(token, "users/${charlie.username}/test", mapOf("user" to "charlie"))
                    vaultHelper.readSecret(token, "users/${charlie.username}/test")
                }

                val aliceData = aliceTask.await()
                val bobData = bobTask.await()
                val charlieData = charlieTask.await()

                aliceData!!["user"] shouldBe "alice"
                bobData!!["user"] shouldBe "bob"
                charlieData!!["user"] shouldBe "charlie"
            }
        } finally {
            ldapHelper.deleteTestUser(alice.username)
            ldapHelper.deleteTestUser(bob.username)
            ldapHelper.deleteTestUser(charlie.username)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 7: Token Management
    // ═══════════════════════════════════════════════════════════════

    test("Invalid token is rejected") {
        val exception = runCatching {
            vaultHelper.readSecret("invalid-token-12345", "users/test/secret")
        }.exceptionOrNull()

        require(exception != null) { "Expected exception for invalid token" }
    }

    test("Token lookup-self works for authenticated users") {
        if (ldapHelper == null) {
            println("  ⊘ SKIPPED (LDAP not configured)")
            return@test
        }

        val testUser = ldapHelper.createEphemeralUser().getOrThrow()

        try {
            val token = vaultHelper.loginWithLdap(testUser.username, testUser.password)
            val tokenInfo = vaultHelper.lookupSelf(token)

            require(tokenInfo.displayName.contains(testUser.username)) { "Expected displayName to contain ${testUser.username}, got ${tokenInfo.displayName}" }
            require("user-template" in tokenInfo.policies) { "Expected user-template policy in ${tokenInfo.policies}" }
        } finally {
            ldapHelper.deleteTestUser(testUser.username)
        }
    }
}
