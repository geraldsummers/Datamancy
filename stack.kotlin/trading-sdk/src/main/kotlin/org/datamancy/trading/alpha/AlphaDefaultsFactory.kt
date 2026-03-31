package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import kotlin.math.ceil

object AlphaDefaultsFactory {
    fun activeInterdayControlConfig(policy: TradingPolicy): InterdayAlphaConfig {
        val datasets = policy.research.datasets
        return InterdayAlphaConfig(
            strategyFamily = policy.research.discovery.defaultStrategyFamily,
            exchange = datasets.marketExchange,
            signalBarMinutes = datasets.defaultSignalBarMinutes,
            lookbackHours = datasets.defaultLookbackHours,
            forwardHours = datasets.defaultForwardHours,
            rebalanceCadenceHours = 24,
            factorLookbackDays = 18,
            selectionQuantile = policy.research.discovery.selectionQuantiles.firstOrNull() ?: 0.020,
            trendScoreMode = InterdayTrendScoreMode.EMA_RETURN_STACK,
            residualizationMode = InterdayResidualizationMode.MARKET,
            residualizationBetaMode = InterdayResidualizationBetaMode.SIMPLE,
            residualizationMarketProxyMode = InterdayResidualizationMarketProxyMode.LIQUIDITY_WEIGHTED,
            tailWeightingMode = InterdayTailWeightingMode.VOLATILITY_SCALED,
            fastTrendDays = 2,
            mediumTrendDays = 8,
            slowTrendDays = 16,
            volatilityDays = 14,
            fundingOverlayMode = InterdayFundingOverlayMode.LINEAR_FACTOR,
            fundingWeight = 0.15,
            openInterestWeight = 0.0,
            exitOverlayMode = InterdayExitOverlayMode.TRAILING_AND_TAKE_PROFIT,
            executionWindowMinutes = 120
        )
    }

    fun datasetDefaults(policy: TradingPolicy): AlphaDatasetDefaults {
        val datasets = policy.research.datasets
        val featureFamilies = buildList {
            add("price_action")
            if (datasets.includeFunding) add("funding")
            if (datasets.includeOpenInterest) add("open_interest")
            if (datasets.includeTradeFlow) add("trade_flow")
            if (datasets.includeOrderbookConditioning) add("orderbook_conditioning")
        }
        return AlphaDatasetDefaults(
            marketExchange = datasets.marketExchange,
            executionExchange = datasets.executionExchange,
            canonicalBarMinutes = datasets.canonicalBarMinutes,
            supportedSignalBarMinutes = datasets.supportedSignalBarMinutes,
            defaultSignalBarMinutes = datasets.defaultSignalBarMinutes,
            defaultLookbackHours = datasets.defaultLookbackHours,
            defaultForwardHours = datasets.defaultForwardHours,
            anchorHorizonsMinutes = datasets.anchorHorizonsMinutes,
            coreHorizonsDays = datasets.coreHorizonsDays,
            volatilityLookbackDays = datasets.volatilityLookbackDays,
            priceReferenceMode = datasets.priceReferenceMode,
            featureFamilies = featureFamilies,
            universeBounds = UniverseBoundsSpec(
                lookbackDays = datasets.volatilityLookbackDays,
                lowerQuantiles = policy.research.discovery.selectionQuantiles,
                upperQuantiles = policy.research.discovery.selectionQuantiles.map { 1.0 - it }.sorted()
            )
        )
    }

