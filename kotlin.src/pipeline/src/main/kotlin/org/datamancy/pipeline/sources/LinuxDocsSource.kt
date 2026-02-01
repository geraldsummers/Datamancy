package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.datamancy.pipeline.core.Source
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Fetches Linux documentation from various sources:
 * - Man pages from /usr/share/man
 * - Debian docs from /usr/share/doc
 * - Kernel docs (if available)
 */
class LinuxDocsSource(
    private val sources: List<DocSource> = listOf(DocSource.MAN_PAGES),
    private val maxDocs: Int = Int.MAX_VALUE
) : Source<LinuxDoc> {
    override val name = "LinuxDocsSource"

    enum class DocSource {
        MAN_PAGES,      // /usr/share/man
        DEBIAN_DOCS,    // /usr/share/doc
        KERNEL_DOCS     // /usr/share/doc/linux-doc (if installed)
    }

    override suspend fun fetch(): Flow<LinuxDoc> = flow {
        logger.info { "Starting Linux docs fetch from sources: ${sources.joinToString()}" }

        var totalFetched = 0

        sources.forEach { source ->
            if (totalFetched >= maxDocs) return@forEach

            try {
                when (source) {
                    DocSource.MAN_PAGES -> fetchManPages(totalFetched, maxDocs)
                    DocSource.DEBIAN_DOCS -> fetchDebianDocs(totalFetched, maxDocs)
                    DocSource.KERNEL_DOCS -> fetchKernelDocs(totalFetched, maxDocs)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch docs from $source: ${e.message}" }
            }
        }

        logger.info { "Linux docs fetch complete: $totalFetched docs fetched" }
    }

    private suspend fun FlowCollector<LinuxDoc>.fetchManPages(startCount: Int, max: Int) {
        logger.info { "Fetching man pages..." }

        val manDirs = listOf("/usr/share/man", "/usr/local/share/man")
        var count = startCount

        for (manDir in manDirs) {
            if (count >= max) break

            val manRoot = File(manDir)
            if (!manRoot.exists()) {
                logger.debug { "Man directory not found: $manDir" }
                continue
            }

            // Walk through man sections (man1, man2, etc.)
            manRoot.listFiles()?.forEach { sectionDir ->
                if (count >= max) return@forEach
                if (!sectionDir.isDirectory) return@forEach
                if (!sectionDir.name.startsWith("man")) return@forEach

                val section = sectionDir.name.removePrefix("man")

                sectionDir.listFiles()?.forEach { manFile ->
                    if (count >= max) return@forEach

                    try {
                        // Man pages are often gzipped
                        val name = manFile.nameWithoutExtension.removeSuffix(".gz")
                        val content = readManPage(manFile)

                        if (content.isNotEmpty()) {
                            emit(LinuxDoc(
                                id = "man:$section:$name",
                                title = "$name($section)",
                                section = section,
                                type = "man",
                                content = content,
                                path = manFile.absolutePath
                            ))

                            count++

                            if (count % 100 == 0) {
                                logger.info { "Fetched $count man pages" }
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug(e) { "Failed to read man page ${manFile.name}: ${e.message}" }
                    }
                }
            }
        }

        logger.info { "Fetched ${count - startCount} man pages" }
    }

    private suspend fun FlowCollector<LinuxDoc>.fetchDebianDocs(startCount: Int, max: Int) {
        logger.info { "Fetching Debian documentation..." }

        val debianDocDir = File("/usr/share/doc")
        if (!debianDocDir.exists()) {
            logger.warn { "Debian doc directory not found: ${debianDocDir.absolutePath}" }
            return
        }

        var count = startCount

        debianDocDir.listFiles()?.forEach { packageDir ->
            if (count >= max) return@forEach
            if (!packageDir.isDirectory) return@forEach

            // Look for README, README.Debian, etc.
            val readmeFiles = packageDir.listFiles()?.filter { file ->
                file.isFile && (
                    file.name.startsWith("README") ||
                    file.name.equals("changelog.Debian.gz", ignoreCase = true) ||
                    file.name.equals("NEWS.Debian.gz", ignoreCase = true)
                )
            } ?: emptyList()

            readmeFiles.forEach { readmeFile ->
                if (count >= max) return@forEach

                try {
                    val content = readDebianDoc(readmeFile)
                    if (content.isNotEmpty() && content.length < 50000) {  // Skip huge files

                        emit(LinuxDoc(
                            id = "debian:${packageDir.name}:${readmeFile.name}",
                            title = "${packageDir.name} - ${readmeFile.name}",
                            section = packageDir.name,
                            type = "debian-doc",
                            content = content,
                            path = readmeFile.absolutePath
                        ))

                        count++
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to read ${readmeFile.name}: ${e.message}" }
                }
            }
        }

        logger.info { "Fetched ${count - startCount} Debian docs" }
    }

    private suspend fun FlowCollector<LinuxDoc>.fetchKernelDocs(startCount: Int, max: Int) {
        logger.info { "Fetching kernel documentation..." }

        val kernelDocDirs = listOf(
            "/usr/share/doc/linux-doc",
            "/usr/src/linux/Documentation"
        )

        var count = startCount

        for (kernelDocDir in kernelDocDirs) {
            if (count >= max) break

            val docRoot = File(kernelDocDir)
            if (!docRoot.exists()) {
                logger.debug { "Kernel doc directory not found: $kernelDocDir" }
                continue
            }

            // Recursively find .txt, .rst, .md files
            docRoot.walkTopDown().forEach { file ->
                if (count >= max) return@forEach
                if (!file.isFile) return@forEach
                if (!file.name.matches(Regex(".*\\.(txt|rst|md|asciidoc)$"))) return@forEach

                try {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.isNotEmpty() && content.length < 50000) {  // Skip huge files

                        val relativePath = file.relativeTo(docRoot).path

                        emit(LinuxDoc(
                            id = "kernel:$relativePath",
                            title = "Kernel: ${file.nameWithoutExtension}",
                            section = file.parentFile.name,
                            type = "kernel-doc",
                            content = content,
                            path = file.absolutePath
                        ))

                        count++
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to read ${file.name}: ${e.message}" }
                }
            }
        }

        logger.info { "Fetched ${count - startCount} kernel docs" }
    }

    private fun readManPage(file: File): String {
        return try {
            if (file.name.endsWith(".gz")) {
                // Decompress and read - simplified version
                val process = ProcessBuilder("zcat", file.absolutePath).start()
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to read man page ${file.name}" }
            ""
        }
    }

    private fun readDebianDoc(file: File): String {
        return try {
            if (file.name.endsWith(".gz")) {
                val process = ProcessBuilder("zcat", file.absolutePath).start()
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to read Debian doc ${file.name}" }
            ""
        }
    }
}

data class LinuxDoc(
    val id: String,
    val title: String,
    val section: String,  // man section, package name, or subdirectory
    val type: String,     // man, debian-doc, kernel-doc
    val content: String,
    val path: String
) {
    fun toText(): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("**Type:** $type")
            appendLine("**Section:** $section")
            appendLine("**Path:** $path")
            appendLine()
            appendLine("## Content")
            appendLine()

            // Clean up man page formatting
            val cleanedContent = content
                .replace(Regex("\\.TH[^\n]*\n"), "")  // Remove man page headers
                .replace(Regex("\\.SH[^\n]*\n"), "\n### ")  // Convert section headers
                .replace(Regex("\\.[A-Z]{2}[^\n]*\n"), "\n")  // Remove other man macros
                .take(2000)  // Truncate for embedding

            appendLine(cleanedContent)

            if (content.length > 2000) {
                appendLine("\n...")
                appendLine("*(Full content: ${content.length} characters)*")
            }
        }
    }

    fun contentHash(): String {
        return id.hashCode().toString()
    }
}
