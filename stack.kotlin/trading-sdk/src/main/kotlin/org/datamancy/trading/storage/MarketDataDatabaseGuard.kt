package org.datamancy.trading.storage

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

private const val MARKET_DATA_IDENTITY_TABLE = "market_data_database_identity"
private const val MARKET_DATA_ROLE = "market_data"
private val canonicalMarketTables = listOf(
    "market_data",
    "orderbook_data",
    "raw_sync_state",
    "feature_materialization_state",
    "feature_coverage_state",
    "execution_context_5m",
    "alpha_signal_panel_1d"
)
private val verifiedMarketDatabaseKeys = ConcurrentHashMap.newKeySet<String>()
private val verifiedPrimaryDatabaseKeys = ConcurrentHashMap.newKeySet<String>()

fun verifyCanonicalMarketDataDatabase(
    connection: Connection,
    verificationKey: String,
    descriptor: String,
    canonicalFeatureTable: String = "alpha_signal_panel_1d"
) {
    if (!verifiedMarketDatabaseKeys.add(verificationKey)) return
    try {
        val identityExists = connection.publicTableExists(MARKET_DATA_IDENTITY_TABLE)
        require(identityExists) {
            val existingTables = connection.existingCanonicalMarketTables()
            buildString {
                append("$descriptor is not a canonical market-data database: missing $MARKET_DATA_IDENTITY_TABLE")
                if (existingTables.isNotEmpty()) {
                    append(" (found stale canonical tables ${existingTables.joinToString(",")})")
                }
            }
        }

        val identity = connection.prepareStatement(
            """
            SELECT database_role, canonical_feature_table
            FROM $MARKET_DATA_IDENTITY_TABLE
            LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                require(rs.next()) {
                    "$descriptor is not a canonical market-data database: $MARKET_DATA_IDENTITY_TABLE contains no rows"
                }
                MarketDataDatabaseIdentity(
                    databaseRole = rs.getString("database_role")?.trim().orEmpty(),
                    canonicalFeatureTable = rs.getString("canonical_feature_table")?.trim().orEmpty()
                )
            }
        }

        require(identity.databaseRole == MARKET_DATA_ROLE) {
            "$descriptor is not a canonical market-data database: expected database_role=$MARKET_DATA_ROLE " +
                "but found ${identity.databaseRole.ifBlank { "<blank>" }}"
        }
        require(identity.canonicalFeatureTable == canonicalFeatureTable) {
            "$descriptor is not a canonical market-data database: expected canonical_feature_table=$canonicalFeatureTable " +
                "but found ${identity.canonicalFeatureTable.ifBlank { "<blank>" }}"
        }
        require(connection.publicTableExists(canonicalFeatureTable)) {
            "$descriptor is not a canonical market-data database: missing canonical feature table $canonicalFeatureTable"
        }
    } catch (ex: Exception) {
        verifiedMarketDatabaseKeys.remove(verificationKey)
        throw ex
    }
}

fun verifyPrimaryDatabaseDoesNotContainCanonicalMarketData(
    connection: Connection,
    verificationKey: String,
    descriptor: String
) {
    if (!verifiedPrimaryDatabaseKeys.add(verificationKey)) return
    try {
        val existingTables = connection.existingCanonicalMarketTables()
        require(existingTables.isEmpty()) {
            "$descriptor unexpectedly contains canonical market-data tables ${existingTables.joinToString(",")}. " +
                "Primary app databases must not host market_data/execution_context_5m/alpha_signal_panel_1d state."
        }
        require(!connection.publicTableExists(MARKET_DATA_IDENTITY_TABLE)) {
            "$descriptor unexpectedly exposes $MARKET_DATA_IDENTITY_TABLE. " +
                "Primary app databases must not masquerade as canonical market-data storage."
        }
    } catch (ex: Exception) {
        verifiedPrimaryDatabaseKeys.remove(verificationKey)
        throw ex
    }
}

private data class MarketDataDatabaseIdentity(
    val databaseRole: String,
    val canonicalFeatureTable: String
)

private fun Connection.publicTableExists(tableName: String): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = ?
        )
        """.trimIndent()
    ).use { stmt ->
        stmt.setString(1, tableName)
        stmt.executeQuery().use { rs ->
            rs.next() && rs.getBoolean(1)
        }
    }

private fun Connection.existingCanonicalMarketTables(): List<String> =
    prepareStatement(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = ANY (?)
        ORDER BY table_name
        """.trimIndent()
    ).use { stmt ->
        stmt.setArray(1, createArrayOf("text", canonicalMarketTables.toTypedArray()))
        stmt.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.getString(1))
            }
        }
    }
