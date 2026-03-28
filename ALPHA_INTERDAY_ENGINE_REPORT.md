# Alpha Interday Engine Report

Generated: `2026-03-28T01:49Z`

## Executive Summary

We are no longer blocked on the original feature-materializer starvation bug. The materializer is now running `historical_catchup` after recent gap repair, and the live stack is fresh enough for bounded alpha search on Hyperliquid.

The important change this session is that interday search is now constrained to *feasible* horizons. The previous `60m / 144h / 72h+` runs were not genuine alpha failures. They were impossible engine requests because `warmup + forward > loaded lookback`, which left the calibration window inside the warmup dead zone and silently produced zero trades. I added a config feasibility gate so those combinations are rejected instead of being misread as strategy failure.

After that fix, a bounded interday trend region produced real after-cost trades:

- Best surviving family: `cross_section_beta_trend_v1`
- Best verified region: `60m` bars, `144h` lookback, `24h` forward, `72` beta bars, `96` slow trend bars, `144/192` medium/long trend bars, `48h` hold, scaled exposure `0.15 -> 1.25`
- Verified result: `11` backtest trades and `4` forward trades in search fitness, with positive after-cost forward performance
- Current status: **hold / continue forward paper**, not promote

Cross-sectional reversion remains dead in the tested interday region. It generated either zero seeds or zero executed trades after full gating.

## What Changed

### Already live before this report

- Interday detection/holding/execution separation in the cross-sectional engine
- Confidence-scaled exposure sizing and rebalance-on-size-change
- Universe-relative reversion state using blended `residualZ` and `residualCrossSectionalZ`
- Feature materializer fix allowing `historical_catchup` after recent-gap repair

### Added this session

Commit: `283d9f6 unblock alpha reject impossible interday windows`

Changes:

- Added `minimumResearchLookbackHours(config)`
- Tightened `isValidResearchConfig(config)` so search rejects configs where:
  - `lookbackHours < warmupHours + forwardHours + 1`
- Unified diagnostics warmup accounting via `researchWarmupBars(config)`
- Added regression test covering the infeasible interday horizon case

Why this matters:

- Old interday searches were mixing valid and impossible configs and reporting zero-trade output as if it were alpha failure.
- Search now rejects structurally impossible configs up front.

## Verified Live Status

### Readiness

Verified readiness sample:

- Timestamp: `2026-03-28T01:48:51.623Z`
- Exchange: `hyperliquid_mainnet`
- `trackedSymbols=168`
- `activeSymbols=163`
- `eligibleSymbols=129`
- `liveSparseSymbols=41`
- `criticalSymbols=1`
- `executionFailSymbols=0`
- Default research readiness: `passed=true`

### Frontiers

Verified on `market-postgres`:

- `candle_1m.latest_raw_time = 2026-03-28T01:48:00Z`
- `trade.latest_raw_time = 2026-03-28T01:48:26.866Z`
- `funding.latest_raw_time = 2026-03-28T01:48:35Z`
- `minute_orderbook_state.max(time) = 2026-03-28T01:48:00Z`
- `minute_orderbook_state.max(last_event_time) = 2026-03-28T01:48:30.286Z`
- `research_features_1m.max(time) = 2026-03-28T01:47:00Z`
- `research_features_1m.max(finalized_time) = 2026-03-28T01:45:00Z`

### Feature coverage floor

Verified feature materialization state:

- `min(earliest_feature_time) = 2026-03-19T11:31:00Z`
- `max(latest_feature_time) = 2026-03-28T01:47:00Z`

Interpretation:

- Live freshness is good enough for bounded interday search.
- Historical feature coverage debt is still real.
- Long interday windows are still blocked by coverage / execution-observation history, not by current live pipeline freshness.

## Readiness vs Blockage

### Not blocking right now

Alpha search is **not blocked** for:

- `60m` bars
- `96h` to `144h` research windows
- `24h` forward window
- the currently selected 18-symbol refined universe

### Still blocking right now

Alpha search is **still blocked** for:

- `60m / 168h+` regions
- `240m` regions
- any config where `warmup + forward > loaded lookback`

Live reasons observed:

1. Historical feature coverage debt
2. Historical execution-observation coverage debt on older windows
3. Prior to the new validation, impossible config geometry causing empty calibration/trading slices

## Live Sparse vs Local Stale vs Historical Debt

### Live sparse markets

These are not local pipeline failures. Example issue rows show:

