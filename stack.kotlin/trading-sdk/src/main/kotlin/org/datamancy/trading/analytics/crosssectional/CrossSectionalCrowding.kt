package org.datamancy.trading.analytics.crosssectional

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

internal data class CrowdingContextMetrics(
    val fundingZ: Double,
    val fundingChangeZ: Double,
    val oiChangeZ: Double,
    val oiAccelerationZ: Double,
    val oiNotionalZ: Double,
    val participationScore: Double,
    val crowdingScore: Double
)

internal fun computeCrowdingContextMetrics(
    bucket: List<UnrankedFeature>,
    comparableCohorts: Map<String, List<UnrankedFeature>>,
    minimumCohortSize: Int
): Map<UnrankedFeature, CrowdingContextMetrics> {
    if (bucket.isEmpty()) return emptyMap()

    fun cohortFor(row: UnrankedFeature): List<UnrankedFeature> =
        comparableCohorts[reversionUniverseBucket(row)].orEmpty()
            .takeIf { it.size >= minimumCohortSize }
            ?: bucket

    return bucket.associateWith { row ->
        val cohort = cohortFor(row)
        val fundingUniverse = cohort.map { it.fundingRate }
        val fundingChangeUniverse = cohort.map { it.fundingChange }
        val oiChangeUniverse = cohort.map { it.oiChange }
        val oiAccelerationUniverse = cohort.map { it.oiAcceleration }
        val oiNotionalUniverse = cohort.map { ln(max(it.openInterestNotionalUsd, 0.0) + 1.0) }
        val fundingZ = robustZScore(
            value = row.fundingRate,
            values = fundingUniverse,
            fallbackScale = max(stdev(fundingUniverse), 1e-4)
        )
        val fundingChangeZ = robustZScore(
            value = row.fundingChange,
            values = fundingChangeUniverse,
            fallbackScale = max(stdev(fundingChangeUniverse), 5e-5)
        )
        val oiChangeZ = robustZScore(
            value = row.oiChange,
            values = oiChangeUniverse,
            fallbackScale = max(stdev(oiChangeUniverse), 0.01)
        )
        val oiAccelerationZ = robustZScore(
            value = row.oiAcceleration,
            values = oiAccelerationUniverse,
            fallbackScale = max(stdev(oiAccelerationUniverse), 0.01)
        )
        val oiNotionalZ = robustZScore(
            value = ln(max(row.openInterestNotionalUsd, 0.0) + 1.0),
            values = oiNotionalUniverse,
            fallbackScale = max(stdev(oiNotionalUniverse), 0.1)
        )
        val trendReference = if (abs(row.mediumTrendScore) > abs(row.rawTrend)) row.mediumTrendScore else row.rawTrend
        val trendDirection = direction(trendReference)
        val participationScore = clamp(
            (trendDirection * oiChangeZ * 0.85) +
                (trendDirection * oiAccelerationZ * 0.45) +
                (trendDirection * fundingChangeZ * 0.25),
            -6.0,
            6.0
        )
        val crowdingScore = clamp(
            participationScore +
                (trendDirection * fundingZ * 0.35) +
                (max(0.0, oiNotionalZ) * 0.18) -
                (max(0.0, abs(fundingZ) - 1.8) * 0.55),
            -6.0,
            6.0
        )
        CrowdingContextMetrics(
            fundingZ = fundingZ,
            fundingChangeZ = fundingChangeZ,
            oiChangeZ = oiChangeZ,
            oiAccelerationZ = oiAccelerationZ,
            oiNotionalZ = oiNotionalZ,
            participationScore = participationScore,
            crowdingScore = crowdingScore
        )
    }
}
