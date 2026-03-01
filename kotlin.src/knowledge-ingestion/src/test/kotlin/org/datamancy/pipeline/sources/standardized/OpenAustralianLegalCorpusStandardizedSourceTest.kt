package org.datamancy.pipeline.sources.standardized

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.scheduling.RunType
import org.datamancy.pipeline.sources.AustralianLegalDocument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAustralianLegalCorpusStandardizedSourceTest {

    @Test
    fun `test source name is australian_laws`() {
        val source = OpenAustralianLegalCorpusStandardizedSource()
        assertEquals("australian_laws", source.name)
    }

    @Test
    fun `test resync strategy is Monthly`() {
        val source = OpenAustralianLegalCorpusStandardizedSource()
        val strategy = source.resyncStrategy()

        assertNotNull(strategy)
        assertTrue(strategy.describe().contains("Monthly") || strategy.describe().contains("month"))
    }

    @Test
    fun `test backfill strategy is LegalDatabase`() {
        val source = OpenAustralianLegalCorpusStandardizedSource()
        val strategy = source.backfillStrategy()

        assertNotNull(strategy)
        assertEquals("LegalDatabase", strategy.describe())
    }

    @Test
    fun `test chunking is enabled`() {
        val source = OpenAustralianLegalCorpusStandardizedSource()
        assertTrue(source.needsChunking())
    }

    @Test
    fun `test chunker configuration`() {
        val source = OpenAustralianLegalCorpusStandardizedSource()
        val chunker = source.chunker()

        assertNotNull(chunker)
        
    }

    @Test
    fun `test AustralianLegalDocumentChunkable wraps document correctly`() {
        val doc = AustralianLegalDocument(
            id = "test-act-001",
            type = "primary_legislation",
            jurisdiction = "commonwealth",
            source = "legislation.gov.au",
            mime = "text/html",
            date = "2024-01-01",
            citation = "Act No. 1 of 2024",
            url = "https://example.com/act",
            whenScraped = "2024-01-15",
            text = "This is the full text of the act."
        )

        val chunkable = AustralianLegalDocumentChunkable(doc)

        assertEquals("test-act-001", chunkable.getId())
        assertTrue(chunkable.toText().contains("primary_legislation"))

        val metadata = chunkable.getMetadata()
        assertEquals("primary_legislation", metadata["type"])
        assertEquals("commonwealth", metadata["jurisdiction"])
        assertEquals("2024-01-01", metadata["date"])
        assertEquals("Act No. 1 of 2024", metadata["citation"])
        assertEquals("legislation.gov.au", metadata["source"])
        assertEquals("https://example.com/act", metadata["url"])
    }

    @Test
    fun `test AustralianLegalDocumentChunkable metadata includes all fields`() {
        val doc = AustralianLegalDocument(
            id = "doc-123",
            type = "secondary_legislation",
            jurisdiction = "new_south_wales",
            source = "legislation.nsw.gov.au",
            mime = "application/pdf",
            date = "2023-06-15",
            citation = "Regulation 5 of 2023",
            url = "https://example.com/regulation",
            whenScraped = "2023-07-01",
            text = "Regulation text content."
        )

        val chunkable = AustralianLegalDocumentChunkable(doc)
        val metadata = chunkable.getMetadata()

        
        assertTrue(metadata.containsKey("type"))
        assertTrue(metadata.containsKey("jurisdiction"))
        assertTrue(metadata.containsKey("date"))
        assertTrue(metadata.containsKey("citation"))
        assertTrue(metadata.containsKey("source"))
        assertTrue(metadata.containsKey("url"))

        assertEquals(6, metadata.size)
    }

    @Test
    fun `test fetchForRun with INITIAL_PULL metadata`() = runBlocking {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            cacheDir = "/tmp/test-corpus-initial",
            maxDocuments = 0  
        )

        val metadata = RunMetadata(
            runType = RunType.INITIAL_PULL,
            attemptNumber = 1,
            isFirstRun = true
        )

        
        try {
            val flow = source.fetchForRun(metadata)
            assertNotNull(flow)
        } catch (e: Exception) {
            
            assertTrue(e.message?.contains("Failed to download") == true ||
                      e.message?.contains("Connection") == true ||
                      e is java.net.UnknownHostException ||
                      e is java.net.ConnectException)
        }
    }

    @Test
    fun `test fetchForRun with RESYNC metadata`() = runBlocking {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            cacheDir = "/tmp/test-corpus-resync",
            maxDocuments = 0
        )

        val metadata = RunMetadata(
            runType = RunType.RESYNC,
            attemptNumber = 1,
            isFirstRun = false
        )

        
        try {
            val flow = source.fetchForRun(metadata)
            assertNotNull(flow)
        } catch (e: Exception) {
            
            assertTrue(e.message?.contains("Failed to download") == true ||
                      e.message?.contains("Connection") == true ||
                      e is java.net.UnknownHostException ||
                      e is java.net.ConnectException)
        }
    }

    @Test
    fun `test source accepts jurisdiction filter`() {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            jurisdictions = listOf("commonwealth", "new_south_wales")
        )

        
        assertEquals("australian_laws", source.name)
    }

    @Test
    fun `test source accepts document type filter`() {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            documentTypes = listOf("primary_legislation")
        )

        
        assertEquals("australian_laws", source.name)
    }

    @Test
    fun `test source accepts maxDocuments limit`() {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            maxDocuments = 1000
        )

        
        assertEquals("australian_laws", source.name)
    }

    @Test
    fun `test source with all filters combined`() {
        val source = OpenAustralianLegalCorpusStandardizedSource(
            cacheDir = "/tmp/test-filtered",
            jurisdictions = listOf("commonwealth", "queensland"),
            documentTypes = listOf("primary_legislation", "secondary_legislation"),
            maxDocuments = 5000
        )

        assertEquals("australian_laws", source.name)
        assertTrue(source.needsChunking())
    }
}
