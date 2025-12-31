package org.datamancy.unifiedindexer

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}
private val gson = Gson()

/**
 * Source adapter for reading documents from ClickHouse.
 * This makes ClickHouse the single source of truth for indexing.
 */
class ClickHouseSourceAdapter(
    private val clickhouseUrl: String,
    private val user: String = System.getenv("CLICKHOUSE_USER") ?: "default",
    private val password: String = System.getenv("CLICKHOUSE_PASSWORD") ?: ""
) : SourceAdapter {

    /**
     * Get all legal document sections from ClickHouse for a jurisdiction.
     * Collection name format: "legal-{jurisdiction}" e.g. "legal-federal", "legal-nsw"
     */
    override suspend fun getPages(collection: String): List<PageInfo> {
        logger.info { "Fetching pages from ClickHouse for collection: $collection" }

        // Parse jurisdiction from collection name
        val jurisdiction = when {
            collection.startsWith("legal-") -> collection.removePrefix("legal-")
            collection == "legal" -> "%"  // All jurisdictions
            else -> {
                logger.warn { "Unknown collection format: $collection, using as-is" }
                collection
            }
        }

        val pages = mutableListOf<PageInfo>()
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val sql = if (jurisdiction == "%") {
                    """
                    SELECT DISTINCT
                        bitAnd(cityHash64(concat(doc_id, section_number)), 0x7FFFFFFF) as page_id,
                        concat(title, ' - Section ', section_number, ': ', section_title) as page_name,
                        url as page_url
                    FROM legal_documents
                    ORDER BY jurisdiction, doc_id, section_number
                    """.trimIndent()
                } else {
                    """
                    SELECT DISTINCT
                        bitAnd(cityHash64(concat(doc_id, section_number)), 0x7FFFFFFF) as page_id,
                        concat(title, ' - Section ', section_number, ': ', section_title) as page_name,
                        url as page_url
                    FROM legal_documents
                    WHERE lower(jurisdiction) = lower('$jurisdiction')
                    ORDER BY doc_id, section_number
                    """.trimIndent()
                }

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    while (rs.next()) {
                        // ClickHouse bitAnd returns the masked value directly as Long
                        val pageId = rs.getLong("page_id").toInt()
                        pages.add(
                            PageInfo(
                                id = pageId,
                                name = rs.getString("page_name"),
                                url = rs.getString("page_url")
                            )
                        )
                    }
                }
            }

            logger.info { "Found ${pages.size} pages in ClickHouse for $collection" }
            return pages

        } catch (e: Exception) {
            logger.error { "Failed to fetch pages from ClickHouse for $collection: ${e.javaClass.name}: ${e.message}" }
            logger.error { "JDBC URL: $jdbcUrl, User: $user" }
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Export a single document section as plain text from ClickHouse.
     * The pageId is a hash of (doc_id + section_number).
     */
    override suspend fun exportPage(pageId: Int): String {
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                // Find the document by matching the hash (using same masking as getPages)
                // pageId is a positive Int masked from UInt64, need to match against masked hash
                val sql = """
                    SELECT
                        doc_id,
                        jurisdiction,
                        doc_type,
                        title,
                        year,
                        section_number,
                        section_title,
                        content_markdown,
                        url
                    FROM legal_documents
                    WHERE bitAnd(cityHash64(concat(doc_id, section_number)), 0x7FFFFFFF) = ${pageId.toLong()}
                    LIMIT 1
                """.trimIndent()

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    if (rs.next()) {
                        // Build structured text for embedding
                        val jurisdiction = rs.getString("jurisdiction")
                        val docType = rs.getString("doc_type")
                        val title = rs.getString("title")
                        val year = rs.getString("year")
                        val sectionNumber = rs.getString("section_number")
                        val sectionTitle = rs.getString("section_title")
                        val content = rs.getString("content_markdown")
                        val url = rs.getString("url")

                        // Format for RAG: include metadata in the text for better context
                        return buildString {
                            appendLine("# $title")
                            if (year.isNotEmpty()) appendLine("**Year:** $year")
                            appendLine("**Jurisdiction:** ${jurisdiction.uppercase()}")
                            appendLine("**Type:** $docType")
                            if (sectionNumber.isNotEmpty()) {
                                appendLine("\n## Section $sectionNumber: $sectionTitle")
                            }
                            appendLine()
                            appendLine(content)
                            appendLine()
                            appendLine("**Source:** $url")
                        }
                    } else {
                        throw Exception("Document not found for pageId: $pageId")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error { "Failed to export page $pageId from ClickHouse: ${e.javaClass.name}: ${e.message}" }
            e.printStackTrace()
            throw e
        }
    }

    /**
     * List all available legal collections in ClickHouse.
     */
    fun listCollections(): List<String> {
        val collections = mutableListOf<String>()
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val sql = "SELECT DISTINCT lower(jurisdiction) as jurisdiction FROM legal_documents ORDER BY jurisdiction"

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    while (rs.next()) {
                        val jurisdiction = rs.getString("jurisdiction")
                        collections.add("legal-$jurisdiction")
                    }
                }
            }

            logger.info { "Found ${collections.size} legal collections in ClickHouse: $collections" }
            return collections

        } catch (e: Exception) {
            logger.error(e) { "Failed to list collections from ClickHouse" }
            return emptyList()
        }
    }
}