- `APE`, `MOVE`, `ALT`, `DASH`, `DYM`, `HEMI`, `SYRUP`
- `livenessClass = LIVE_SPARSE`
- orderbook is current while trade/candle channels are quiet

Interpretation:

- These markets are quiet, not necessarily broken.
- They should not be treated the same as stale ingestion.

### Local stale markets

Current critical sample at measurement time:

- `BIGTIME`
- `status = CRITICAL`
- `livenessClass = HEALTHY`
- reason included `candle_1m lag 111s exceeds 90s`

Interpretation:

- This is actual local freshness debt for a small subset of names.
- It is not the primary blocker for the surviving `60m / 144h / 24h` search surface.

### Historical coverage debt

This is the dominant remaining data debt:

- average active coverage ratio is still only `0.5571`
- average active finalized ratio is `0.5569`
- `coverageFailSymbols=163`
- `finalizedFailSymbols=145`

Interpretation:

- The hot path is fresh.
- The long historical research surface is still incomplete.
- This matters for longer interday windows and promotion confidence, but it is not a hard blocker for the bounded surface that currently passes.

## Search Surfaces Tested

### Infeasible interday surfaces

These were previously returning zero-trade artifacts:

- `60m / 144h / 72h`
- `60m / 144h / 96h`
- multiple `240m` variants

Root cause:

- `warmup + forward > lookback`
- calibration and backtest slices ended up in pre-warmup rows
- engine produced zero calibration examples and therefore zero tradable signals

This is now explicitly rejected in search validation.

### Feasible balanced interday search

Artifact:

- `.tmp/alpha-discovery/20260328T014654Z-search-interday-feasible-balanced`

Top surviving config:

- `barMinutes=60`
- `lookbackHours=144`
- `forwardHours=24`
- `betaLookbackBars=72`
- `trendLookbackBars=24`
- `trendSlowBars=96`
- `trendMediumBars=144`
- `trendLongBars=192`
- `trendHoldBars=48`
- `topPerSide=4`
- `trendEntryScore=0.9`
- `maxConcurrentPositions=24`
- exposure range `0.15 -> 1.25`
- rebalance step `0.1`

Trend fitness:

- Backtest: `11` trades, `winRate=0.6364`, `netReturnPct=0.4763`, `avgEdgeAfterCostBps=5.8913`, `avgFillRatio=0.6868`, `maxDrawdownPct=2.3840`
- Forward: `4` trades, `winRate=0.75`, `netReturnPct=3.7031`, `avgEdgeAfterCostBps=94.6368`, `avgFillRatio=0.6595`, `maxDrawdownPct=0.0248`
- Search fitness: `passesFilters=true`, `score=44.7410`

Reversion fitness:

- `0` backtest trades
- `0` forward trades
- failed all minimum sample gates

### Feasible trend-heavy interday search

Artifact:

- `.tmp/alpha-discovery/20260328T014654Z-search-interday-feasible-trend`

Top region:

- same `60m / 144h / 24h` family
- stronger trend structure and longer hold bias

Result:

- Backtest: `8` trades, `netReturnPct=3.0654`, `avgEdgeAfterCostBps=38.9208`
- Forward: `0` trades
- Search fitness: `passesFilters=false`
- Failure reason: `missing_forward`, `forward_trades<3`

Interpretation:

- Stronger trend confirmation increases historical selectivity.
- It over-tightens the current forward slice and fails the minimum forward sample gate.

## Direct Runs

### Best verified balanced run

Artifact:

- `.tmp/alpha-discovery/20260328T014735Z-run-run-interday-feasible-top-balanced`

Diagnostics:

- `bars_loaded=2592`
- `feature_rows=2592`
- `calibration_rows=2142`
- `forward_rows=450`
- `candidate_symbols=18`
- `selected_symbols=18`
- `liquid_rows=432`
- `trend_seeds=111`
- `reversion_seeds=1`

Portfolio/risk profile:

- Backtest max concurrent positions: `5`
- Forward max concurrent positions: `3`
- Backtest max gross exposure: `$23,759.50`
- Forward max gross exposure: `$13,610.00`
- Backtest max absolute net exposure fraction: `0.1980`
- Forward max absolute net exposure fraction: `0.1134`
- No net or beta limit breaches on accepted entries

Backtest robustness:

- `symbolCount=7`
- `largestSymbolTradeShare=0.2727`
- `profitableSymbolShare=0.4286`
- `largestRegimeTradeShare=0.6364`
- worst symbol edge after cost: `-110.4064 bps`
- worst regime edge after cost: `-3.0348 bps`
- stability score: `61.4190`

