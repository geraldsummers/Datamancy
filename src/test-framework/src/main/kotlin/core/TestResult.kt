package core

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

sealed class TestResult {
    data class Success(
        val service: String,
        val duration: Duration,
        val screenshots: List<Screenshot>,
        val checkpoints: List<CheckpointResult>
    ) : TestResult()

    data class Failure(
        val service: String,
        val error: TestError,
        val screenshots: List<Screenshot>,
        val partialCheckpoints: List<CheckpointResult>
    ) : TestResult()

    data class Timeout(
        val service: String,
        val elapsed: Duration,
        val lastScreenshot: Screenshot?
    ) : TestResult()
}

data class TestError(
    val type: ErrorType,
    val message: String,
    val stackTrace: String? = null
)

enum class ErrorType {
    AUTH_FAILED,
    SELECTOR_NOT_FOUND,
    NETWORK_ERROR,
    SERVICE_UNHEALTHY,
    UNEXPECTED_REDIRECT,
    OAUTH_CONSENT_TIMEOUT,
    UNKNOWN
}

data class Screenshot(
    val name: String,
    val timestamp: Instant,
    val path: Path,
    val pageUrl: String,
    val metadata: Map<String, String> = emptyMap()
)

sealed class CheckpointResult {
    data class Success(val description: String) : CheckpointResult()
    data class Failure(val description: String, val reason: String?) : CheckpointResult()
}
