package org.datamancy.controlpanel

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Marks a test as an integration test that requires Docker network access.
 *
 * Integration tests:
 * - Require running services (postgres, clickhouse, control-panel, etc.)
 * - Must run inside Docker network to access services by name
 * - Are automatically delegated to Docker when running `./gradlew test` on host
 * - Can specify required services that must be healthy before test execution
 *
 * Usage:
 * ```kotlin
 * @IntegrationTest
 * class MyServiceIntegrationTest {
 *     @Test
 *     fun `test real service interaction`() {
 *         // Can access http://control-panel:8097, postgres:5432, etc.
 *     }
 * }
 *
 * @IntegrationTest(requiredServices = ["postgres", "clickhouse"])
 * class DatabaseIntegrationTest {
 *     @Test
 *     fun `test with database dependencies`() {
 *         // postgres and clickhouse will be verified healthy before test runs
 *     }
 * }
 * ```
 *
 * @param requiredServices List of Docker service names that must be healthy.
 *                         If empty, test runs without health checks.
 *                         Valid values: postgres, clickhouse, control-panel, data-fetcher,
 *                                      unified-indexer, search-service, qdrant, caddy, etc.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@ExtendWith(IntegrationTestExtension::class)
annotation class IntegrationTest(
    val requiredServices: Array<String> = []
)
