package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy

object AlphaDefaultsFactory {
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
        return AlphaDiscoveryDefaults(
            strategyFamily = discovery.defaultStrategyFamily,
            supportedSignalBarMinutes = policy.research.datasets.supportedSignalBarMinutes,
            defaultSignalBarMinutes = policy.research.datasets.defaultSignalBarMinutes,
            defaultLookbackHours = policy.research.datasets.defaultLookbackHours,
            defaultForwardHours = policy.research.datasets.defaultForwardHours,
            rebalanceCadenceHours = discovery.rebalanceCadenceHours,
            executionWindowMinutes = discovery.executionWindowMinutes,
            selectionQuantiles = discovery.selectionQuantiles,
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
                bootstrapReplications = policy.research.validation.bootstrapReplications,
                requireDeflatedSharpe = policy.research.validation.requireDeflatedSharpe,
                requireWhitesRealityCheck = policy.research.validation.requireWhitesRealityCheck,
                regimeSlices = policy.research.validation.regimeSlices
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
