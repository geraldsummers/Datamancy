package org.datamancy.datafetcher.converters

import org.junit.Test
import kotlin.test.assertEquals

class MarkdownUtilsTest {

    @Test
    fun `cleanWhitespace removes extra spaces`() {
        val result = MarkdownUtils.cleanWhitespace("This  has   extra    spaces")
        assertEquals("This has extra spaces", result)
    }

    @Test
    fun `cleanWhitespace removes leading and trailing whitespace`() {
        val result = MarkdownUtils.cleanWhitespace("  text  ")
        assertEquals("text", result)
    }

    @Test
    fun `cleanWhitespace handles newlines`() {
        val result = MarkdownUtils.cleanWhitespace("line1\n\nline2")
        assertEquals("line1 line2", result)
    }

    @Test
    fun `escapeMarkdown escapes asterisks`() {
        val result = MarkdownUtils.escapeMarkdown("bold *text*")
        assertEquals("bold \\*text\\*", result)
    }

    @Test
    fun `escapeMarkdown escapes underscores`() {
        val result = MarkdownUtils.escapeMarkdown("italic _text_")
        assertEquals("italic \\_text\\_", result)
    }

    @Test
    fun `escapeMarkdown escapes brackets`() {
        val result = MarkdownUtils.escapeMarkdown("[link](url)")
        assertEquals("\\[link\\]\\(url\\)", result)
    }

    @Test
    fun `escapeMarkdown escapes hash`() {
        val result = MarkdownUtils.escapeMarkdown("# heading")
        assertEquals("\\# heading", result)
    }

    @Test
    fun `htmlHeadingToMarkdown creates h1`() {
        val result = MarkdownUtils.htmlHeadingToMarkdown(1, "Title")
        assertEquals("# Title", result)
    }

    @Test
    fun `htmlHeadingToMarkdown creates h3`() {
        val result = MarkdownUtils.htmlHeadingToMarkdown(3, "Subsection")
        assertEquals("### Subsection", result)
    }

    @Test
    fun `htmlHeadingToMarkdown cleans whitespace`() {
        val result = MarkdownUtils.htmlHeadingToMarkdown(2, "  Title  with   spaces  ")
        assertEquals("## Title with spaces", result)
    }

    @Test
    fun `htmlHeadingToMarkdown clamps level to valid range`() {
        val result1 = MarkdownUtils.htmlHeadingToMarkdown(0, "Title")
        assertEquals("# Title", result1)

        val result2 = MarkdownUtils.htmlHeadingToMarkdown(10, "Title")
        assertEquals("###### Title", result2)
    }

    @Test
    fun `createMarkdownLink formats link correctly`() {
        val result = MarkdownUtils.createMarkdownLink("Google", "https://google.com")
        assertEquals("[Google](https://google.com)", result)
    }

    @Test
    fun `createListItem creates unordered item`() {
        val result = MarkdownUtils.createListItem("Item text")
        assertEquals("- Item text", result)
    }

    @Test
    fun `createListItem creates ordered item`() {
        val result = MarkdownUtils.createListItem("Item text", ordered = true, index = 3)
        assertEquals("3. Item text", result)
    }

    @Test
    fun `createListItem cleans whitespace`() {
        val result = MarkdownUtils.createListItem("  Item  with   spaces  ")
        assertEquals("- Item with spaces", result)
    }

    @Test
    fun `extractSectionNumber finds Section prefix`() {
        val result = MarkdownUtils.extractSectionNumber("Section 42: Title")
        assertEquals("42", result)
    }

    @Test
    fun `extractSectionNumber finds number with colon`() {
        val result = MarkdownUtils.extractSectionNumber("42: Title")
        assertEquals("42", result)
    }

    @Test
    fun `extractSectionNumber finds number with period`() {
        val result = MarkdownUtils.extractSectionNumber("42. Title")
        assertEquals("42", result)
    }

