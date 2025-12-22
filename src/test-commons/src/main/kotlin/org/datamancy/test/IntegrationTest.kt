package org.datamancy.test

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Shared marker annotation for integration tests that require real services.
 * These tests are run against actual Docker services in the backend network.
 *
 * Usage:
 * ```kotlin
 * @IntegrationTest(requiredServices = ["data-fetcher", "postgres"])
 * class MyIntegrationTest {
 *     @Test
 *     fun `test real service interaction`() {
 *         // Services will be verified healthy before test runs
 *     }
 * }
 * ```
 *
 * @param requiredServices List of Docker service names that must be healthy.
 *                         Valid values: agent-tool-server, postgres, clickhouse,
 *                                      mariadb, qdrant, ldap, litellm, docker-proxy, etc.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@ExtendWith(IntegrationTestExtension::class)
annotation class IntegrationTest(
    val requiredServices: Array<String> = []
)
