# Medium-Horizon Cross-Sectional Discovery

## Hypothesis

Medium-term cross-sectional trend and reversion edges should survive full execution costs if the engine stops treating trend as only a fast residual-momentum problem and instead scores residual persistence, pullback, and exhaustion across a wider horizon stack.

## Experiment

- Added medium-horizon residual trend state to the cross-sectional feature engine:
  - `residualMomMedium`
  - `residualMomLong`
  - `mediumTrendScore`
  - `trendPersistence`
  - `trendPullback`
  - `trendExhaustion`
- Reweighted trend and reversion scoring/gating around those features.
- Added explicit medium-term trend and reversion search seeds.
- Deployed the updated `alpha-analytics-service` to `latium.local`.
- Ran the medium-term trend search in `/tmp/cross_search_medium_trend.json`.
- Ran the medium-term reversion search in `/tmp/cross_search_medium_reversion.json`.
- Ran direct neighborhood probes around the top trend and reversion leads.

## Result

- Medium-term trend now has one real surviving region after cost:
  - `30m`
  - `lookbackHours=720`
  - `forwardHours=24`
  - `trendLookbackBars=12`
  - `trendSlowBars=36`
  - `trendHoldBars=2`
  - `topPerSide=10`
  - `maxSymbols=72`
  - `trendEntryScore=0.8`
  - `trendTakeProfitVolMultiple=2.0`
  - `trendTrailingStopVolMultiple=0.0` or `0.75`
  - backtest: `6` trades, `+0.1244%`, `+2.153 bps`, fill ratio `0.5077`
  - forward: `7` trades, `+0.2560%`, `+3.6957 bps`, fill ratio `0.7584`
- The trend region is narrow, not broad:
  - removing take-profit flips backtest negative
  - increasing hold from `2` to `3` or `4` destroys both backtest and forward
  - cutting `topPerSide` from `10` to `6` destroys forward
  - changing `trendSlowBars` from `36` to `48` turns both windows negative
  - widening `maxSymbols` from `72` to `96` improves backtest edge to `+9.4153 bps`, but drops backtest trades to `5`
- Medium-term reversion is stronger than trend on the current dataset:
  - `60m`
  - `lookbackHours=720`
  - `forwardHours=24`
  - `reversionLookbackBars=8`
  - `reversionHoldBars=2`
  - `topPerSide=6`
  - `maxSymbols=96`
  - `reversionZEntry=1.2`
  - `reversionZExit=0.2`
  - `reversionMaxContinuationPressure=0.32`
  - `reversionTrailingStopVolMultiple=0.75`
  - `reversionTakeProfitVolMultiple=0.0`
  - backtest: `11` trades, `+1.3802%`, `+12.7957 bps`, fill ratio `0.4891`
  - forward: `6` trades, `+1.0855%`, `+18.1689 bps`, fill ratio `0.6641`
- The reversion region is materially more stable than trend:
  - `maxConcurrentPositions=8` vs `12` is unchanged
  - `reversionTrailingStopVolMultiple=0.0` vs `0.75` is unchanged
  - `maxVolRegime=3.0` vs `5.0` is unchanged on the currently reachable sample
  - `reversionMaxContinuationPressure=0.24` weakens backtest edge to `+10.5856 bps`
  - `reversionZEntry=1.0` increases trades but weakens both windows
  - `reversionTakeProfitVolMultiple=0.5` weakens backtest edge to `+7.3463 bps`
  - `maxSymbols=72` collapses backtest edge to `+1.1323 bps`
  - a balanced risk profile with `maxConcurrentPositions=8`, `maxConcurrentLongs=4`, `maxConcurrentShorts=4`, `maxNetExposureFraction=0.5` improves backtest to `+1.8087%` and `+18.2845 bps` while keeping forward unchanged
- Universe breadth is no longer the blocker:
  - the trend probe scanned `151` candidate Hyperliquid symbols and selected `74`
  - the reversion probe scanned `162` candidate Hyperliquid symbols and selected `98`
- Effective history depth is now the blocker:
  - the `30m` trend probe only loaded `5240` feature rows total
  - core symbols had `254` rows each, which is only about `127` hours at `30m`
  - many selected symbols still only had `14` rows
  - changing `lookbackHours` from `720` to `1080` to `1440` produced identical bar counts and identical metrics for both trend and reversion anchors

## Remaining Risk

- The trend lead is still too sparse and too thin to promote.
- The reversion lead is stronger, but still not promotable while stress-regime losses remain large and the forward sample is this small.
- Apparent stability across `720/1080/1440` is not real horizon robustness yet; the effective loaded history is capped upstream by shallow aggregated data availability.
- Search and run still allow leads with positive realized edge but weak expected-net-edge calibration.

## Next Step

1. Increase effective aggregated history depth for the selected universe so `30m` and `60m` runs can actually falsify `720+` hour hypotheses.
2. Tighten promotion filters around positive expected net edge and stress-regime failure, not only realized edge.
3. Keep the `30m` trend family in hold and only revisit it after deeper history lands.
4. Extend the `60m` reversion region into longer walk-forward or paper windows once deeper history is available.
