package org.datamancy.datatransformer

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Content chunk with overlap information
 */
data class ContentChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val content: String,
    val contentSnippet: String,
    val overlapStart: Int = 0,
    val overlapEnd: Int = 0
)

/**
 * Chunking strategies for different content types
 */
object ChunkingStrategy {

    /**
     * Select appropriate chunking strategy based on source type
     */
    fun chunkContent(sourceType: String, content: String, title: String): List<ContentChunk> {
        return when (sourceType.lowercase()) {
            "cve" -> chunkCVE(content, maxSize = 3000)
            "legal", "legislation" -> chunkBySection(content, maxSize = 2000, overlap = 200)
            "rss", "news" -> chunkByParagraph(content, maxSize = 1500, overlap = 100)
            "wiki", "wikipedia" -> chunkByHeading(content, maxSize = 2500, overlap = 150)
            "docs", "documentation" -> chunkByCodeBlock(content, maxSize = 2000, overlap = 200)
            else -> chunkByCharacterLimit(content, maxSize = 1500, overlap = 100)
        }
    }

    /**
     * Chunk CVE content - most CVEs are small enough for a single chunk
     * Only split if affected products section is very large
     */
    fun chunkCVE(content: String, maxSize: Int = 3000): List<ContentChunk> {
        // Most CVEs fit in one chunk
        if (content.length <= maxSize) {
            return listOf(ContentChunk(
                chunkIndex = 0,
                totalChunks = 1,
                content = content,
                contentSnippet = content.take(200)
            ))
        }

        // If CVE is large, split by section headers
        val sections = content.split(Regex("(?=^## )", RegexOption.MULTILINE))
        val chunks = mutableListOf<ContentChunk>()
        val mainSection = StringBuilder()

        sections.forEachIndexed { idx, section ->
            if (idx == 0 || !section.startsWith("## Affected Products")) {
                // Keep description and metadata together
                mainSection.append(section)
            } else {
                // This is affected products - save main chunk first
                if (mainSection.isNotEmpty()) {
                    chunks.add(ContentChunk(
                        chunkIndex = chunks.size,
                        totalChunks = 0,
                        content = mainSection.toString().trim(),
                        contentSnippet = mainSection.toString().take(200)
                    ))
                    mainSection.clear()
                }

                // Add affected products as separate chunk(s)
                if (section.length <= maxSize) {
                    chunks.add(ContentChunk(
                        chunkIndex = chunks.size,
                        totalChunks = 0,
                        content = section.trim(),
                        contentSnippet = section.take(200)
                    ))
                } else {
                    // Split large affected products by lines
                    val lines = section.lines()
                    val currentChunk = StringBuilder()

                    lines.forEach { line ->
                        if (currentChunk.length + line.length > maxSize && currentChunk.isNotEmpty()) {
                            chunks.add(ContentChunk(
                                chunkIndex = chunks.size,
                                totalChunks = 0,
                                content = currentChunk.toString().trim(),
                                contentSnippet = currentChunk.toString().take(200)
                            ))
                            currentChunk.clear()
                        }
                        currentChunk.appendLine(line)
                    }

                    if (currentChunk.isNotEmpty()) {
                        chunks.add(ContentChunk(
                            chunkIndex = chunks.size,
                            totalChunks = 0,
                            content = currentChunk.toString().trim(),
                            contentSnippet = currentChunk.toString().take(200)
                        ))
                    }
                }
            }
        }

        // Add remaining main section if any
        if (mainSection.isNotEmpty()) {
            chunks.add(ContentChunk(
                chunkIndex = chunks.size,
                totalChunks = 0,
                content = mainSection.toString().trim(),
                contentSnippet = mainSection.toString().take(200)
            ))
        }

        return finalizeChunks(chunks)
    }

    /**
     * Chunk by markdown sections (for legal documents)
     * Looks for ## headers and splits there
     */
    fun chunkBySection(content: String, maxSize: Int = 2000, overlap: Int = 200): List<ContentChunk> {
        val sections = content.split(Regex("(?=^##\\s+)", RegexOption.MULTILINE))
        val chunks = mutableListOf<ContentChunk>()

        sections.forEach { section ->
            if (section.length <= maxSize) {
                // Section fits in one chunk
                chunks.add(createChunk(chunks.size, section, overlap))
            } else {
                // Section too large, split by paragraphs
                val subChunks = chunkByParagraph(section, maxSize, overlap)
                chunks.addAll(subChunks.mapIndexed { idx, chunk ->
                    chunk.copy(chunkIndex = chunks.size + idx)
                })
            }
        }

        return finalizeChunks(chunks)
    }

