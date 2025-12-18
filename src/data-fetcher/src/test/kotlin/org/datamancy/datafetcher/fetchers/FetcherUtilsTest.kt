package org.datamancy.datafetcher.fetchers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetcherUtilsTest {

    @Test
    fun `createSafeItemId returns hash code as string`() {
        val result = FetcherUtils.createSafeItemId("https://example.com/article/123")
        assertNotNull(result)
        assertTrue(result.all { it.isDigit() || it == '-' })
    }

    @Test
    fun `createSafeItemId returns null for null input`() {
        val result = FetcherUtils.createSafeItemId(null)
        assertNull(result)
    }

    @Test
    fun `createSafeItemId returns consistent results`() {
        val input = "test-string"
        val result1 = FetcherUtils.createSafeItemId(input)
        val result2 = FetcherUtils.createSafeItemId(input)
        assertEquals(result1, result2)
    }

    @Test
    fun `detectInstrumentType identifies crypto symbols`() {
        assertEquals("crypto", FetcherUtils.detectInstrumentType("BTC"))
        assertEquals("crypto", FetcherUtils.detectInstrumentType("ETH"))
        assertEquals("crypto", FetcherUtils.detectInstrumentType("USDT"))
        assertEquals("crypto", FetcherUtils.detectInstrumentType("SOL"))
    }

    @Test
    fun `detectInstrumentType identifies stock symbols`() {
        assertEquals("stock", FetcherUtils.detectInstrumentType("AAPL-US"))
        assertEquals("stock", FetcherUtils.detectInstrumentType("TSLA.US"))
        assertEquals("stock", FetcherUtils.detectInstrumentType("BRK.B"))
    }

    @Test
    fun `detectInstrumentType returns unknown for ambiguous symbols`() {
        assertEquals("unknown", FetcherUtils.detectInstrumentType("bitcoin"))
        assertEquals("unknown", FetcherUtils.detectInstrumentType("SomeLongSymbol"))
        assertEquals("unknown", FetcherUtils.detectInstrumentType("abc123def"))
    }

    @Test
    fun `extractYear finds year in text`() {
        assertEquals("2024", FetcherUtils.extractYear("Test Act 2024"))
        assertEquals("2020", FetcherUtils.extractYear("Copyright 2020-2024"))
        assertEquals("1999", FetcherUtils.extractYear("Y2K happened in 1999"))
    }

    @Test
    fun `extractYear returns null when no year found`() {
        assertNull(FetcherUtils.extractYear("No year here"))
        assertNull(FetcherUtils.extractYear("Year 999"))
        assertNull(FetcherUtils.extractYear("Year 3000"))
    }

    @Test
    fun `extractIdentifier finds federal pattern`() {
        val result = FetcherUtils.extractIdentifier("Bill C2004A00467 was passed")
        assertEquals("C2004A00467", result)
    }

    @Test
    fun `extractIdentifier finds state pattern`() {
        val result = FetcherUtils.extractIdentifier("Act 2020-123 summary")
        assertEquals("Act 2020-123", result)
    }

    @Test
    fun `extractIdentifier finds No pattern`() {
        val result = FetcherUtils.extractIdentifier("No. 2021/456 regulation")
        assertEquals("No. 2021/456", result)
    }

    @Test
    fun `extractIdentifier returns null when no pattern found`() {
        assertNull(FetcherUtils.extractIdentifier("No identifier here"))
    }

    @Test
    fun `extractStatus finds In force`() {
        assertEquals("In force", FetcherUtils.extractStatus("Status: In force"))
        assertEquals("In force", FetcherUtils.extractStatus("Currently in FORCE"))
    }

    @Test
    fun `extractStatus finds Repealed`() {
        assertEquals("Repealed", FetcherUtils.extractStatus("This act was repealed"))
    }

    @Test
    fun `extractStatus finds Amended`() {
        assertEquals("Amended", FetcherUtils.extractStatus("Last amended 2020"))
    }

    @Test
    fun `extractStatus returns null when no status found`() {
        assertNull(FetcherUtils.extractStatus("No status information"))
    }

    @Test
    fun `extractDate finds slash format`() {
        assertEquals("12/25/2024", FetcherUtils.extractDate("Date: 12/25/2024"))
        assertEquals("1/5/2020", FetcherUtils.extractDate("Effective 1/5/2020"))
    }

    @Test
    fun `extractDate finds ISO format`() {
        assertEquals("2024-12-25", FetcherUtils.extractDate("Published 2024-12-25"))
    }

    @Test
    fun `extractDate finds month name format`() {
        val result = FetcherUtils.extractDate("Date: 15 December 2024")
        assertNotNull(result)
        assertTrue(result.contains("December") || result.contains("Dec"))
    }

    @Test
    fun `extractDate returns null when no date found`() {
        assertNull(FetcherUtils.extractDate("No date here"))
    }

    @Test
    fun `sanitizeForFilesystem removes special characters`() {
        val result = FetcherUtils.sanitizeForFilesystem("Test: File/Name? (2024)")
        assertEquals("Test_FileName_2024", result)
    }

    @Test
    fun `sanitizeForFilesystem respects max length`() {
        val longText = "This is a very long filename that should be truncated"
        val result = FetcherUtils.sanitizeForFilesystem(longText, 20)
        assertEquals(20, result.length)
    }

    @Test
    fun `sanitizeForFilesystem preserves hyphens`() {
        val result = FetcherUtils.sanitizeForFilesystem("test-file-name")
        assertEquals("test-file-name", result)
    }

    @Test
    fun `encodeQuery encodes spaces`() {
        val result = FetcherUtils.encodeQuery("test query")
        assertTrue(result.contains("+") || result.contains("%20"))
    }

    @Test
    fun `encodeQuery encodes special characters`() {
        val result = FetcherUtils.encodeQuery("test&query=value")
        assertTrue(result.contains("%26") || result.contains("%3D"))
    }

    @Test
    fun `createSnapshotId generates consistent IDs`() {
        val query = "test query"
        val timestamp = "2024-12-18T10:30:00Z"

        val result1 = FetcherUtils.createSnapshotId(query, timestamp)
        val result2 = FetcherUtils.createSnapshotId(query, timestamp)

        assertEquals(result1, result2)
    }

    @Test
    fun `createSnapshotId includes query hash`() {
        val query = "test query"
        val timestamp = "2024-12-18T10:30:00Z"

        val result = FetcherUtils.createSnapshotId(query, timestamp)
        assertTrue(result.contains("_"))
        assertTrue(result.split("_")[0].all { it.isDigit() || it == '-' })
    }

    @Test
    fun `mapSymbolToCoinGeckoId maps common symbols`() {
        assertEquals("bitcoin", FetcherUtils.mapSymbolToCoinGeckoId("BTC"))
        assertEquals("ethereum", FetcherUtils.mapSymbolToCoinGeckoId("ETH"))
        assertEquals("tether", FetcherUtils.mapSymbolToCoinGeckoId("USDT"))
        assertEquals("binancecoin", FetcherUtils.mapSymbolToCoinGeckoId("BNB"))
        assertEquals("solana", FetcherUtils.mapSymbolToCoinGeckoId("SOL"))
        assertEquals("ripple", FetcherUtils.mapSymbolToCoinGeckoId("XRP"))
        assertEquals("usd-coin", FetcherUtils.mapSymbolToCoinGeckoId("USDC"))
        assertEquals("cardano", FetcherUtils.mapSymbolToCoinGeckoId("ADA"))
        assertEquals("avalanche-2", FetcherUtils.mapSymbolToCoinGeckoId("AVAX"))
        assertEquals("dogecoin", FetcherUtils.mapSymbolToCoinGeckoId("DOGE"))
    }

    @Test
    fun `mapSymbolToCoinGeckoId lowercases unknown symbols`() {
        assertEquals("unknown", FetcherUtils.mapSymbolToCoinGeckoId("UNKNOWN"))
        assertEquals("xyz", FetcherUtils.mapSymbolToCoinGeckoId("XYZ"))
    }

    @Test
    fun `isValidResponseBody returns true for valid body`() {
        assertTrue(FetcherUtils.isValidResponseBody("valid response"))
        assertTrue(FetcherUtils.isValidResponseBody("{\"data\": \"value\"}"))
    }

    @Test
    fun `isValidResponseBody returns false for null`() {
        assertEquals(false, FetcherUtils.isValidResponseBody(null))
    }

    @Test
    fun `isValidResponseBody returns false for empty string`() {
        assertEquals(false, FetcherUtils.isValidResponseBody(""))
        assertEquals(false, FetcherUtils.isValidResponseBody("   "))
    }

    @Test
    fun `isValidResponseBody returns false for empty JSON object`() {
        assertEquals(false, FetcherUtils.isValidResponseBody("{}"))
    }

    @Test
    fun `computeSetDiff identifies added items`() {
        val previous = setOf("a", "b", "c")
        val current = setOf("b", "c", "d", "e")

        val (added, removed, unchanged) = FetcherUtils.computeSetDiff(previous, current)

        assertEquals(setOf("d", "e"), added)
        assertEquals(setOf("a"), removed)
        assertEquals(setOf("b", "c"), unchanged)
    }

    @Test
    fun `computeSetDiff handles empty sets`() {
        val previous = emptySet<String>()
        val current = setOf("a", "b")

        val (added, removed, unchanged) = FetcherUtils.computeSetDiff(previous, current)

        assertEquals(setOf("a", "b"), added)
        assertEquals(emptySet(), removed)
        assertEquals(emptySet(), unchanged)
    }

    @Test
    fun `computeSetDiff handles identical sets`() {
        val previous = setOf("a", "b", "c")
        val current = setOf("a", "b", "c")

        val (added, removed, unchanged) = FetcherUtils.computeSetDiff(previous, current)

        assertEquals(emptySet(), added)
        assertEquals(emptySet(), removed)
        assertEquals(setOf("a", "b", "c"), unchanged)
    }
}
