package org.datamancy.pipeline.integration

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.sources.LinuxDocsSource
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class LinuxDocsDirectTest {
    @Test
    fun `directly test Linux docs reading real man pages`() = runBlocking {
        val manPagesExist = File("/usr/share/man").exists()
        
        println("\n🐧 Direct Linux Docs Test")
        println("Man pages exist: $manPagesExist")
        
        if (manPagesExist) {
            val source = LinuxDocsSource(
                sources = listOf(LinuxDocsSource.DocSource.MAN_PAGES),
                maxDocs = 3
            )
            
            val docs = source.fetch().toList()
            
            println("Fetched ${docs.size} docs")
            docs.forEach { doc ->
                println("  - ${doc.title}: ${doc.content.length} chars")
            }
            
            assertTrue(docs.isNotEmpty(), "Should fetch at least one doc")
        } else {
            println("Skipping test - no man pages on system")
        }
    }
}
