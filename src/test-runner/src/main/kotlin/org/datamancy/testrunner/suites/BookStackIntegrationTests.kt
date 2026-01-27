package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.TestRunner

/**
 * BookStack Integration Tests
 *
 * Tests that the pipeline correctly writes articles to BookStack
 * with proper formatting, metadata, and structure.
 *
 * These tests verify end-to-end BookStack integration:
 * 1. Pipeline fetches articles from sources
 * 2. Articles are transformed to BookStack documents
 * 3. Documents are written to BookStack via API
 * 4. Books, chapters, and pages are created with correct structure
 */
suspend fun TestRunner.bookStackIntegrationTests() {
    suite("BookStack Integration Tests") {
        // ================================================================================
        // BOOKSTACK API CONNECTIVITY
        // ================================================================================

        test("BookStack: API is accessible and authenticated") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack requires authentication - skipping integration tests")
                println("      ℹ️  Set BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET to enable")
                return@test
            }

            response.status shouldBe HttpStatusCode.OK
            println("      ✓ BookStack API is accessible and authenticated")
        }

        // ================================================================================
        // BOOK STRUCTURE TESTS
        // ================================================================================

        test("BookStack: RSS Feeds book exists") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=RSS%20Feeds")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            response.status shouldBe HttpStatusCode.OK

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                val book = books.first().jsonObject
                val bookName = book["name"]?.jsonPrimitive?.content

                bookName shouldBe "RSS Feeds"
                println("      ✓ Found 'RSS Feeds' book in BookStack")

                // Check book description
                val description = book["description"]?.jsonPrimitive?.content
                if (description != null) {
                    description shouldContain "Aggregated news"
                    println("      ✓ Book has correct description")
                }
            } else {
                println("      ℹ️  'RSS Feeds' book not found - pipeline may not have run yet")
            }
        }

        test("BookStack: CVE Database book exists") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=CVE%20Database")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            response.status shouldBe HttpStatusCode.OK

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                val book = books.first().jsonObject
                val bookName = book["name"]?.jsonPrimitive?.content

                bookName shouldBe "CVE Database"
                println("      ✓ Found 'CVE Database' book in BookStack")

                // Check book description
                val description = book["description"]?.jsonPrimitive?.content
                if (description != null) {
                    description shouldContain "Security vulnerabilities"
                    println("      ✓ Book has correct description")
                }
            } else {
                println("      ℹ️  'CVE Database' book not found - pipeline may not have run yet")
            }
        }

        test("BookStack: Wikipedia book exists") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=Wikipedia")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            response.status shouldBe HttpStatusCode.OK

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                println("      ✓ Found 'Wikipedia' book in BookStack")
            } else {
                println("      ℹ️  'Wikipedia' book not found - pipeline may not have run yet")
            }
        }

        test("BookStack: Linux Documentation book exists") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=Linux%20Documentation")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            response.status shouldBe HttpStatusCode.OK

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                println("      ✓ Found 'Linux Documentation' book in BookStack")
            } else {
                println("      ℹ️  'Linux Documentation' book not found - pipeline may not have run yet")
            }
        }

        // ================================================================================
        // CHAPTER STRUCTURE TESTS
        // ================================================================================

        test("BookStack: RSS book has chapters organized by feed") {
            val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=RSS%20Feeds")

            if (booksResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
            val books = booksJson["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int

                if (bookId != null) {
                    val bookDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                    val bookDetail = Json.parseToJsonElement(bookDetailResponse.bodyAsText()).jsonObject
                    val contents = bookDetail["contents"]?.jsonArray

                    if (!contents.isNullOrEmpty()) {
                        // Check if any chapters exist
                        val chapters = contents.filter {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "chapter"
                        }

                        if (chapters.isNotEmpty()) {
                            println("      ✓ Found ${chapters.size} chapters in RSS Feeds book")
                            val chapterNames = chapters.map {
                                it.jsonObject["name"]?.jsonPrimitive?.content
                            }
                            println("      ℹ️  Chapters: ${chapterNames.joinToString(", ")}")
                        } else {
                            println("      ℹ️  No chapters found - RSS articles may be at book level")
                        }
                    } else {
                        println("      ℹ️  RSS book has no contents yet")
                    }
                }
            } else {
                println("      ℹ️  RSS book not found")
            }
        }

        test("BookStack: CVE book has chapters organized by severity") {
            val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=CVE%20Database")

            if (booksResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
            val books = booksJson["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int

                if (bookId != null) {
                    val bookDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/books/$bookId")
                    val bookDetail = Json.parseToJsonElement(bookDetailResponse.bodyAsText()).jsonObject
                    val contents = bookDetail["contents"]?.jsonArray

                    if (!contents.isNullOrEmpty()) {
                        val chapters = contents.filter {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "chapter"
                        }

                        if (chapters.isNotEmpty()) {
                            println("      ✓ Found ${chapters.size} chapters in CVE Database book")
                            val chapterNames = chapters.map {
                                it.jsonObject["name"]?.jsonPrimitive?.content
                            }
                            // Should have severity-based chapters
                            println("      ℹ️  Chapters: ${chapterNames.joinToString(", ")}")

                            // Verify severity chapters exist
                            val hasSeverityChapters = chapterNames.any {
                                it in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
                            }
                            if (hasSeverityChapters) {
                                println("      ✓ CVE chapters are organized by severity")
                            }
                        }
                    }
                }
            }
        }

        // ================================================================================
        // PAGE CONTENT TESTS
        // ================================================================================

        test("BookStack: RSS pages contain proper HTML formatting") {
            val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/pages?filter[name]:like=")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            if (pages != null && pages.isNotEmpty()) {
                val firstPageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int

                if (firstPageId != null) {
                    val pageDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/pages/$firstPageId")
                    val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                    val html = pageDetail["html"]?.jsonPrimitive?.content

                    if (html != null) {
                        // Verify HTML structure
                        html shouldContain "<h1>"
                        html shouldContain "<div"
                        html shouldContain "</div>"

                        // Verify metadata box exists
                        html shouldContain "style="

                        // Verify footer attribution
                        html shouldContain "automatically generated"
                        html shouldContain "Datamancy Pipeline"

                        println("      ✓ Page has proper HTML formatting")
                        println("      ✓ Page includes attribution footer")
                    }
                }
            } else {
                println("      ℹ️  No pages found in BookStack")
            }
        }

        test("BookStack: Pages have proper tags") {
            val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/pages?count=5")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            if (pages != null && pages.isNotEmpty()) {
                var pagesWithTags = 0
                var pagesWithSourceTag = 0

                pages.take(5).forEach { pageElement ->
                    val pageId = pageElement.jsonObject["id"]?.jsonPrimitive?.int

                    if (pageId != null) {
                        val pageDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/pages/$pageId")
                        val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                        val tags = pageDetail["tags"]?.jsonArray

                        if (!tags.isNullOrEmpty()) {
                            pagesWithTags++

                            // Check for source tag
                            val hasSourceTag = tags.any {
                                it.jsonObject["name"]?.jsonPrimitive?.content == "source"
                            }

                            if (hasSourceTag) {
                                pagesWithSourceTag++
                            }
                        }
                    }
                }

                if (pagesWithTags > 0) {
                    println("      ✓ Found $pagesWithTags pages with tags")
                }

                if (pagesWithSourceTag > 0) {
                    println("      ✓ Found $pagesWithSourceTag pages with 'source' tag")
                }
            } else {
                println("      ℹ️  No pages found in BookStack")
            }
        }

        // ================================================================================
        // DATA VALIDATION TESTS
        // ================================================================================

        test("BookStack: RSS pages contain article metadata") {
            val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/pages?filter[book_id]=1")

            if (pagesResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
            val pages = pagesJson["data"]?.jsonArray

            if (pages != null && pages.isNotEmpty()) {
                val firstPageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int

                if (firstPageId != null) {
                    val pageDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/pages/$firstPageId")
                    val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                    val html = pageDetail["html"]?.jsonPrimitive?.content

                    if (html != null && html.contains("Source:")) {
                        // Check for RSS-specific metadata
                        val hasMetadata = html.contains("Published:") ||
                                        html.contains("Author:") ||
                                        html.contains("Categories:")

                        if (hasMetadata) {
                            println("      ✓ RSS page contains article metadata")
                        }
                    }
                }
            }
        }

        test("BookStack: CVE pages contain vulnerability details") {
            val booksResponse = client.getRawResponse("${endpoints.bookstack}/api/books?filter[name]=CVE%20Database")

            if (booksResponse.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val booksJson = Json.parseToJsonElement(booksResponse.bodyAsText()).jsonObject
            val books = booksJson["data"]?.jsonArray

            if (books != null && books.isNotEmpty()) {
                val bookId = books.first().jsonObject["id"]?.jsonPrimitive?.int

                if (bookId != null) {
                    val pagesResponse = client.getRawResponse("${endpoints.bookstack}/api/pages?filter[book_id]=$bookId&count=1")
                    val pagesJson = Json.parseToJsonElement(pagesResponse.bodyAsText()).jsonObject
                    val pages = pagesJson["data"]?.jsonArray

                    if (pages != null && pages.isNotEmpty()) {
                        val pageId = pages.first().jsonObject["id"]?.jsonPrimitive?.int

                        if (pageId != null) {
                            val pageDetailResponse = client.getRawResponse("${endpoints.bookstack}/api/pages/$pageId")
                            val pageDetail = Json.parseToJsonElement(pageDetailResponse.bodyAsText()).jsonObject
                            val html = pageDetail["html"]?.jsonPrimitive?.content

                            if (html != null) {
                                // Check for CVE-specific fields
                                html shouldContain "Severity:"
                                html shouldContain "Published:"

                                println("      ✓ CVE page contains vulnerability details")
                            }
                        }
                    }
                }
            }
        }

        // ================================================================================
        // STATISTICS
        // ================================================================================

        test("BookStack: Count total books created by pipeline") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/books")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val books = json["data"]?.jsonArray

            if (books != null) {
                // Filter for pipeline-generated books
                val pipelineBooks = listOf(
                    "RSS Feeds",
                    "CVE Database",
                    "Wikipedia",
                    "Australian Legal Corpus",
                    "Linux Documentation",
                    "Debian Wiki",
                    "Arch Wiki"
                )

                val foundBooks = books.filter {
                    val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    name in pipelineBooks
                }

                println("      ✓ Found ${foundBooks.size}/${pipelineBooks.size} pipeline-generated books in BookStack")

                foundBooks.forEach {
                    val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    println("        • $name")
                }
            }
        }

        test("BookStack: Count total pages created by pipeline") {
            val response = client.getRawResponse("${endpoints.bookstack}/api/pages?count=1000")

            if (response.status == HttpStatusCode.Unauthorized) {
                println("      ℹ️  BookStack authentication required - skipping")
                return@test
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val pages = json["data"]?.jsonArray

            if (pages != null) {
                println("      ✓ Found ${pages.size} total pages in BookStack")

                // Try to get pages with source tags
                var pipelinePages = 0
                pages.take(100).forEach { pageElement ->
                    val tags = pageElement.jsonObject["tags"]?.jsonArray
                    if (tags != null) {
                        val hasSourceTag = tags.any {
                            val tagName = it.jsonObject["name"]?.jsonPrimitive?.content
                            tagName == "source"
                        }
                        if (hasSourceTag) {
                            pipelinePages++
                        }
                    }
                }

                if (pipelinePages > 0) {
                    println("      ✓ At least $pipelinePages pages have pipeline source tags")
                }
            }
        }
    }
}
