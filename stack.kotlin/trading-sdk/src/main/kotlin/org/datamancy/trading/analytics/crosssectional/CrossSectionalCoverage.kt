package org.datamancy.trading.analytics.crosssectional

import org.datamancy.trading.policy.CoverageContractPolicy
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import kotlin.math.max

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L

internal fun barCloseLagSeconds(
    bucketStartTime: Instant?,
    referenceTime: Instant = Instant.now(),
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Long =
    bucketStartTime
        ?.plusSeconds(max(bucketSeconds, 1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).seconds.coerceAtLeast(0L)
        }
        ?: Long.MAX_VALUE

internal fun barCloseLagMinutes(
    bucketStartTime: Instant?,
    referenceTime: Instant = Instant.now(),
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Long? =
    bucketStartTime
        ?.plusSeconds(max(bucketSeconds, 1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).toMinutes().coerceAtLeast(0L)
        }

internal fun effectiveCoverageMaxFeatureLagSeconds(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long =
    max(
        coveragePolicy.maxFeatureLagSeconds,
        coveragePolicy.maxFeatureLagSeconds + (max(barMinutes, 1).toLong() - 1L).coerceAtLeast(0L) * 60L
    )

internal fun effectiveCoverageMaxExecutionLagSeconds(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long = effectiveCoverageMaxFeatureLagSeconds(coveragePolicy, barMinutes)

internal fun effectiveCoverageMaxCrowdingLagSeconds(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long =
    max(
        effectiveCoverageMaxFeatureLagSeconds(coveragePolicy, barMinutes),
        max(7_200L, max(barMinutes, 1).toLong() * 120L)
    )

internal fun effectiveCoverageMaxFinalizedLagMinutes(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int
): Long =
    max(
        coveragePolicy.maxFinalizedLagMinutes,
        coveragePolicy.maxFinalizedLagMinutes + (max(barMinutes, 1).toLong() - 1L).coerceAtLeast(0L)
    )

private data class ExecutionCoverageBar(
    val time: Instant,
    val executionObserved: Boolean,
    val latestExecutionObservedTime: Instant?
)

private data class ExecutionObservationCoverage(
    val observedBars: Int,
    val latestExecutionObservedTime: Instant?
)

private fun alignTimeToBucketStart(
    time: Instant?,
    bucketSeconds: Long
): Instant? =
    time?.let {
        val normalizedBucketSeconds = max(bucketSeconds, 1L)
        Instant.ofEpochSecond((it.epochSecond / normalizedBucketSeconds) * normalizedBucketSeconds)
    }

private fun effectiveLatestExecutionObservedTime(bar: ExecutionCoverageBar): Instant? =
    bar.latestExecutionObservedTime ?: bar.time.takeIf { bar.executionObserved }

private fun computeExecutionObservationCoverage(
    bars: List<ExecutionCoverageBar>,
    bucketSeconds: Long,
    maxExecutionLagSeconds: Long
): ExecutionObservationCoverage {
    if (bars.isEmpty()) {
        return ExecutionObservationCoverage(
            observedBars = 0,
            latestExecutionObservedTime = null
        )
    }

    val normalizedBucketSeconds = max(bucketSeconds, 1L)
    val normalizedMaxExecutionLagSeconds = max(maxExecutionLagSeconds, 0L)
    var latestSeenExecutionObservedTime: Instant? = null
    var observedBars = 0

    bars.sortedBy { it.time }.forEach { bar ->
        val barExecutionObservedTime = effectiveLatestExecutionObservedTime(bar)
        if (barExecutionObservedTime != null &&
            (latestSeenExecutionObservedTime == null || barExecutionObservedTime.isAfter(latestSeenExecutionObservedTime))
        ) {
            latestSeenExecutionObservedTime = barExecutionObservedTime
        }

        val bucketClose = bar.time.plusSeconds(normalizedBucketSeconds)
        val isExecutionObserved = latestSeenExecutionObservedTime?.let { observedTime ->
            !observedTime.isAfter(bucketClose) &&
                Duration.between(observedTime, bucketClose).seconds <= normalizedMaxExecutionLagSeconds
        } ?: false

        if (isExecutionObserved) {
            observedBars += 1
        }
    }

    return ExecutionObservationCoverage(
        observedBars = observedBars,
        latestExecutionObservedTime = latestSeenExecutionObservedTime
    )
}

data class ResearchCoverageSnapshot(
    val symbol: String,
    val expectedBars: Int,
    val observedBars: Int,
    val finalizedBars: Int,
    val executionObservedBars: Int,
    val coverageRatio: Double,
    val finalizedRatio: Double,
    val executionObservedRatio: Double,
    val latestFeatureTime: Instant?,
    val finalizedThrough: Instant?,
    val latestExecutionObservedTime: Instant?,
    val latestFeatureLagSeconds: Long,
    val finalizedLagMinutes: Long?,
    val latestExecutionObservedLagSeconds: Long?,
    val crowdingObservedBars: Int = 0,
    val crowdingObservedRatio: Double = 0.0,
    val latestCrowdingObservedTime: Instant? = null,
    val latestCrowdingObservedLagSeconds: Long? = null
)

data class ResearchCoverageVerdict(
    val exchange: String,
    val requestedSymbols: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val eligibleSymbols: List<String>,
    val snapshots: List<ResearchCoverageSnapshot>,
    val passed: Boolean,
    val reason: String?
)

internal data class ResearchCoverageLagMetrics(
    val latestFeatureLagSeconds: Long,
    val finalizedLagMinutes: Long?,
    val latestExecutionObservedLagSeconds: Long?,
    val latestCrowdingObservedLagSeconds: Long?
)

private data class CoverageGateRequirements(
    val requiredContexts: Set<ResearchFeatureContext>,
    val requiresExecution: Boolean,
    val requiresCrowding: Boolean,
    val maxFeatureLagSeconds: Long,
    val maxExecutionLagSeconds: Long,
    val maxFinalizedLagMinutes: Long,
    val maxCrowdingLagSeconds: Long
)

data class CrossSectionalExchangeReadiness(
    val exchange: String,
    val marketAliases: List<String>,
    val discoveredSymbols: Int,
    val eligibleSymbols: Int,
    val executionEligibleSymbols: Int,
    val promotionEligibleSymbols: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val passed: Boolean,
    val executionPassed: Boolean,
    val promotionPassed: Boolean,
    val reason: String?,
    val executionReason: String?,
    val promotionReason: String?,
    val sampleEligibleSymbols: List<String>,
    val sampleExecutionEligibleSymbols: List<String>,
    val samplePromotionEligibleSymbols: List<String>,
    val sampleCoverageFailures: List<ResearchCoverageSnapshot>,
    val sampleExecutionCoverageFailures: List<ResearchCoverageSnapshot>,
    val samplePromotionCoverageFailures: List<ResearchCoverageSnapshot>,
    val requiredSignalContexts: List<String> = emptyList(),
    val requiredExecutionContexts: List<String> = emptyList(),
    val requiredPromotionContexts: List<String> = emptyList()
)

data class CrossSectionalResearchReadiness(
    val config: ResearchConfig,
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangePlans: List<ExchangePlan>,
    val exchangeCatalogMs: Long,
    val discoveryMs: Long,
    val discoveryCandidateLimit: Int,
    val requiredBars: Int,
    val minimumEligibleSymbols: Int,
    val passed: Boolean,
    val executionPassed: Boolean,
    val promotionPassed: Boolean,
    val reason: String?,
    val executionReason: String?,
    val promotionReason: String?,
    val requiredSignalContexts: List<String> = emptyList(),
    val requiredExecutionContexts: List<String> = emptyList(),
    val requiredPromotionContexts: List<String> = emptyList(),
    val exchanges: List<CrossSectionalExchangeReadiness>
)

class ResearchCoverageException(message: String) : IllegalStateException(message)

private data class PreparedResearchUniverse(
    val exchangeCatalog: List<ExchangeCatalogSnapshot>,
    val exchangeCatalogMs: Long,
    val exchangePlans: List<ExchangePlan>,
    val discoveryMs: Long,
    val discoveryCandidateLimit: Int,
    val requiredCoverageBars: Int,
    val discoveredUniverse: Map<String, List<String>>,
    val signalCoverageVerdicts: Map<String, ResearchCoverageVerdict>,
    val executionCoverageVerdicts: Map<String, ResearchCoverageVerdict>,
    val promotionCoverageVerdicts: Map<String, ResearchCoverageVerdict>
)

private fun signalCoveragePolicy(
    coveragePolicy: CoverageContractPolicy,
    requiredContexts: Set<ResearchFeatureContext>
): CoverageContractPolicy =
    if (ResearchFeatureContext.EXECUTION in requiredContexts) {
        coveragePolicy
    } else {
        coveragePolicy.copy(requireExecutionObserved = false)
    }

private fun expectedCoverageBars(lookbackHours: Int, barMinutes: Int, minBars: Int): Int {
    return requiredResearchWindowBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
}

internal fun computeResearchCoverageSnapshotsFromUniverseSnapshot(
    exchange: String,
    snapshot: UniverseSnapshot,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    minBars: Int,
    referenceTime: Instant = Instant.now(),
    coveragePolicy: CoverageContractPolicy = crossSectionalPolicy().coverage
): List<ResearchCoverageSnapshot> {
    if (symbols.isEmpty()) return emptyList()

    val expectedBars = expectedCoverageBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
    val window = alignedResearchWindowBounds(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        now = referenceTime
    )
    val maxExecutionLagSeconds = effectiveCoverageMaxExecutionLagSeconds(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes
    )

    return symbols
        .map(String::trim)
        .map(String::uppercase)
        .filter(String::isNotEmpty)
        .distinct()
        .mapNotNull { symbol ->
            val bars = snapshot.barsBySymbol[symbol].orEmpty()
                .filter { !it.time.isBefore(window.startInclusive) && it.time.isBefore(window.endExclusive) }
            if (bars.isEmpty()) {
                null
            } else {
                val latestFeatureTime = bars.maxOfOrNull { it.time }
                val finalizedThrough = bars.filter { it.finalized }.maxOfOrNull { it.time }
                val latestCrowdingObservedTime = bars
                    .filter { it.assetContextObserved }
                    .maxOfOrNull { it.latestCrowdingObservedTime ?: it.time }
                val executionCoverage = computeExecutionObservationCoverage(
                    bars = bars.map { bar ->
                        ExecutionCoverageBar(
                            time = bar.time,
                            executionObserved = bar.executionObserved,
                            latestExecutionObservedTime = bar.latestExecutionObservedTime
                        )
                    },
                    bucketSeconds = window.bucketSeconds.toLong(),
                    maxExecutionLagSeconds = maxExecutionLagSeconds
                )
                val latestExecutionObservedTime = executionCoverage.latestExecutionObservedTime
                val lagMetrics = computeResearchCoverageLagMetrics(
                    latestFeatureTime = latestFeatureTime,
                    finalizedThrough = finalizedThrough,
                    latestExecutionObservedTime = latestExecutionObservedTime,
                    latestCrowdingObservedTime = latestCrowdingObservedTime,
                    referenceTime = referenceTime,
                    bucketSeconds = window.bucketSeconds.toLong()
                )

                ResearchCoverageSnapshot(
                    symbol = symbol,
                    expectedBars = expectedBars,
                    observedBars = bars.size,
                    finalizedBars = bars.count { it.finalized },
                    executionObservedBars = executionCoverage.observedBars,
                    coverageRatio = clamp(bars.size.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    finalizedRatio = clamp(bars.count { it.finalized }.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    executionObservedRatio = clamp(executionCoverage.observedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    latestFeatureTime = latestFeatureTime,
                    finalizedThrough = finalizedThrough,
                    latestExecutionObservedTime = latestExecutionObservedTime,
                    latestFeatureLagSeconds = lagMetrics.latestFeatureLagSeconds,
                    finalizedLagMinutes = lagMetrics.finalizedLagMinutes,
                    latestExecutionObservedLagSeconds = lagMetrics.latestExecutionObservedLagSeconds,
                    crowdingObservedBars = bars.count { it.assetContextObserved },
                    crowdingObservedRatio = clamp(bars.count { it.assetContextObserved }.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                    latestCrowdingObservedTime = latestCrowdingObservedTime,
                    latestCrowdingObservedLagSeconds = lagMetrics.latestCrowdingObservedLagSeconds
                )
            }
        }
        .sortedBy { it.symbol }
}

internal fun computeResearchCoverageLagMetrics(
    latestFeatureTime: Instant?,
    finalizedThrough: Instant?,
    latestExecutionObservedTime: Instant?,
    latestCrowdingObservedTime: Instant? = null,
    referenceTime: Instant,
    bucketSeconds: Long
): ResearchCoverageLagMetrics = ResearchCoverageLagMetrics(
    latestFeatureLagSeconds = barCloseLagSeconds(
        bucketStartTime = latestFeatureTime,
        referenceTime = referenceTime,
        bucketSeconds = bucketSeconds
    ),
    finalizedLagMinutes = barCloseLagMinutes(
        bucketStartTime = finalizedThrough,
        referenceTime = referenceTime,
        bucketSeconds = bucketSeconds
    ),
    latestExecutionObservedLagSeconds = alignTimeToBucketStart(
        time = latestExecutionObservedTime,
        bucketSeconds = bucketSeconds
    )?.let {
        barCloseLagSeconds(
            bucketStartTime = it,
            referenceTime = referenceTime,
            bucketSeconds = bucketSeconds
        )
    },
    latestCrowdingObservedLagSeconds = alignTimeToBucketStart(
        time = latestCrowdingObservedTime,
        bucketSeconds = bucketSeconds
    )?.let {
        barCloseLagSeconds(
            bucketStartTime = it,
            referenceTime = referenceTime,
            bucketSeconds = bucketSeconds
        )
    }
)

private fun coverageGateRequirements(
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int,
    requiredContexts: Set<ResearchFeatureContext>
): CoverageGateRequirements =
    CoverageGateRequirements(
        requiredContexts = requiredContexts,
        requiresExecution = ResearchFeatureContext.EXECUTION in requiredContexts && coveragePolicy.requireExecutionObserved,
        requiresCrowding = ResearchFeatureContext.CROWDING in requiredContexts,
        maxFeatureLagSeconds = effectiveCoverageMaxFeatureLagSeconds(coveragePolicy, barMinutes),
        maxExecutionLagSeconds = effectiveCoverageMaxExecutionLagSeconds(coveragePolicy, barMinutes),
        maxFinalizedLagMinutes = effectiveCoverageMaxFinalizedLagMinutes(coveragePolicy, barMinutes),
        maxCrowdingLagSeconds = effectiveCoverageMaxCrowdingLagSeconds(coveragePolicy, barMinutes)
    )

private fun coverageFailureComparator(prioritizeCrowding: Boolean = false): Comparator<ResearchCoverageSnapshot> =
    compareBy<ResearchCoverageSnapshot> { it.coverageRatio }
        .thenBy { it.finalizedRatio }
        .let { comparator ->
            if (prioritizeCrowding) comparator.thenBy { it.crowdingObservedRatio } else comparator
        }
        .thenBy { it.executionObservedRatio }
        .thenByDescending { it.latestFeatureLagSeconds }
        .thenBy { it.symbol }

private fun computeResearchCoverageSnapshots(
    exchange: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    minBars: Int,
    coveragePolicy: CoverageContractPolicy = crossSectionalPolicy().coverage
): List<ResearchCoverageSnapshot> {
    if (symbols.isEmpty()) return emptyList()

    val normalizedSymbols = symbols.map(String::trim).map(String::uppercase).filter(String::isNotEmpty).distinct()
    if (normalizedSymbols.isEmpty()) return emptyList()
    val referenceTime = Instant.now()

    loadUniverseSnapshot(
        aliases = aliases,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )?.let { snapshot ->
        return computeResearchCoverageSnapshotsFromUniverseSnapshot(
            exchange = exchange,
            snapshot = snapshot,
            symbols = normalizedSymbols,
            lookbackHours = lookbackHours,
            barMinutes = barMinutes,
            minBars = minBars,
            referenceTime = referenceTime,
            coveragePolicy = coveragePolicy
        )
    }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(normalizedSymbols)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = barMinutes)
    val bucketSeconds = window.bucketSeconds
    val expectedBars = expectedCoverageBars(lookbackHours = lookbackHours, barMinutes = barMinutes, minBars = minBars)
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                orderbook_observed,
                asset_context_observed,
                is_finalized,
                exchange
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                time,
                orderbook_observed,
                asset_context_observed,
                is_finalized
            FROM minute_rows
        ),
        bucket_rollup AS (
            SELECT
                symbol,
                bucket_time,
                BOOL_AND(is_finalized) AS finalized,
                MAX(time) FILTER (WHERE orderbook_observed) AS latest_execution_observed_time,
                MAX(time) FILTER (WHERE asset_context_observed) AS latest_crowding_observed_time,
                BOOL_OR(asset_context_observed) AS crowding_observed
            FROM bucketed
            GROUP BY symbol, bucket_time
        ),
        feature_freshness AS (
            SELECT
                symbol,
                MAX(time) AS latest_feature_time,
                MAX(time) FILTER (WHERE is_finalized) AS finalized_through
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND symbol IN ($symbolSql)
            GROUP BY symbol
        )
        SELECT
            b.symbol,
            b.bucket_time,
            b.finalized,
            b.latest_execution_observed_time,
            b.latest_crowding_observed_time,
            b.crowding_observed,
            f.latest_feature_time,
            f.finalized_through
        FROM bucket_rollup b
        JOIN feature_freshness f
          ON f.symbol = b.symbol
        ORDER BY b.symbol ASC, b.bucket_time ASC
    """.trimIndent()

    data class CoverageBucketRow(
        val symbol: String,
        val bucketTime: Instant,
        val finalized: Boolean,
        val latestExecutionObservedTime: Instant?,
        val latestCrowdingObservedTime: Instant?,
        val crowdingObserved: Boolean,
        val latestFeatureTime: Instant?,
        val finalizedThrough: Instant?
    )

    val bucketRows = buildList {
        pgConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        add(
                            CoverageBucketRow(
                                symbol = rs.getString("symbol"),
                                bucketTime = rs.getTimestamp("bucket_time").toInstant(),
                                finalized = rs.getBoolean("finalized"),
                                latestExecutionObservedTime = rs.getTimestamp("latest_execution_observed_time")?.toInstant(),
                                latestCrowdingObservedTime = rs.getTimestamp("latest_crowding_observed_time")?.toInstant(),
                                crowdingObserved = rs.getBoolean("crowding_observed"),
                                latestFeatureTime = rs.getTimestamp("latest_feature_time")?.toInstant(),
                                finalizedThrough = rs.getTimestamp("finalized_through")?.toInstant()
                            )
                        )
                    }
                }
            }
        }
    }
    val maxExecutionLagSeconds = effectiveCoverageMaxExecutionLagSeconds(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes
    )

    return bucketRows
        .groupBy { it.symbol }
        .map { (symbol, symbolRows) ->
            val executionCoverage = computeExecutionObservationCoverage(
                bars = symbolRows.map { row ->
                    ExecutionCoverageBar(
                        time = row.bucketTime,
                        executionObserved = row.latestExecutionObservedTime != null,
                        latestExecutionObservedTime = row.latestExecutionObservedTime
                    )
                },
                bucketSeconds = bucketSeconds.toLong(),
                maxExecutionLagSeconds = maxExecutionLagSeconds
            )
            val observedBars = symbolRows.size
            val finalizedBars = symbolRows.count { it.finalized }
            val latestFeatureTime = symbolRows.firstOrNull()?.latestFeatureTime
            val finalizedThrough = symbolRows.firstOrNull()?.finalizedThrough
            val latestExecutionObservedTime = executionCoverage.latestExecutionObservedTime
            val latestCrowdingObservedTime = symbolRows.maxOfOrNull { it.latestCrowdingObservedTime ?: Instant.MIN }
                ?.takeIf { it != Instant.MIN }
            val lagMetrics = computeResearchCoverageLagMetrics(
                latestFeatureTime = latestFeatureTime,
                finalizedThrough = finalizedThrough,
                latestExecutionObservedTime = latestExecutionObservedTime,
                latestCrowdingObservedTime = latestCrowdingObservedTime,
                referenceTime = referenceTime,
                bucketSeconds = bucketSeconds.toLong()
            )
            val crowdingObservedBars = symbolRows.count { it.crowdingObserved }

            ResearchCoverageSnapshot(
                symbol = symbol,
                expectedBars = expectedBars,
                observedBars = observedBars,
                finalizedBars = finalizedBars,
                executionObservedBars = executionCoverage.observedBars,
                coverageRatio = clamp(observedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                finalizedRatio = clamp(finalizedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                executionObservedRatio = clamp(executionCoverage.observedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                latestFeatureTime = latestFeatureTime,
                finalizedThrough = finalizedThrough,
                latestExecutionObservedTime = latestExecutionObservedTime,
                latestFeatureLagSeconds = lagMetrics.latestFeatureLagSeconds,
                finalizedLagMinutes = lagMetrics.finalizedLagMinutes,
                latestExecutionObservedLagSeconds = lagMetrics.latestExecutionObservedLagSeconds,
                crowdingObservedBars = crowdingObservedBars,
                crowdingObservedRatio = clamp(crowdingObservedBars.toDouble() / max(expectedBars, 1).toDouble(), 0.0, 1.0),
                latestCrowdingObservedTime = latestCrowdingObservedTime,
                latestCrowdingObservedLagSeconds = lagMetrics.latestCrowdingObservedLagSeconds
            )
        }
        .sortedBy { it.symbol }
}

internal fun buildResearchCoverageVerdict(
    exchange: String,
    symbols: List<String>,
    snapshots: List<ResearchCoverageSnapshot>,
    requiredBars: Int,
    coveragePolicy: CoverageContractPolicy,
    barMinutes: Int,
    requiredContexts: Set<ResearchFeatureContext> = setOf(ResearchFeatureContext.PRICE),
    gateName: String = "coverage"
): ResearchCoverageVerdict {
    val requirements = coverageGateRequirements(
        coveragePolicy = coveragePolicy,
        barMinutes = barMinutes,
        requiredContexts = requiredContexts
    )
    val eligibleSymbols = snapshots
        .filter { snapshot ->
            snapshot.observedBars >= requiredBars &&
                snapshot.coverageRatio >= coveragePolicy.minCoverageRatio &&
                snapshot.finalizedRatio >= coveragePolicy.minFinalizedRatio &&
                snapshot.latestFeatureLagSeconds <= requirements.maxFeatureLagSeconds &&
                (snapshot.finalizedLagMinutes ?: Long.MAX_VALUE) <= requirements.maxFinalizedLagMinutes &&
                (
                    !requirements.requiresExecution ||
                        (
                            snapshot.executionObservedRatio >= coveragePolicy.minExecutionObservedRatio &&
                                (snapshot.latestExecutionObservedLagSeconds ?: Long.MAX_VALUE) <= requirements.maxExecutionLagSeconds
                            )
                    ) &&
                (
                    !requirements.requiresCrowding ||
                        (
                            snapshot.crowdingObservedBars > 0 &&
                                (snapshot.latestCrowdingObservedLagSeconds ?: Long.MAX_VALUE) <= requirements.maxCrowdingLagSeconds
                            )
                    )
        }
        .map { it.symbol }
        .sorted()

    val requestedSymbols = symbols.map(String::trim).count(String::isNotEmpty)
    val reason = when {
        requestedSymbols == 0 ->
            "$gateName gate failed exchange=$exchange requestedSymbols=0 reason=no_candidate_universe"
        snapshots.isEmpty() ->
            "$gateName gate failed exchange=$exchange requestedSymbols=$requestedSymbols reason=no_feature_rows"
        eligibleSymbols.size < coveragePolicy.minUniverseSymbols -> {
            val sample = snapshots.take(3).joinToString(";") { snapshot ->
                "${snapshot.symbol}:cov=${snapshot.coverageRatio.round(3)} " +
                    "fin=${snapshot.finalizedRatio.round(3)} " +
                    "exec=${snapshot.executionObservedRatio.round(3)} " +
                    "crowd=${snapshot.crowdingObservedRatio.round(3)} " +
                    "execLag=${snapshot.latestExecutionObservedLagSeconds ?: Long.MAX_VALUE}s " +
                    "crowdLag=${snapshot.latestCrowdingObservedLagSeconds ?: Long.MAX_VALUE}s " +
                    "featLag=${snapshot.latestFeatureLagSeconds}s " +
                    "finLag=${snapshot.finalizedLagMinutes ?: Long.MAX_VALUE}m"
            }
            val thresholdSummary = buildString {
                append("cov>=${coveragePolicy.minCoverageRatio.round(3)} ")
                append("fin>=${coveragePolicy.minFinalizedRatio.round(3)} ")
                if (requirements.requiresExecution) {
                    append("exec>=${coveragePolicy.minExecutionObservedRatio.round(3)} ")
                    append("execLag<=${requirements.maxExecutionLagSeconds}s ")
                }
                if (requirements.requiresCrowding) {
                    append("crowd>0 ")
                    append("crowdLag<=${requirements.maxCrowdingLagSeconds}s ")
                }
                append("featLag<=${requirements.maxFeatureLagSeconds}s ")
                append("finLag<=${requirements.maxFinalizedLagMinutes}m")
            }
            "$gateName gate failed exchange=$exchange eligible=${eligibleSymbols.size}/$requestedSymbols " +
                "requiredMinSymbols=${coveragePolicy.minUniverseSymbols} requiredBars=$requiredBars " +
                "thresholds=$thresholdSummary sample=[$sample]"
        }
        else -> null
    }

    return ResearchCoverageVerdict(
        exchange = exchange,
        requestedSymbols = requestedSymbols,
        requiredBars = requiredBars,
        minimumEligibleSymbols = coveragePolicy.minUniverseSymbols,
        eligibleSymbols = eligibleSymbols,
        snapshots = snapshots,
        passed = reason == null,
        reason = reason
    )
}

private fun prepareResearchUniverse(config: ResearchConfig): PreparedResearchUniverse {
    val (exchangeCatalog, exchangeCatalogMs) = timedMillis {
        fetchExchangeCatalog(config.txGatewayUrl)
    }
    val exchangePlans = buildExchangePlans(exchangeCatalog, config)
    val discoveryMaxSymbols = discoveryCandidateLimit(config.maxSymbols, config.discoveryMaxSymbols)
    val requiredCoverageBars = expectedCoverageBars(
        lookbackHours = config.lookbackHours,
        barMinutes = config.barMinutes,
        minBars = config.minBars
    )
    val coveragePolicy = crossSectionalPolicy().coverage
    val signalContexts = signalFeatureContexts(config)
    val executionContexts = executionFeatureContexts(config)
    val promotionContexts = promotionFeatureContexts(config)
    val signalCoveragePolicy = signalCoveragePolicy(coveragePolicy, signalContexts)

    val (discoveredUniverse, discoveryMs) = timedMillis {
        parallelMapBlocking(
            items = exchangePlans,
            maxParallelism = resolveResearchQueryParallelism(exchangePlans.size)
        ) { plan ->
            plan.exchange to discoverSymbols(
                txBase = config.txGatewayUrl,
                exchange = plan.exchange,
                aliases = plan.marketAliases,
                lookbackHours = config.lookbackHours,
                maxSymbols = discoveryMaxSymbols,
                minBars = config.minBars,
                barMinutes = config.barMinutes
            )
        }.toMap(LinkedHashMap())
    }

    val coverageVerdicts = parallelMapBlocking(
        items = exchangePlans,
        maxParallelism = resolveResearchQueryParallelism(exchangePlans.size)
    ) { plan ->
        val symbols = discoveredUniverse[plan.exchange].orEmpty()
        val snapshots = computeResearchCoverageSnapshots(
            exchange = plan.exchange,
            aliases = plan.marketAliases,
            symbols = symbols,
            lookbackHours = config.lookbackHours,
            barMinutes = config.barMinutes,
            minBars = config.minBars,
            coveragePolicy = coveragePolicy
        )
        val signalVerdict = buildResearchCoverageVerdict(
            exchange = plan.exchange,
            symbols = symbols,
            snapshots = snapshots,
            requiredBars = requiredCoverageBars,
            coveragePolicy = signalCoveragePolicy,
            barMinutes = config.barMinutes,
            requiredContexts = signalContexts,
            gateName = "signal_coverage"
        )
        val executionVerdict = buildResearchCoverageVerdict(
            exchange = plan.exchange,
            symbols = symbols,
            snapshots = snapshots,
            requiredBars = requiredCoverageBars,
            coveragePolicy = coveragePolicy,
            barMinutes = config.barMinutes,
            requiredContexts = executionContexts,
            gateName = "execution_coverage"
        )
        val promotionVerdict = buildResearchCoverageVerdict(
            exchange = plan.exchange,
            symbols = symbols,
            snapshots = snapshots,
            requiredBars = requiredCoverageBars,
            coveragePolicy = coveragePolicy,
            barMinutes = config.barMinutes,
            requiredContexts = promotionContexts,
            gateName = "promotion_coverage"
        )
        plan.exchange to Triple(signalVerdict, executionVerdict, promotionVerdict)
    }.toMap(LinkedHashMap())

    return PreparedResearchUniverse(
        exchangeCatalog = exchangeCatalog,
        exchangeCatalogMs = exchangeCatalogMs,
        exchangePlans = exchangePlans,
        discoveryMs = discoveryMs,
        discoveryCandidateLimit = discoveryMaxSymbols,
        requiredCoverageBars = requiredCoverageBars,
        discoveredUniverse = discoveredUniverse,
        signalCoverageVerdicts = coverageVerdicts.mapValues { it.value.first },
        executionCoverageVerdicts = coverageVerdicts.mapValues { it.value.second },
        promotionCoverageVerdicts = coverageVerdicts.mapValues { it.value.third }
    )
}

fun evaluateCrossSectionalReadiness(config: ResearchConfig): CrossSectionalResearchReadiness {
    val prepared = prepareResearchUniverse(config)
    val coveragePolicy = crossSectionalPolicy().coverage
    val signalContexts = contextNames(signalFeatureContexts(config))
    val executionContexts = contextNames(executionFeatureContexts(config))
    val promotionContexts = contextNames(promotionFeatureContexts(config))
    val exchanges = prepared.exchangePlans.map { plan ->
        val signalVerdict = prepared.signalCoverageVerdicts.getValue(plan.exchange)
        val executionVerdict = prepared.executionCoverageVerdicts.getValue(plan.exchange)
        val promotionVerdict = prepared.promotionCoverageVerdicts.getValue(plan.exchange)
        val signalFailingSamples = signalVerdict.snapshots
            .filterNot { it.symbol in signalVerdict.eligibleSymbols }
            .sortedWith(coverageFailureComparator())
            .take(5)
        val executionFailingSamples = executionVerdict.snapshots
            .filterNot { it.symbol in executionVerdict.eligibleSymbols }
            .sortedWith(coverageFailureComparator())
            .take(5)
        val promotionFailingSamples = promotionVerdict.snapshots
            .filterNot { it.symbol in promotionVerdict.eligibleSymbols }
            .sortedWith(coverageFailureComparator(prioritizeCrowding = true))
            .take(5)
        CrossSectionalExchangeReadiness(
            exchange = plan.exchange,
            marketAliases = plan.marketAliases,
            discoveredSymbols = prepared.discoveredUniverse[plan.exchange].orEmpty().size,
            eligibleSymbols = signalVerdict.eligibleSymbols.size,
            executionEligibleSymbols = executionVerdict.eligibleSymbols.size,
            promotionEligibleSymbols = promotionVerdict.eligibleSymbols.size,
            requiredBars = signalVerdict.requiredBars,
            minimumEligibleSymbols = signalVerdict.minimumEligibleSymbols,
            passed = signalVerdict.passed,
            executionPassed = executionVerdict.passed,
            promotionPassed = promotionVerdict.passed,
            reason = signalVerdict.reason,
            executionReason = executionVerdict.reason,
            promotionReason = promotionVerdict.reason,
            sampleEligibleSymbols = signalVerdict.eligibleSymbols.take(12),
            sampleExecutionEligibleSymbols = executionVerdict.eligibleSymbols.take(12),
            samplePromotionEligibleSymbols = promotionVerdict.eligibleSymbols.take(12),
            sampleCoverageFailures = signalFailingSamples,
            sampleExecutionCoverageFailures = executionFailingSamples,
            samplePromotionCoverageFailures = promotionFailingSamples,
            requiredSignalContexts = signalContexts,
            requiredExecutionContexts = executionContexts,
            requiredPromotionContexts = promotionContexts
        )
    }
    val signalFailure = exchanges.firstOrNull { !it.passed }
    val executionFailure = exchanges.firstOrNull { !it.executionPassed }
    val promotionFailure = exchanges.firstOrNull { !it.promotionPassed }
    return CrossSectionalResearchReadiness(
        config = config,
        exchangeCatalog = prepared.exchangeCatalog,
        exchangePlans = prepared.exchangePlans,
        exchangeCatalogMs = prepared.exchangeCatalogMs,
        discoveryMs = prepared.discoveryMs,
        discoveryCandidateLimit = prepared.discoveryCandidateLimit,
        requiredBars = prepared.requiredCoverageBars,
        minimumEligibleSymbols = coveragePolicy.minUniverseSymbols,
        passed = signalFailure == null,
        executionPassed = executionFailure == null,
        promotionPassed = promotionFailure == null,
        reason = signalFailure?.reason,
        executionReason = executionFailure?.executionReason,
        promotionReason = promotionFailure?.promotionReason,
        requiredSignalContexts = signalContexts,
        requiredExecutionContexts = executionContexts,
        requiredPromotionContexts = promotionContexts,
        exchanges = exchanges
    )
}

fun loadResearchDataContext(config: ResearchConfig): ResearchDataContext {
    val prepared = prepareResearchUniverse(config)
    val exchangeCatalog = prepared.exchangeCatalog
    val exchangeCatalogMs = prepared.exchangeCatalogMs
    val exchangePlans = prepared.exchangePlans
    val discoveryMs = prepared.discoveryMs
    val requiredCoverageBars = prepared.requiredCoverageBars
    val discoveredUniverse = prepared.discoveredUniverse
    val signalCoverageVerdicts = prepared.signalCoverageVerdicts
    val executionCoverageVerdicts = prepared.executionCoverageVerdicts
    val promotionCoverageVerdicts = prepared.promotionCoverageVerdicts

    signalCoverageVerdicts.values.forEach { verdict ->
        println(
            "Cross-sectional signal coverage exchange=${verdict.exchange} " +
                "eligible=${verdict.eligibleSymbols.size}/${verdict.requestedSymbols} " +
                "requiredBars=${verdict.requiredBars} passed=${verdict.passed} " +
                "reason=${verdict.reason ?: "ok"}"
        )
    }
    executionCoverageVerdicts.values.forEach { verdict ->
        println(
            "Cross-sectional execution coverage exchange=${verdict.exchange} " +
                "eligible=${verdict.eligibleSymbols.size}/${verdict.requestedSymbols} " +
                "requiredBars=${verdict.requiredBars} passed=${verdict.passed} " +
                "reason=${verdict.reason ?: "ok"}"
        )
    }
    promotionCoverageVerdicts.values.forEach { verdict ->
        println(
            "Cross-sectional promotion coverage exchange=${verdict.exchange} " +
                "eligible=${verdict.eligibleSymbols.size}/${verdict.requestedSymbols} " +
                "requiredBars=${verdict.requiredBars} passed=${verdict.passed} " +
                "reason=${verdict.reason ?: "ok"}"
        )
    }

    val coverageFailure = signalCoverageVerdicts.values.firstOrNull { !it.passed }
    if (coverageFailure != null) {
        throw ResearchCoverageException(coverageFailure.reason ?: "coverage gate failed for ${coverageFailure.exchange}")
    }

    val candidateUniverse = signalCoverageVerdicts.mapValues { (_, verdict) -> verdict.eligibleSymbols }

    val (researchBars, loadBarsMs) = timedMillis {
        exchangePlans.flatMap { plan ->
            val symbols = candidateUniverse[plan.exchange].orEmpty()
            loadBars(
                exchange = plan.exchange,
                aliases = plan.marketAliases,
                symbols = symbols,
                lookbackHours = config.lookbackHours,
                barMinutes = config.barMinutes
            )
        }
    }
    val rankedUniverseCandidates = rankResearchUniverseCandidates(researchBars, config)
    val refinedUniverse = selectResearchUniverseFromCandidates(rankedUniverseCandidates, config)
        .takeIf { it.isNotEmpty() }
        ?: candidateUniverse
    val refinedBars = researchBars.filter { bar ->
        bar.symbol in refinedUniverse[bar.exchange].orEmpty()
    }
    val universeProfiles = buildUniverseProfiles(rankedUniverseCandidates, refinedUniverse, config)
    val totalCandidateSymbols = candidateUniverse.values.sumOf { it.size }
    val totalSelectedSymbols = refinedUniverse.values.sumOf { it.size }
    refinedUniverse.forEach { (exchange, symbols) ->
        val candidateSymbols = candidateUniverse[exchange].orEmpty()
        println(
            "Cross-sectional universe refinement exchange=$exchange " +
                "candidates=${candidateSymbols.size} selected=${symbols.size} " +
                "symbols=${symbols.joinToString(",")}"
        )
    }
    println(
        "Cross-sectional data load exchangePlans=${exchangePlans.size} " +
            "candidateSymbols=$totalCandidateSymbols selectedSymbols=$totalSelectedSymbols " +
            "candidateBars=${researchBars.size} selectedBars=${refinedBars.size} " +
            "catalogMs=$exchangeCatalogMs discoveryMs=$discoveryMs loadBarsMs=$loadBarsMs"
    )

    return ResearchDataContext(
        key = researchDataKey(config),
        exchangeCatalog = exchangeCatalog,
        exchangePlans = exchangePlans,
        candidateUniverse = candidateUniverse,
        discoveredUniverse = refinedUniverse,
        universeProfiles = universeProfiles,
        bars = refinedBars,
        loadedAt = Instant.now()
    )
}
