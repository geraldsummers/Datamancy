package org.datamancy.datafetcher.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigValidatorsTest {

    @Test
    fun `validateRssFeed accepts valid feed`() {
        val feed = RssFeed("https://example.com/feed.xml", "tech")
        val result = ConfigValidators.validateRssFeed(feed)
        assertTrue(result.isValid())
    }

    @Test
    fun `validateRssFeed rejects blank URL`() {
        val feed = RssFeed("", "tech")
        val result = ConfigValidators.validateRssFeed(feed)
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("URL cannot be blank") })
    }

    @Test
    fun `validateRssFeed rejects non-http URL`() {
        val feed = RssFeed("ftp://example.com/feed.xml", "tech")
        val result = ConfigValidators.validateRssFeed(feed)
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("must start with http") })
    }

    @Test
    fun `validateRssFeed rejects blank category`() {
        val feed = RssFeed("https://example.com/feed.xml", "")
        val result = ConfigValidators.validateRssFeed(feed)
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("category cannot be blank") })
    }

    @Test
    fun `validateRssFeed accumulates multiple errors`() {
        val feed = RssFeed("", "")
        val result = ConfigValidators.validateRssFeed(feed)
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().size >= 2)
    }

    @Test
    fun `validateApiKey accepts valid key`() {
        val result = ConfigValidators.validateApiKey("valid-api-key-12345")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateApiKey rejects blank key`() {
        val result = ConfigValidators.validateApiKey("")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("cannot be blank") })
    }

    @Test
    fun `validateApiKey rejects short key`() {
        val result = ConfigValidators.validateApiKey("abc123")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("too short") })
    }

    @Test
    fun `validateApiKey rejects key with spaces`() {
        val result = ConfigValidators.validateApiKey("key with spaces")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("should not contain spaces") })
    }

    @Test
    fun `validateApiKey uses custom key name`() {
        val result = ConfigValidators.validateApiKey("", "OpenAI Key")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("OpenAI Key") })
    }

    @Test
    fun `validateSymbol accepts valid crypto symbol`() {
        val result = ConfigValidators.validateSymbol("BTC")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateSymbol accepts valid stock symbol`() {
        val result = ConfigValidators.validateSymbol("AAPL")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateSymbol accepts symbol with hyphen`() {
        val result = ConfigValidators.validateSymbol("BRK-B")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateSymbol accepts symbol with dot`() {
        val result = ConfigValidators.validateSymbol("BRK.B")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateSymbol rejects blank symbol`() {
        val result = ConfigValidators.validateSymbol("")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("cannot be blank") })
    }

    @Test
    fun `validateSymbol rejects too long symbol`() {
        val result = ConfigValidators.validateSymbol("VERYLONGSYMBOL")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("too long") })
    }

    @Test
    fun `validateSymbol rejects invalid characters`() {
        val result = ConfigValidators.validateSymbol("BTC@USD")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("invalid characters") })
    }

    @Test
    fun `validateSearchQuery accepts valid query`() {
        val result = ConfigValidators.validateSearchQuery("machine learning")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateSearchQuery rejects blank query`() {
        val result = ConfigValidators.validateSearchQuery("")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("cannot be blank") })
    }

    @Test
    fun `validateSearchQuery rejects too short query`() {
        val result = ConfigValidators.validateSearchQuery("a")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("too short") })
    }

    @Test
    fun `validateSearchQuery rejects too long query`() {
        val longQuery = "a".repeat(501)
        val result = ConfigValidators.validateSearchQuery(longQuery)
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("too long") })
    }

    @Test
    fun `validateUrl accepts valid http URL`() {
        val result = ConfigValidators.validateUrl("http://example.com")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateUrl accepts valid https URL`() {
        val result = ConfigValidators.validateUrl("https://example.com/path")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateUrl rejects blank URL`() {
        val result = ConfigValidators.validateUrl("")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("cannot be blank") })
    }

    @Test
    fun `validateUrl rejects non-http protocol`() {
        val result = ConfigValidators.validateUrl("ftp://example.com")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("must start with http") })
    }

    @Test
    fun `validateUrl rejects malformed URL`() {
        val result = ConfigValidators.validateUrl("https://not a valid url")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("format is invalid") })
    }

    @Test
    fun `validateScheduleInterval accepts seconds`() {
        val result = ConfigValidators.validateScheduleInterval("30s")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateScheduleInterval accepts minutes`() {
        val result = ConfigValidators.validateScheduleInterval("5m")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateScheduleInterval accepts hours`() {
        val result = ConfigValidators.validateScheduleInterval("2h")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateScheduleInterval accepts days`() {
        val result = ConfigValidators.validateScheduleInterval("1d")
        assertTrue(result.isValid())
    }

    @Test
    fun `validateScheduleInterval rejects blank interval`() {
        val result = ConfigValidators.validateScheduleInterval("")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("cannot be blank") })
    }

    @Test
    fun `validateScheduleInterval rejects invalid format`() {
        val result = ConfigValidators.validateScheduleInterval("5")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("must be in format") })
    }

    @Test
    fun `validateScheduleInterval rejects invalid unit`() {
        val result = ConfigValidators.validateScheduleInterval("5x")
        assertTrue(result.isInvalid())
        assertTrue(result.getErrors().any { it.contains("must be in format") })
    }

    @Test
    fun `ValidationResult Valid returns empty errors`() {
        val result = ValidationResult.Valid
        assertEquals(emptyList(), result.getErrors())
    }

    @Test
    fun `ValidationResult Invalid returns errors list`() {
        val errors = listOf("Error 1", "Error 2")
        val result = ValidationResult.Invalid(errors)
        assertEquals(errors, result.getErrors())
        assertEquals(errors, result.errorMessages)
    }
}