    fun discoveryDefaults(policy: TradingPolicy): AlphaDiscoveryDefaults {
        val discovery = policy.research.discovery
        val defaultConfig = activeInterdayControlConfig(policy)
        return AlphaDiscoveryDefaults(
            strategyFamily = discovery.defaultStrategyFamily,
            supportedSignalBarMinutes = policy.research.datasets.supportedSignalBarMinutes,
            defaultSignalBarMinutes = defaultConfig.signalBarMinutes,
            defaultLookbackHours = defaultConfig.lookbackHours,
            defaultForwardHours = defaultConfig.forwardHours,
            rebalanceCadenceHours = discovery.rebalanceCadenceHours,
            executionWindowMinutes = discovery.executionWindowMinutes,
            selectionQuantiles = discovery.selectionQuantiles,
            defaultConfig = defaultConfig,
            enabledFeatures = buildList {
                add("multi_horizon_returns")
                if (discovery.useRegressionSlope) add("regression_slope")
                if (discovery.useAdxFilter) add("adx_filter")
                if (discovery.useMovingAverageFilter) add("moving_average_filter")
                if (policy.research.datasets.includeFunding) add("funding")
                if (policy.research.datasets.includeOpenInterest) add("open_interest")
            },
            universeBounds = datasetDefaults(policy).universeBounds,
            validation = AlphaValidationDefaults(
                walkForwardWindows = policy.research.validation.walkForwardWindows,
                nestedCvFolds = policy.research.validation.nestedCvFolds,
                purgedKFoldFolds = policy.research.validation.purgedKFoldFolds,
                embargoBars = policy.research.validation.embargoBars,
                atomicBlockBars = policy.research.validation.atomicBlockBars.takeIf { it > 0 }
                    ?: ceil(defaultConfig.forwardHours.toDouble() * 60.0 / defaultConfig.signalBarMinutes.toDouble().coerceAtLeast(1.0)).toInt(),
                activeBlocksPerFold = policy.research.validation.activeBlocksPerFold,
                purgeBlocksPerSide = policy.research.validation.purgeBlocksPerSide,
                maxConcurrentFoldEvaluations = policy.research.validation.maxConcurrentFoldEvaluations,
                empiricalWeightFitPasses = policy.research.validation.empiricalWeightFitPasses,
                bootstrapReplications = policy.research.validation.bootstrapReplications,
                requireDeflatedSharpe = policy.research.validation.requireDeflatedSharpe,
                requireWhitesRealityCheck = policy.research.validation.requireWhitesRealityCheck,
                regimeSlices = (policy.research.validation.regimeSlices + "market_trend")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
            )
        )
    }

    fun portfolioDefaults(policy: TradingPolicy): AlphaPortfolioDefaults {
        val portfolio = policy.research.portfolio
        return AlphaPortfolioDefaults(
            longShort = portfolio.longShort,
            selectionMode = portfolio.selectionMode,
            weightingMode = portfolio.weightingMode,
            targetGrossFraction = portfolio.targetGrossFraction,
            targetNetFraction = portfolio.targetNetFraction,
            maxWeightPerSymbol = portfolio.maxWeightPerSymbol,
            maxConcurrentLongs = portfolio.maxConcurrentLongs,
            maxConcurrentShorts = portfolio.maxConcurrentShorts,
            rebalanceTargetExposureStep = portfolio.rebalanceTargetExposureStep,
            minTargetExposureFraction = portfolio.minTargetExposureFraction,
            maxTargetExposureFraction = portfolio.maxTargetExposureFraction,
            useTrailingStops = portfolio.useTrailingStops,
            trailingStopVolMultiple = portfolio.trailingStopVolMultiple,
            takeProfitVolMultiple = portfolio.takeProfitVolMultiple,
            turnoverPenaltyBps = portfolio.turnoverPenaltyBps,
            maxParticipationRate = portfolio.maxParticipationRate
        )
    }

    fun executionDefaults(policy: TradingPolicy): AlphaExecutionDefaults {
        return AlphaExecutionDefaults(
            makerFeeBps = policy.execution.makerFeeBps,
            takerFeeBps = policy.execution.takerFeeBps,
            quoteExchange = policy.execution.quoteExchange,
            testnetQuoteExchange = policy.execution.testnetQuoteExchange,
            rebalanceTargetExposureStep = policy.research.portfolio.rebalanceTargetExposureStep,
            maxParticipationRate = policy.research.portfolio.maxParticipationRate,
            useMakerFirstExecution = policy.research.portfolio.useMakerFirstExecution,
            executionWindowMinutes = policy.research.discovery.executionWindowMinutes
        )
    }
}