    @Test
    fun `extractSectionNumber finds section symbol`() {
        val result = MarkdownUtils.extractSectionNumber("§ 42 Title")
        assertEquals("42", result)
    }

    @Test
    fun `extractSectionNumber handles subsection letters`() {
        val result = MarkdownUtils.extractSectionNumber("Section 42A: Subsection")
        assertEquals("42A", result)
    }

    @Test
    fun `extractSectionNumber returns empty for no match`() {
        val result = MarkdownUtils.extractSectionNumber("Just a title")
        assertEquals("", result)
    }

    @Test
    fun `createCodeBlock creates block without language`() {
        val result = MarkdownUtils.createCodeBlock("code here")
        assertEquals("```\ncode here\n```", result)
    }

    @Test
    fun `createCodeBlock creates block with language`() {
        val result = MarkdownUtils.createCodeBlock("fun main() {}", "kotlin")
        assertEquals("```kotlin\nfun main() {}\n```", result)
    }

    @Test
    fun `createBlockquote prefixes single line`() {
        val result = MarkdownUtils.createBlockquote("Quote text")
        assertEquals("> Quote text", result)
    }

    @Test
    fun `createBlockquote prefixes multiple lines`() {
        val result = MarkdownUtils.createBlockquote("Line 1\nLine 2\nLine 3")
        assertEquals("> Line 1\n> Line 2\n> Line 3", result)
    }

    @Test
    fun `normalizeMarkdown removes excessive blank lines`() {
        val result = MarkdownUtils.normalizeMarkdown("Line 1\n\n\n\nLine 2")
        assertEquals("Line 1\n\nLine 2", result)
    }

    @Test
    fun `normalizeMarkdown trims whitespace`() {
        val result = MarkdownUtils.normalizeMarkdown("\n\nText\n\n")
        assertEquals("Text", result)
    }

    @Test
    fun `stripMarkdown removes bold`() {
        val result = MarkdownUtils.stripMarkdown("This is **bold** text")
        assertEquals("This is bold text", result)
    }

    @Test
    fun `stripMarkdown removes italic`() {
        val result = MarkdownUtils.stripMarkdown("This is *italic* text")
        assertEquals("This is italic text", result)
    }

    @Test
    fun `stripMarkdown removes links`() {
        val result = MarkdownUtils.stripMarkdown("[link text](https://example.com)")
        assertEquals("link text", result)
    }

    @Test
    fun `stripMarkdown removes inline code`() {
        val result = MarkdownUtils.stripMarkdown("Use `code` here")
        assertEquals("Use code here", result)
    }

    @Test
    fun `stripMarkdown removes code blocks`() {
        val result = MarkdownUtils.stripMarkdown("Text\n```kotlin\ncode\n```\nMore text")
        assertEquals("Text\n\nMore text", result)
    }

    @Test
    fun `countWords counts correctly`() {
        assertEquals(5, MarkdownUtils.countWords("This is a test string"))
        assertEquals(1, MarkdownUtils.countWords("Word"))
        assertEquals(0, MarkdownUtils.countWords(""))
        assertEquals(0, MarkdownUtils.countWords("   "))
    }

    @Test
    fun `countWords handles multiple spaces`() {
        assertEquals(3, MarkdownUtils.countWords("Word1   Word2    Word3"))
    }

    @Test
    fun `truncateToWords keeps text under limit`() {
        val text = "This is a short text"
        val result = MarkdownUtils.truncateToWords(text, 10)
        assertEquals(text, result)
    }

    @Test
    fun `truncateToWords truncates text over limit`() {
        val text = "This is a longer text that should be truncated"
        val result = MarkdownUtils.truncateToWords(text, 5)
        assertEquals("This is a longer text...", result)
    }

    @Test
    fun `truncateToWords handles exact limit`() {
        val text = "One two three four five"
        val result = MarkdownUtils.truncateToWords(text, 5)
        assertEquals(text, result)
    }
}
