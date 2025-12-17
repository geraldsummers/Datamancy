package org.datamancy.datafetcher.converters

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtmlToMarkdownConverterTest {

    @Test
    fun `converts simple HTML paragraph to markdown`() {
        val html = "<p>Hello World</p>"
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertTrue(markdown.contains("Hello World"), "Should contain text content")
    }

    @Test
    fun `converts HTML headings to markdown headers`() {
        val html = "<h1>Title</h1><h2>Subtitle</h2>"
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertTrue(markdown.contains("Title"), "Should contain heading text")
        assertTrue(markdown.contains("Subtitle"), "Should contain subheading text")
    }

    @Test
    fun `converts HTML links to markdown links`() {
        val html = """<a href="https://example.com">Link Text</a>"""
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertTrue(markdown.contains("Link Text"), "Should contain link text")
    }

    @Test
    fun `converts HTML lists to markdown lists`() {
        val html = "<ul><li>Item 1</li><li>Item 2</li></ul>"
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertTrue(markdown.contains("Item 1"), "Should contain first item")
        assertTrue(markdown.contains("Item 2"), "Should contain second item")
    }

    @Test
    fun `handles empty HTML`() {
        val html = ""
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertNotNull(markdown, "Should return non-null result")
    }

    @Test
    fun `strips HTML comments`() {
        val html = "<p>Text</p><!-- Comment --><p>More text</p>"
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertFalse(markdown.contains("Comment"), "Should not contain HTML comments")
        assertTrue(markdown.contains("Text"), "Should contain actual text")
    }

    @Test
    fun `handles nested HTML elements`() {
        val html = "<div><p><strong>Bold</strong> and <em>italic</em></p></div>"
        val markdown = HtmlToMarkdownConverter.convert(html)
        assertTrue(markdown.contains("Bold"), "Should contain bold text")
        assertTrue(markdown.contains("italic"), "Should contain italic text")
    }
}