    /**
     * Chunk by paragraphs (for articles/RSS)
     * Splits on double newlines
     */
    fun chunkByParagraph(content: String, maxSize: Int = 1500, overlap: Int = 100): List<ContentChunk> {
        val paragraphs = content.split(Regex("\n\n+"))
        val chunks = mutableListOf<ContentChunk>()
        val currentChunk = StringBuilder()

        paragraphs.forEach { para ->
            val trimmed = para.trim()
            if (trimmed.isEmpty()) return@forEach

            // Check if adding this paragraph exceeds maxSize
            if (currentChunk.length + trimmed.length + 2 > maxSize && currentChunk.isNotEmpty()) {
                // Save current chunk
                chunks.add(createChunk(chunks.size, currentChunk.toString(), overlap))

                // Start new chunk with overlap
                val overlapText = getOverlapText(currentChunk.toString(), overlap)
                currentChunk.clear()
                currentChunk.append(overlapText)
                if (overlapText.isNotEmpty()) currentChunk.append("\n\n")
            }

            if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
            currentChunk.append(trimmed)
        }

        // Add final chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(createChunk(chunks.size, currentChunk.toString(), overlap))
        }

        return finalizeChunks(chunks)
    }

    /**
     * Chunk by markdown headings (for wiki content)
     * Preserves heading hierarchy
     */
    fun chunkByHeading(content: String, maxSize: Int = 2500, overlap: Int = 150): List<ContentChunk> {
        val headings = content.split(Regex("(?=^#+ )", RegexOption.MULTILINE))
        val chunks = mutableListOf<ContentChunk>()

        headings.forEach { section ->
            if (section.length <= maxSize) {
                chunks.add(createChunk(chunks.size, section, overlap))
            } else {
                // Too large, split by paragraphs
                val subChunks = chunkByParagraph(section, maxSize, overlap)
                chunks.addAll(subChunks.mapIndexed { idx, chunk ->
                    chunk.copy(chunkIndex = chunks.size + idx)
                })
            }
        }

        return finalizeChunks(chunks)
    }

    /**
     * Chunk by code blocks (for technical docs)
     * Keeps code blocks intact when possible
     */
    fun chunkByCodeBlock(content: String, maxSize: Int = 2000, overlap: Int = 200): List<ContentChunk> {
        val blocks = content.split(Regex("(```[\\s\\S]*?```)", RegexOption.MULTILINE))
        val chunks = mutableListOf<ContentChunk>()
        val currentChunk = StringBuilder()

        blocks.forEach { block ->
            val trimmed = block.trim()
            if (trimmed.isEmpty()) return@forEach

            // Check if this is a code block
            val isCodeBlock = trimmed.startsWith("```")

            if (currentChunk.length + trimmed.length > maxSize && currentChunk.isNotEmpty()) {
                // Save current chunk
                chunks.add(createChunk(chunks.size, currentChunk.toString(), overlap))

                // Start new chunk
                if (!isCodeBlock) {
                    val overlapText = getOverlapText(currentChunk.toString(), overlap)
                    currentChunk.clear()
                    currentChunk.append(overlapText)
                    if (overlapText.isNotEmpty()) currentChunk.append("\n\n")
                } else {
                    currentChunk.clear()
                }
            }

            if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
            currentChunk.append(trimmed)
        }

        // Add final chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(createChunk(chunks.size, currentChunk.toString(), overlap))
        }

        return finalizeChunks(chunks)
    }

    /**
     * Simple character-based chunking with overlap
     * Fallback for unknown content types
     */
    fun chunkByCharacterLimit(content: String, maxSize: Int = 1500, overlap: Int = 100): List<ContentChunk> {
        if (content.length <= maxSize) {
            return listOf(ContentChunk(
                chunkIndex = 0,
                totalChunks = 1,
                content = content,
                contentSnippet = content.take(200)
            ))
        }

        val chunks = mutableListOf<ContentChunk>()
        var start = 0

        while (start < content.length) {
            val end = minOf(start + maxSize, content.length)

            // Try to break at word boundary
            var actualEnd = end
            if (end < content.length) {
                val nextSpace = content.indexOf(' ', end)
                val prevSpace = content.lastIndexOf(' ', end)
                actualEnd = if (nextSpace - end < 50 && nextSpace != -1) nextSpace else prevSpace
                if (actualEnd <= start) actualEnd = end // Fallback if no good break point
            }

            val chunkContent = content.substring(start, actualEnd).trim()
            chunks.add(createChunk(chunks.size, chunkContent, overlap))

            start = actualEnd - overlap
            if (start < 0) start = actualEnd
        }

        return finalizeChunks(chunks)
    }

    /**
     * Get overlap text from end of content
     */
    private fun getOverlapText(content: String, overlapSize: Int): String {
        if (content.length <= overlapSize) return content

        // Try to break at sentence boundary
        val overlapStart = content.length - overlapSize
        val lastPeriod = content.lastIndexOf(". ", overlapStart)

        return if (lastPeriod > overlapStart) {
            content.substring(lastPeriod + 2).trim()
        } else {
            content.substring(overlapStart).trim()
        }
    }

    /**
     * Create chunk with snippet
     */
    private fun createChunk(index: Int, content: String, overlap: Int): ContentChunk {
        return ContentChunk(
            chunkIndex = index,
            totalChunks = 0, // Will be set later
            content = content.trim(),
            contentSnippet = content.trim().take(200)
        )
    }

    /**
     * Update totalChunks for all chunks
     */
    private fun finalizeChunks(chunks: List<ContentChunk>): List<ContentChunk> {
        val total = chunks.size
        return chunks.mapIndexed { idx, chunk ->
            chunk.copy(
                chunkIndex = idx,
                totalChunks = total
            )
        }
    }
}
