package org.datamancy.datafetcher.converters

/**
 * Utility functions for markdown processing that can be easily unit tested.
 */
object MarkdownUtils {

    /**
     * Cleans HTML text by removing excessive whitespace.
     */
    fun cleanWhitespace(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Escapes markdown special characters.
     */
    fun escapeMarkdown(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace(".", "\\.")
            .replace("!", "\\!")
    }

    /**
     * Converts HTML heading level to markdown.
     */
    fun htmlHeadingToMarkdown(level: Int, text: String): String {
        val hashes = "#".repeat(level.coerceIn(1, 6))
        return "$hashes ${cleanWhitespace(text)}"
    }

    /**
     * Creates a markdown link.
     */
    fun createMarkdownLink(text: String, url: String): String {
        return "[$text]($url)"
    }

    /**
     * Creates a markdown list item.
     */
    fun createListItem(text: String, ordered: Boolean = false, index: Int = 1): String {
        val prefix = if (ordered) "$index." else "-"
        return "$prefix ${cleanWhitespace(text)}"
    }

    /**
     * Extracts section number from heading text.
     * Examples: "Section 42: Title" -> "42", "42. Title" -> "42"
     */
    fun extractSectionNumber(text: String): String {
        val patterns = listOf(
            Regex("^Section\\s+(\\d+[A-Za-z]?)"),
            Regex("^(\\d+[A-Za-z]?)\\s*[.:]"),
            Regex("^§\\s*(\\d+[A-Za-z]?)")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return ""
    }

    /**
     * Creates a markdown code block.
     */
    fun createCodeBlock(code: String, language: String = ""): String {
        return "```$language\n$code\n```"
    }

    /**
     * Creates a markdown blockquote.
     */
    fun createBlockquote(text: String): String {
        return text.lines().joinToString("\n") { "> $it" }
    }

    /**
     * Normalizes markdown content by removing excessive blank lines.
     */
    fun normalizeMarkdown(markdown: String): String {
        return markdown
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Extracts plain text from markdown by removing formatting.
     */
    fun stripMarkdown(markdown: String): String {
        return markdown
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1") // Bold
            .replace(Regex("\\*(.+?)\\*"), "$1") // Italic
            .replace(Regex("__(.+?)__"), "$1") // Bold
            .replace(Regex("_(.+?)_"), "$1") // Italic
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1") // Links
            .replace(Regex("^#{1,6}\\s+"), "") // Headers
            .replace(Regex("^[-*+]\\s+"), "") // Unordered lists
            .replace(Regex("^\\d+\\.\\s+"), "") // Ordered lists
            .replace(Regex("^>\\s+"), "") // Blockquotes
            .replace(Regex("`(.+?)`"), "$1") // Inline code
            .replace(Regex("```[\\s\\S]*?```"), "") // Code blocks
    }

    /**
     * Counts words in text.
     */
    fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    /**
     * Truncates text to a maximum word count.
     */
    fun truncateToWords(text: String, maxWords: Int): String {
        val words = text.split(Regex("\\s+"))
        return if (words.size <= maxWords) {
            text
        } else {
            words.take(maxWords).joinToString(" ") + "..."
        }
    }
}
