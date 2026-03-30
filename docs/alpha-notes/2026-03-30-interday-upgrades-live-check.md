# 2026-03-30 Interday Upgrade Live Check

- readiness
  - `2026-03-30T00:40:00.961892933Z`
  - `READY`
  - `eligible=149`
  - `critical=0`
  - `coverage_fail=0`
  - `execution_fail=0`
  - `live_sparse=22`

- research basis
  - `deep-research-report2.md`
  - residualisation stability via smoother beta and alternate market proxies
  - narrow-tail equal-weight vs vol-scaled geometry
  - funding as bounded sizing overlay, not hidden core ranker
  - regime-sliced drift attribution including `market_trend`
  - exit overlays as explicit research families, not hardcoded stop magic

- hypothesis
  - exposing residual beta mode, market proxy mode, tail weighting, funding overlay mode, and exit overlay mode would improve the current `1d / 72h / q=2.5%` battleground or at least clarify which layer is still failing

- experiment
  - deployed updated `alpha-discovery-service` only to `latium.local`
  - validated new defaults now expose `validation.regimeSlices = [volatility, liquidity, funding, open_interest, market_trend]`
  - live run `generatedAt=2026-03-30T00:49:48.255860457Z`
    - config: baseline daily residual trend
    - `factorLookbackDays=19`
    - `residualizationBetaMode=SIMPLE`
    - `residualizationMarketProxyMode=EQUAL_WEIGHT`
    - `tailWeightingMode=VOLATILITY_SCALED`
    - `fundingOverlayMode=LINEAR_FACTOR`
    - `exitOverlayMode=TRAILING_AND_TAKE_PROFIT`
  - live run `generatedAt=2026-03-30T00:52:25.664982525Z`
    - config: structural-upgrade candidate
    - `residualizationBetaMode=EWMA`
    - `residualizationMarketProxyMode=LIQUIDITY_WEIGHTED`
    - `tailWeightingMode=EQUAL_WEIGHT`
    - `fundingOverlayMode=BOUNDED_REINFORCEMENT`
    - `fundingWeight=0.30`
  - live run `generatedAt=2026-03-30T00:54:46.146434226Z`
    - config: exit-overlay variant
    - baseline structure with `exitOverlayMode=TREND_BREAK`

- result
  - baseline run
    - backtest: `220 trades`, `-5.8107 edge bps`, `-3.1439 calmar`
    - forward: `11 trades`, `1.9818 edge bps`, `2448.3848 calmar`
  - structural-upgrade run
    - backtest: `386 trades`, `-1.9663 edge bps`, `-1.7410 calmar`
    - forward: `7 trades`, `7.6353 edge bps`, `2763.2133 calmar`
    - bounded funding overlay multipliers were live and nontrivial: sample `[0.8545, 1.1, 1.1273, 0.8, 1.0818]`
  - exit-overlay run
    - backtest: `138 trades`, `-8.3229 edge bps`, `-3.1204 calmar`
    - forward: `12 trades`, `1.4117 edge bps`, `2149.5168 calmar`
    - sampled recent trade reasons remained ordinary rebalance exits, so `TREND_BREAK` did not show a clear improvement on this pass

- remaining risk
  - none of the tested runs are promotable because backtest edge remains negative after costs
  - structural upgrades improved the backtest/forward trade-off materially versus baseline but did not clear the search/promotion standard
  - forward samples are still too small to treat the improved structural variant as deployable alpha
  - exit overlay is not yet earning its complexity

- next step
  - continue model improvement and threshold calibration around the upgraded structural region
  - prioritize targeted search over:
    - `residualizationBetaMode in {SIMPLE, EWMA}`
    - `residualizationMarketProxyMode in {EQUAL_WEIGHT, LIQUIDITY_WEIGHTED}`
    - `tailWeightingMode in {VOLATILITY_SCALED, EQUAL_WEIGHT}`
    - `fundingOverlayMode in {LINEAR_FACTOR, BOUNDED_REINFORCEMENT}`
    - `factorLookbackDays in {18,19,20,21}`
  - hold exit-overlay expansion until the core daily surface stops failing backtest edge