Forward robustness:

- `symbolCount=4`
- `largestSymbolTradeShare=0.25`
- `profitableSymbolShare=0.75`
- single observed regime slice: `normal`
- stability score: `68.4444`

Interpretation:

- This is a real, after-cost, cross-sectional trend result.
- It is still too thin to promote.
- Backtest breadth is not yet strong enough to claim regime-stable alpha.

### Loose upper-bound diagnostic

Artifact:

- `.tmp/alpha-discovery/20260328T014736Z-run-run-interday-feasible-loose`

Config intent:

- lower entry threshold
- shorter slow trend horizon
- wider signal acceptance
- looser reversion bounds

Result:

- Backtest: `14` trend trades
- Forward: `0` trades
- Backtest net outcome strongly negative
- Backtest worst regime edge after cost: `-228.8445 bps`
- Backtest profitable symbol share: `0.1`
- Forward portfolio stayed flat

Interpretation:

- This is a useful upper-bound diagnostic, not a candidate.
- Loosening the gate increases activity but destroys quality.
- The surviving region is selective for a reason.

## Strongest Surviving Strategy Families

### 1. Cross-sectional residual trend

Status: **survives in a bounded interday region**

What survives:

- `60m` bar cadence
- `144h` detection window
- `24h` forward validation window
- `72-96` bar beta / slow-trend context
- `48h` hold horizon
- gradual exposure scaling up to `1.25x` unit exposure

Why it survives:

- It still trades after full cost modeling.
- It keeps positive forward edge in the best region.
- It does not require absurd portfolio concentration.

Why it is not promotable yet:

- forward sample is only `4` trades
- backtest symbol breadth is mixed
- worst symbol and calm regime slices are still weak

### 2. Cross-sectional residual mean reversion

Status: **kill for current interday surface**

Observed behavior:

- zero backtest trades
- zero forward trades
- often zero seeds even under loose bound settings

Likely reason:

- the current market is not offering enough normalized residual snapback events at this cadence after execution realism and continuation-pressure gating

## Strongest Parameter Region

Best current region:

- detection horizon: `144h` loaded lookback with `24/96/144/192` trend stack
- holding horizon: `48h`
- execution/sizing horizon: gradual rebalance with `0.15 -> 1.25` target exposure and `0.1` rebalance step
- portfolio breadth: up to `24` concurrent positions, though realized usage stayed far lower

This is important:

- the engine is *not* saying “hold 144h”.
- it is saying “use roughly 6 days of history to detect a trend, then hold a trade around 2 days, while sizing gradually as confirmation changes.”

## What Failed and Why

### Failed because of infra / contract geometry

- old interday searches with `72h+` forward windows on `144h` lookback
- reason: impossible warmup/forward geometry
- fix: explicit feasibility validation in search

### Failed because of data coverage debt

- `60m / 168h+`
- `240m` search surfaces
- reason: insufficient historical feature and execution-observation coverage across enough symbols

### Failed because of strategy behavior

- loose trend/reversion diagnostic
- reason: more activity, but edge collapses and forward goes flat

### Failed because of no signal

- interday reversion
- reason: no meaningful surviving reversion seed/trade flow under current normalization, liquidity, and execution realism gates

## Backtest vs Forward vs Paper Drift

Current best trend region:

- Backtest edge after cost: `5.8913 bps`
- Forward edge after cost: `94.6368 bps`
- Backtest net return: `0.4763%`
- Forward net return: `3.7031%`

Interpretation:

- Forward is currently much stronger than backtest.
- This is *not* enough evidence to trust it.
- With only `4` forward trades, this is sample noise until proven otherwise.

Paper/testnet/live status:

- We have forward-style simulated results through the unified engine.
- We have **not** promoted this config to testnet live execution.
- That is correct for now; the sample is too small.

## How Alpha Discovery Works

The current discovery loop is:

1. Check live readiness and data health on `latium.local`
2. Reject configs that violate the no-fallback coverage contract
3. Reject configs that are structurally impossible for the loaded horizon
4. Load the eligible cross-sectional universe from `research_features_1m`
5. Engineer cross-sectional trend and reversion features
6. Generate structural seeds
7. Apply execution realism and calibration gates
8. Simulate backtest and forward slices with the same risk/execution model
9. Rank configs by after-cost outcome, fill quality, drawdown, and robustness

The main search artifact surface is the analytics service, not notebooks.

## How The Risk Model Works

The risk model is portfolio-aware and universe-aware.

It enforces:

