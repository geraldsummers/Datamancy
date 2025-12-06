package core

import com.microsoft.playwright.Page
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ScreenshotCollector(private val outputDir: Path, private val serviceName: String) {
    private val screenshots = mutableListOf<Screenshot>()

    fun captureFinal(
        page: Page,
        metadata: Map<String, String> = emptyMap()
    ): Screenshot {
        val sanitizedName = serviceName.replace(" ", "_")
        val pngPath = outputDir.resolve("${sanitizedName}.png")
        val htmlPath = outputDir.resolve("${sanitizedName}.html")

        Files.createDirectories(outputDir)

        // Capture screenshot
        page.screenshot(Page.ScreenshotOptions().setPath(pngPath).setFullPage(true))

        // Save HTML
        val html = page.content()
        Files.writeString(htmlPath, html)

        val shot = Screenshot(
            name = "final_state",
            timestamp = Instant.now(),
            path = pngPath,
            pageUrl = page.url(),
            metadata = metadata + mapOf("htmlPath" to htmlPath.toString())
        )
        screenshots.add(shot)
        return shot
    }

    fun getAll(): List<Screenshot> = screenshots.toList()
}
