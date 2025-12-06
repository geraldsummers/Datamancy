package reporting

import core.TestResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class JsonReporter {
    private val json = Json { prettyPrint = true }

    fun generate(results: List<TestResult>, outputFile: Path) {
        Files.createDirectories(outputFile.parent)
        val serializable = ResultsDTO(results.map { it.toDto() })
        Files.writeString(outputFile, json.encodeToString(serializable))
    }
}

@Serializable
private data class ResultsDTO(val results: List<ResultDTO>)

@Serializable
private data class ResultDTO(
    val service: String,
    val status: String,
    val message: String? = null
)

private fun TestResult.toDto(): ResultDTO = when (this) {
    is core.TestResult.Success -> ResultDTO(service, "SUCCESS", null)
    is core.TestResult.Timeout -> ResultDTO(service, "TIMEOUT", "${elapsed.toMillis()} ms")
    is core.TestResult.Failure -> ResultDTO(service, "FAILURE", "${error.type}: ${error.message}")
}