- max concurrent positions
- max concurrent longs / shorts
- max net exposure fraction
- max BTC beta exposure
- max ETH beta exposure
- same-symbol open-position exclusion
- rebalance-on-material-size-change only

Selection is not “pick the highest score and yolo”. It is:

- generate candidates
- score candidates by expected net edge plus portfolio balance contribution
- reject additions that violate gross, long/short, net, or beta constraints

In the best surviving run, actual utilization stayed modest even though the configured cap was large.

## How The Execution Model Works

The execution model is explicitly pessimistic relative to naive mid fills.

It includes:

- maker/taker fee mix
- spread half-cost
- slippage
- impact
- adverse selection
- fill ratio degradation from depth/volatility/flow
- additional penalties when execution observation is missing

This is why the loose diagnostic fails. More signals exist, but they do not survive the execution model.

## How Reversion Works Right Now

This is the current normalized bound definition.

Reversion does **not** use absolute token price levels.

It uses:

- `residualZ`: symbol move after beta adjustment
- `residualCrossSectionalZ`: symbol move relative to its comparable universe cohort
- `reversionState`: weighted blend of the two

Current formula directionally:

- `reversionState = (1 - w) * residualZ + w * residualCrossSectionalZ`

Bounds are then dynamic and universe-relative:

- entry bounds come from cross-sectional quantiles plus minimum z-score floors
- exit bounds come from inner quantiles plus tighter z-score caps

That means the engine is already approximating the thing you asked for:

- quantify upper and lower relative bounds
- normalize against the universe / cohort
- look for dislocations outside the symbol’s adjusted universe share

Why it is failing now is not normalization. It is that there is no surviving after-cost reversion surface at the tested interday cadence.

## Promotion, Hold, Kill

### Promote

Nothing should be promoted today.

### Hold

Hold this family for more forward evidence:

- `cross_section_beta_trend_v1`
- region centered on `60m / 144h / 24h`
- moderate entry threshold
- `48h` hold
- scaled exposure up to `1.25`

Why hold, not promote:

- positive after-cost forward result exists
- sample is still too thin
- backtest breadth is not yet robust enough

### Kill

Kill for now:

- interday residual reversion on the tested surfaces
- loose upper-bound trend/reversion region
- trend-heavy region that cannot print forward trades

## Are We Getting Closer To Alpha?

Yes, but with an important caveat.

Closer because:

- we moved from infra-misread zero-trade output to a real after-cost forward-positive trend region
- we now know which interday geometry is feasible on the live stack
- we have a cleaner separation between real alpha failure and invalid engine requests

Not close enough because:

- the best region still has only `4` forward trades
- reversion is still dead
- longer interday horizons are still blocked by historical data debt
- testnet live is still premature

## What Should Happen Next

### Alpha discovery

1. Keep searching around the surviving trend region, not around loose high-activity regions.
2. Focus on `60m` cadence with `24h` forward windows until the sample is bigger.
3. Expand trend detection around `72-96` slow bars and `144-192` medium/long bars.
4. Add more explicit regime slicing around calm vs normal states because calm was the weak backtest regime.

### Risk model

1. Add stronger penalties for calm-regime trend concentration.
2. Track symbol-level contribution caps inside the search fitness, not only portfolio constraints.
3. Keep beta/net exposure constraints strict; they are not the bottleneck right now.

### Data / readiness

1. Let historical feature catchup continue.
2. Recheck `60m / 168h+` and `240m` readiness after more catchup has landed.
3. Do not treat `LIVE_SPARSE` names as local stale failures unless venue sanity says otherwise.

### Promotion ladder

1. Continue forward paper on the best balanced trend config.
2. Require materially more forward trades before testnet live.
3. Only move to Hyperliquid testnet once the forward sample stops being statistically thin.

## Practical Notes

### Hypothesis

Interday cross-sectional trend survives after full costs if we separate detection horizon, holding horizon, and execution sizing horizon.

### Experiment

- deployed interday/scaled-exposure engine
- fixed feature catchup starvation
- added impossible-window validation
- searched feasible `60m / 96-144h / 24h` surfaces
- ran direct top-candidate and loose-bound diagnostics

### Result

- one bounded trend family survives
- reversion does not
- loose activity expansion destroys edge
- longer interday windows remain coverage-blocked

### Remaining risk

- forward sample is still very small
- calm-regime weakness remains
- historical execution-observation debt still blocks longer horizons

### Next step

Keep the best balanced trend config in forward paper and keep searching nearby, while historical feature coverage catches up enough to reopen `168h+` regions.
