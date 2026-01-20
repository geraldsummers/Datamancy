package org.datamancy.datatransformer

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
     * Also supports CVE collection: "cve"
     */
    override suspend fun getPages(collection: String): List<PageInfo> {
        logger.info { "Fetching pages from ClickHouse for collection: $collection" }

        // Handle CVE collection
        if (collection == "cve") {
            return getCVEPages()
        }

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
     * Get CVE pages from ClickHouse
     */
    private fun getCVEPages(): List<PageInfo> {
        val pages = mutableListOf<PageInfo>()
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val sql = """
                    SELECT DISTINCT
                        bitAnd(cityHash64(cve_id), 0x7FFFFFFF) as page_id,
                        cve_id as page_name,
                        concat('https://nvd.nist.gov/vuln/detail/', cve_id) as page_url
                    FROM cve_data
                    ORDER BY last_modified DESC
                """.trimIndent()

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    while (rs.next()) {
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

            logger.info { "Found ${pages.size} CVE pages in ClickHouse" }
            return pages

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch CVE pages from ClickHouse" }
            throw e
        }
    }

    /**
     * Export a single document section as plain text from ClickHouse.
     * The pageId is a hash of (doc_id + section_number) or hash of (cve_id).
     */
    override suspend fun exportPage(pageId: Int): String {
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        try {
            // Try CVE first
            val cveContent = tryExportCVE(jdbcUrl, pageId)
            if (cveContent != null) return cveContent

            // Fall back to legal documents
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
     * Try to export a CVE page by pageId
     */
    private fun tryExportCVE(jdbcUrl: String, pageId: Int): String? {
        return try {
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val sql = """
                    SELECT
                        cve_id,
                        published_date,
                        last_modified,
                        cvss_v3_score,
                        cvss_v3_severity,
                        cvss_v2_score,
                        cvss_v2_severity,
                        description,
                        cwe_ids,
                        cpe_matches,
                        references
                    FROM cve_data
                    WHERE bitAnd(cityHash64(cve_id), 0x7FFFFFFF) = ${pageId.toLong()}
                    LIMIT 1
                """.trimIndent()

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(sql)
                    if (rs.next()) {
                        val cveId = rs.getString("cve_id")
                        val publishedDate = rs.getString("published_date")
                        val cvssV3Score = rs.getDouble("cvss_v3_score")
                        val cvssV3Severity = rs.getString("cvss_v3_severity")
                        val cvssV2Score = rs.getDouble("cvss_v2_score")
                        val cvssV2Severity = rs.getString("cvss_v2_severity")
                        val description = rs.getString("description")
                        val cweIds = rs.getArray("cwe_ids")?.array as? Array<*>
                        val cpeMatches = rs.getArray("cpe_matches")?.array as? Array<*>
                        val references = rs.getArray("references")?.array as? Array<*>

                        // Build formatted markdown
                        buildString {
                            appendLine("# $cveId")
                            appendLine()

                            // Severity and CVSS
                            val severity = cvssV3Severity.takeIf { it.isNotEmpty() } ?: cvssV2Severity
                            val score = if (cvssV3Score > 0.0) cvssV3Score else cvssV2Score
                            if (severity.isNotEmpty()) {
                                appendLine("**Severity:** $severity")
                            }
                            if (score > 0.0) {
                                appendLine("**CVSS Score:** $score")
                            }
                            appendLine("**Published:** $publishedDate")
                            appendLine()

                            // CWE IDs
                            if (cweIds != null && cweIds.isNotEmpty()) {
                                appendLine("**Weakness Types (CWE):**")
                                cweIds.forEach { cwe ->
                                    appendLine("- $cwe")
                                }
                                appendLine()
                            }

                            // Description
                            appendLine("## Description")
                            appendLine()
                            appendLine(description)
                            appendLine()

                            // Affected Products (CPE matches)
                            if (cpeMatches != null && cpeMatches.isNotEmpty()) {
                                appendLine("## Affected Products")
                                appendLine()
                                cpeMatches.take(20).forEach { cpe ->
                                    appendLine("- `$cpe`")
                                }
                                if (cpeMatches.size > 20) {
                                    appendLine("- ... and ${cpeMatches.size - 20} more")
                                }
                                appendLine()
                            }

                            // References
                            if (references != null && references.isNotEmpty()) {
                                appendLine("## References")
                                appendLine()
                                references.take(10).forEach { ref ->
                                    appendLine("- $ref")
                                }
                                if (references.size > 10) {
                                    appendLine("- ... and ${references.size - 10} more")
                                }
                                appendLine()
                            }

                            appendLine("**Source:** https://nvd.nist.gov/vuln/detail/$cveId")
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Not a CVE page: $pageId" }
            null
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
                // Add CVE collection if table has data
                val cveSql = "SELECT count() as cnt FROM cve_data"
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(cveSql)
                    if (rs.next() && rs.getLong("cnt") > 0) {
                        collections.add("cve")
                    }
                }

                // Add legal collections
                val legalSql = "SELECT DISTINCT lower(jurisdiction) as jurisdiction FROM legal_documents ORDER BY jurisdiction"
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(legalSql)
                    while (rs.next()) {
                        val jurisdiction = rs.getString("jurisdiction")
                        collections.add("legal-$jurisdiction")
                    }
                }
            }

            logger.info { "Found ${collections.size} collections in ClickHouse: $collections" }
            return collections

        } catch (e: Exception) {
            logger.error(e) { "Failed to list collections from ClickHouse" }
            return emptyList()
        }
    }
}
