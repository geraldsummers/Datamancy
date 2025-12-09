package reporting

import core.TestResult
import java.nio.file.Files
import java.nio.file.Path

class HtmlReporter {
    fun generate(results: List<TestResult>, outputDir: Path) {
        Files.createDirectories(outputDir)
        val html = buildString {
            append("<html><head><title>Test Report</title></head><body>")
            append("<h1>Datamancy Stack Test Report</h1>")
            append("<ul>")
            results.forEach { r ->
                when (r) {
                    is core.TestResult.Success -> append("<li><strong>${r.service}</strong>: SUCCESS (${r.duration.toMillis()} ms)</li>")
                    is core.TestResult.Failure -> append("<li><strong>${r.service}</strong>: FAILURE - ${r.error.type}: ${r.error.message}</li>")
                    is core.TestResult.Timeout -> append("<li><strong>${r.service}</strong>: TIMEOUT after ${r.elapsed.toMillis()} ms</li>")
                }
            }
            append("</ul>")
            append("</body></html>")
        }
        Files.writeString(outputDir.resolve("index.html"), html)
    }
}
