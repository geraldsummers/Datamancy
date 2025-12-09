package reporting

import core.TestReport
import core.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ResultAggregator(
    private val htmlReporter: HtmlReporter = HtmlReporter(),
    private val jsonReporter: JsonReporter = JsonReporter()
) {
    fun aggregate(results: List<TestResult>, baseOutput: Path): TestReport {
        val runId = UUID.randomUUID().toString()
        val outputDir = baseOutput.resolve(runId)
        Files.createDirectories(outputDir)

        // Simple passthrough for now; reporters handle screenshots if needed later
        htmlReporter.generate(results, outputDir)
        jsonReporter.generate(results, outputDir.resolve("results.json"))

        return TestReport(runId, results)
    }
}
