# Cross-Sectional Exit Overlay Probe

## Hypothesis

Adding volatility-scaled trailing stops and take-profit exits to the cross-sectional engine, while expanding discovery to shorter bar sizes, will surface post-cost alpha that was previously hidden by static signal-only exits.

## Experiment

- Implemented volatility-scaled trailing-stop and take-profit overlays in the cross-sectional simulator.
- Expanded the default search space down to `5m`, `15m`, and `30m` horizons.
- Deployed the updated `alpha-analytics-service` to `latium.local`.
- Ran full-universe permissive scans on Hyperliquid across `5m`, `15m`, `30m`, and `60m`.
- Ran direct `5m` exit overlay comparisons:
  - baseline
  - reversion trailing stop
  - trend take-profit
  - combined
- Ran a constrained search over `5m/15m/30m`.
- Ran a focused `5m` grid across hold bars, forward windows, top-per-side settings, and exit overlays.

## Result

- Breadth is materially better. The engine now scans `165` candidate symbols and selects `98` tradable names in permissive full-universe runs.
- `60m` remains dead on the current dataset with `0` liquid rows and `0` seeds.
- `5m`, `15m`, and `30m` now produce real seeds and backtest trades under permissive settings.
- `5m` is the only horizon that produced any forward trades in this round.
- Reversion trailing stops improved `5m` backtest loss from `-1.953%` to `-1.2337%`, but did not create a real survivor.
- Trend take-profit improved `5m` trend backtest from `-0.6873%` to `+0.1817%`, but trend still had `0` forward trades in the same probe.
- The focused `5m` grid found no trend configs with positive backtest and positive forward together.
- The only reversion configs that cleared the naive positive-backtest/positive-forward bar were effectively the same single-trade path repeated in both windows:
  - `5m`
  - `lookbackHours=240 or 360`
  - `forwardHours=24`
  - `reversionHoldBars=2`
  - `topPerSide=4`
  - realized edge `+0.5192 bps`
  - realized return `+0.0052%`
  - sample size `1` backtest trade and `1` forward trade

## Remaining Risk

- The apparent `5m` reversion survivor is not promotable. It is driven by a single trade and the edge is economically negligible after costs.
- The beam search still undershoots direct-manual improvements because its seed path stayed on baseline exits in the short run.
- `60m+` horizons still fail earlier than exits matter, which points back to liquidity gating and/or feature quality at slower aggregates.

## Next Step

1. Seed the search engine with explicit short-horizon exit-overlay seeds instead of relying on local mutation from a single baseline.
2. Persist the direct-manual grid results so the dashboard can rank short-horizon variants by backtest/forward drift, not just search outputs.
3. Investigate why `60m` has `0` liquid rows on the selected universe despite broad symbol coverage.
4. Accumulate larger `5m` forward windows or paper-forward samples before treating any short-horizon reversion pocket as real alpha.
