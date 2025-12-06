package core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import scenarios.AutheliaForwardAuthScenario
import scenarios.OIDCLoginScenario
import scenarios.DirectLoginScenario
import scenarios.TestScenario
import services.AuthType
import services.ServiceDefinition

data class TestReport(
    val runId: String,
    val results: List<TestResult>
)

private data class ServiceGroups(
    val infrastructure: List<ServiceDefinition>,
    val applications: List<ServiceDefinition>
)

class TestOrchestrator(
    private val browserPool: BrowserPool,
    private val output: java.nio.file.Path,
    concurrency: Int = 5
) {
    private val semaphore = Semaphore(concurrency)

    suspend fun runTests(services: List<ServiceDefinition>): TestReport = coroutineScope {
        // Run all tests in parallel
        val results = services.map { svc ->
            async { runServiceTest(svc) }
        }.awaitAll()

        TestReport(java.util.UUID.randomUUID().toString(), results)
    }

    private suspend fun runServiceTest(service: ServiceDefinition): TestResult =
        semaphore.withPermit {
            browserPool.withContext { ctx ->
                val scenario: TestScenario = when (service.authType) {
                    AuthType.AUTHELIA_FORWARD -> AutheliaForwardAuthScenario()
                    AuthType.OIDC -> OIDCLoginScenario()
                    AuthType.NONE -> DirectLoginScenario()
                    else -> AutheliaForwardAuthScenario()
                }
                val collector = ScreenshotCollector(output, service.name)
                scenario.execute(service, ctx, collector)
            }
        }
}
