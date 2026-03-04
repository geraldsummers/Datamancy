package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType
import org.apache.parquet.schema.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path as JavaPath
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OpenAustralianLegalCorpusSourceTest {

    @Test
    fun `test AustralianLegalDocument toText formatting`() {
        val doc = AustralianLegalDocument(
            id = "test-act-2024",
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

        val text = doc.toText()

        assertTrue(text.contains("# primary_legislation"))
        assertTrue(text.contains("**Citation:** Act No. 1 of 2024"))
        assertTrue(text.contains("**Jurisdiction:** Commonwealth"))
        assertTrue(text.contains("**Date:** 2024-01-01"))
        assertTrue(text.contains("**Source:** legislation.gov.au"))
        assertTrue(text.contains("**URL:** https://example.com/act"))
        assertTrue(text.contains("## Full Text"))
        assertTrue(text.contains("This is the full text of the act."))
    }

    @Test
    fun `test AustralianLegalDocument toText handles empty citation`() {
        val doc = AustralianLegalDocument(
            id = "test-doc",
            type = "decision",
            jurisdiction = "new_south_wales",
            source = "caselaw.nsw.gov.au",
            mime = "text/html",
            date = "2023-06-15",
            citation = "",  
            url = "https://example.com/decision",
            whenScraped = "2023-07-01",
            text = "Court decision text."
        )

        val text = doc.toText()

        assertFalse(text.contains("**Citation:**"))
        assertTrue(text.contains("**Jurisdiction:** New south wales"))
    }

    @Test
    fun `test AustralianLegalDocument contentHash uses id`() {
        val doc1 = AustralianLegalDocument(
            id = "act-123",
            type = "primary_legislation",
            jurisdiction = "commonwealth",
            source = "source1",
            mime = "text/html",
            date = "2024-01-01",
            citation = "Citation 1",
            url = "url1",
            whenScraped = "2024-01-15",
            text = "Text 1"
        )

        val doc2 = AustralianLegalDocument(
            id = "act-123",  
            type = "secondary_legislation",  
            jurisdiction = "new_south_wales",  
            source = "source2",
            mime = "application/pdf",
            date = "2023-01-01",
            citation = "Citation 2",
            url = "url2",
            whenScraped = "2023-01-15",
            text = "Text 2"
        )

        
        assertEquals(doc1.contentHash(), doc2.contentHash())
    }

    @Test
    fun `test OpenAustralianLegalCorpusSource parses Parquet file`(@TempDir tempDir: JavaPath) = runBlocking {
        
        val parquetFile = tempDir.resolve("test-corpus.parquet").toFile()
        createTestParquetFile(parquetFile.absolutePath, listOf(
            mapOf(
                "version_id" to "act-2024-001",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "legislation.gov.au",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Act No. 1 of 2024",
                "url" to "https://example.com/act1",
                "when_scraped" to "2024-01-15",
                "text" to "This is the full text of act 1. It contains enough text to pass the minimum length filter of 100 characters required by the source."
            ),
            mapOf(
                "version_id" to "act-2024-002",
                "type" to "secondary_legislation",
                "jurisdiction" to "new_south_wales",
                "source" to "legislation.nsw.gov.au",
                "mime" to "text/html",
                "date" to "2024-02-01",
                "citation" to "Regulation 2 of 2024",
                "url" to "https://example.com/reg2",
                "when_scraped" to "2024-02-15",
                "text" to "This is the full text of regulation 2. It also contains enough text to pass the minimum length filter of 100 characters that is required."
            )
        ))

        
        
        val cacheDir = tempDir.resolve("cache").toFile()
        cacheDir.mkdirs()
        val cachedFile = cacheDir.resolve("0000.parquet")
        parquetFile.copyTo(cachedFile, overwrite = true)

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir.absolutePath,
            filterJurisdictions = null,
            filterTypes = null,
            maxDocuments = 10
        )

        val documents = source.fetch().toList()

        assertEquals(2, documents.size)

        assertEquals("act-2024-001", documents[0].id)
        assertEquals("primary_legislation", documents[0].type)
        assertEquals("commonwealth", documents[0].jurisdiction)
        assertEquals("Act No. 1 of 2024", documents[0].citation)

        assertEquals("act-2024-002", documents[1].id)
        assertEquals("secondary_legislation", documents[1].type)
        assertEquals("new_south_wales", documents[1].jurisdiction)
    }

    @Test
    fun `test OpenAustralianLegalCorpusSource filters by jurisdiction`(@TempDir tempDir: JavaPath) = runBlocking {
        val parquetFile = tempDir.resolve("test-corpus.parquet").toFile()
        createTestParquetFile(parquetFile.absolutePath, listOf(
            mapOf(
                "version_id" to "act-001",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 1",
                "url" to "url1",
                "when_scraped" to "2024-01-15",
                "text" to "Text for commonwealth act with sufficient length to pass the minimum filter of 100 characters required by the OpenAustralianLegalCorpusSource."
            ),
            mapOf(
                "version_id" to "act-002",
                "type" to "primary_legislation",
                "jurisdiction" to "new_south_wales",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 2",
                "url" to "url2",
                "when_scraped" to "2024-01-15",
                "text" to "Text for NSW act with sufficient length to pass the minimum filter requirement of at least 100 characters in the document text."
            ),
            mapOf(
                "version_id" to "act-003",
                "type" to "primary_legislation",
                "jurisdiction" to "queensland",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 3",
                "url" to "url3",
                "when_scraped" to "2024-01-15",
                "text" to "Text for Queensland act with sufficient length to pass minimum requirements of 100 characters needed by the source filter logic."
            )
        ))

        val cacheDir = tempDir.resolve("cache").toFile()
        cacheDir.mkdirs()
        parquetFile.copyTo(cacheDir.resolve("0000.parquet"), overwrite = true)

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir.absolutePath,
            filterJurisdictions = listOf("commonwealth", "queensland"),
            filterTypes = null,
            maxDocuments = 10
        )

        val documents = source.fetch().toList()

        assertEquals(2, documents.size)
        assertTrue(documents.any { it.jurisdiction == "commonwealth" })
        assertTrue(documents.any { it.jurisdiction == "queensland" })
        assertFalse(documents.any { it.jurisdiction == "new_south_wales" })
    }

    @Test
    fun `test OpenAustralianLegalCorpusSource filters by type`(@TempDir tempDir: JavaPath) = runBlocking {
        val parquetFile = tempDir.resolve("test-corpus.parquet").toFile()
        createTestParquetFile(parquetFile.absolutePath, listOf(
            mapOf(
                "version_id" to "doc-001",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 1",
                "url" to "url1",
                "when_scraped" to "2024-01-15",
                "text" to "Primary legislation text with sufficient length to pass the minimum filter of 100 characters required by the OpenAustralianLegalCorpusSource filter."
            ),
            mapOf(
                "version_id" to "doc-002",
                "type" to "secondary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 2",
                "url" to "url2",
                "when_scraped" to "2024-01-15",
                "text" to "Secondary legislation text with sufficient length to pass minimum requirements of at least 100 characters for the filter to accept it."
            ),
            mapOf(
                "version_id" to "doc-003",
                "type" to "decision",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 3",
                "url" to "url3",
                "when_scraped" to "2024-01-15",
                "text" to "Court decision text with sufficient length to pass the minimum filter requirement of 100 characters needed by the source implementation."
            )
        ))

        val cacheDir = tempDir.resolve("cache").toFile()
        cacheDir.mkdirs()
        parquetFile.copyTo(cacheDir.resolve("0000.parquet"), overwrite = true)

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir.absolutePath,
            filterJurisdictions = null,
            filterTypes = listOf("primary_legislation"),
            maxDocuments = 10
        )

        val documents = source.fetch().toList()

        assertEquals(1, documents.size)
        assertEquals("primary_legislation", documents[0].type)
    }

    @Test
    fun `test OpenAustralianLegalCorpusSource filters short documents`(@TempDir tempDir: JavaPath) = runBlocking {
        val parquetFile = tempDir.resolve("test-corpus.parquet").toFile()
        createTestParquetFile(parquetFile.absolutePath, listOf(
            mapOf(
                "version_id" to "doc-001",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 1",
                "url" to "url1",
                "when_scraped" to "2024-01-15",
                "text" to "Short"  
            ),
            mapOf(
                "version_id" to "doc-002",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation 2",
                "url" to "url2",
                "when_scraped" to "2024-01-15",
                "text" to "This document has sufficient text length to pass the minimum character requirement filter which is 100 characters long."
            )
        ))

        val cacheDir = tempDir.resolve("cache").toFile()
        cacheDir.mkdirs()
        parquetFile.copyTo(cacheDir.resolve("0000.parquet"), overwrite = true)

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir.absolutePath,
            filterJurisdictions = null,
            filterTypes = null,
            maxDocuments = 10
        )

        val documents = source.fetch().toList()

        assertEquals(1, documents.size)
        assertEquals("doc-002", documents[0].id)
    }

    @Test
    fun `test OpenAustralianLegalCorpusSource respects maxDocuments limit`(@TempDir tempDir: JavaPath) = runBlocking {
        val parquetFile = tempDir.resolve("test-corpus.parquet").toFile()
        val records = (1..50).map { i ->
            mapOf(
                "version_id" to "doc-$i",
                "type" to "primary_legislation",
                "jurisdiction" to "commonwealth",
                "source" to "source",
                "mime" to "text/html",
                "date" to "2024-01-01",
                "citation" to "Citation $i",
                "url" to "url$i",
                "when_scraped" to "2024-01-15",
                "text" to "Document $i text with sufficient length to pass the minimum character requirement filter which is 100 characters."
            )
        }
        createTestParquetFile(parquetFile.absolutePath, records)

        val cacheDir = tempDir.resolve("cache").toFile()
        cacheDir.mkdirs()
        parquetFile.copyTo(cacheDir.resolve("0000.parquet"), overwrite = true)

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir.absolutePath,
            filterJurisdictions = null,
            filterTypes = null,
            maxDocuments = 10
        )

        val documents = source.fetch().toList()

        assertEquals(10, documents.size)
    }

    
    private fun createTestParquetFile(path: String, records: List<Map<String, String>>) {
        val schema: MessageType = Types.buildMessage()
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("version_id")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("type")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("jurisdiction")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("source")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("mime")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("date")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("citation")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("url")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("when_scraped")
            .required(PrimitiveType.PrimitiveTypeName.BINARY).named("text")
            .named("legal_document")

        val conf = Configuration()
        conf.set(GroupWriteSupport.PARQUET_EXAMPLE_SCHEMA, schema.toString())

        val hadoopPath = Path("file://$path")
        val writer: ParquetWriter<Group> = ExampleParquetWriter.builder(hadoopPath)
            .withConf(conf)
            .build()

        val factory = SimpleGroupFactory(schema)

        records.forEach { record ->
            val group = factory.newGroup()
            group.append("version_id", record["version_id"]!!)
            group.append("type", record["type"]!!)
            group.append("jurisdiction", record["jurisdiction"]!!)
            group.append("source", record["source"]!!)
            group.append("mime", record["mime"]!!)
            group.append("date", record["date"]!!)
            group.append("citation", record["citation"]!!)
            group.append("url", record["url"]!!)
            group.append("when_scraped", record["when_scraped"]!!)
            group.append("text", record["text"]!!)
            writer.write(group)
        }

        writer.close()
    }
}
