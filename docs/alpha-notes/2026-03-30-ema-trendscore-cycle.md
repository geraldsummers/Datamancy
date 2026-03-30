Timestamp: `2026-03-30T12:00:20Z`

Research basis:
- `deep-research-report4.md` supports explicit trend-definition tests for daily cross-sectional momentum using moving-average stabilizers, vol normalization, and honest overlay separation.

Hypothesis:
- The legacy daily score is hiding a cleaner transportable trend stack.
- EMA-distance trend shape with slower volatility normalization may outperform the legacy blend after costs.
- Funding should remain additive on the improved EMA geometry if the effect is real.

Experiment:
- Readiness recheck on `latium` at `2026-03-30T11:21:00.579898843Z`: `READY`, `eligible=147`, `critical=0`, `coverage_fail=0`, `finalized_fail=0`, `execution_fail=0`, `live_sparse=31`.
- Trend-score-mode matrix on the daily survivor:
  - `LEGACY`: backtest `0.8605`, forward `0.0419`, rejected
  - `VOL_NORM_RETURN_STACK`: backtest `1.1393`, forward `-0.3389`, rejected
  - `REGRESSION_TSTAT`: backtest `-0.1593`, forward `-1.4994`, rejected
  - `VOL_NORM_PLUS_TSTAT`: backtest `1.0295`, forward `-2.9056`, rejected
  - `EMA_RETURN_STACK`: backtest `2.3660`, forward `0.1317`, accepted
- EMA neighborhood and plateau follow-up:
  - `3/7/14 vol10`: backtest `2.3660`, forward `0.1317`, accepted
  - `3/7/14 vol14`: backtest `2.1709`, forward `0.5879`, accepted
  - `3/7/16 vol14`: backtest `2.4545`, forward `0.5888`, flat `6.1618`, accepted
  - `2/8/14 vol14`: backtest `1.9458`, forward `1.1673`, accepted
  - `2/8/16 vol14`: backtest `2.0662`, forward `1.4078`, flat `3.1536`, accepted
- Funding retest on `2/8/16 vol14`:
  - `LINEAR_FACTOR 0.15`: backtest `2.0662`, forward `1.4078`, accepted
  - `NONE 0.0`: backtest `1.2283`, forward `0.2105`, rejected
  - `LINEAR_FACTOR 0.30`: backtest `2.1756`, forward `1.2673`, accepted
  - `BOUNDED_REINFORCEMENT 0.15`: backtest `0.8536`, forward `0.4360`, rejected
  - `BOUNDED_REINFORCEMENT 0.30`: backtest `0.5391`, forward `0.7292`, rejected

Result:
- Current strongest surviving daily family is now `EMA_RETURN_STACK`, not the old legacy blend.
- Current strongest surviving parameter region:
  - `signalBarMinutes=1440`
  - `forwardHours=72`
  - `factorLookbackDays=18`
  - `residualizationMode=MARKET`
  - `residualizationBetaMode=EWMA`
  - `residualizationMarketProxyMode=LIQUIDITY_WEIGHTED`
  - `selectionQuantile=0.025`
  - `tailWeightingMode=VOLATILITY_SCALED`
  - `trendScoreMode=EMA_RETURN_STACK`
  - `fastTrendDays=2`
  - `mediumTrendDays=8`
  - `slowTrendDays=16`
  - `volatilityDays=14`
  - `fundingWeight=0.15`
  - `fundingOverlayMode=LINEAR_FACTOR`
  - `openInterestWeight=0.0`
  - `exitOverlayMode=TRAILING_AND_TAKE_PROFIT`
  - backtest `2.0662 edge bps`, `206 trades`, calmar `7.8992`
  - forward `1.4078 edge bps`, `13 trades`
  - `market_trend=flat = 3.1536 edge bps`, `19 trades`
- Strong nearest neighbor support exists at `3/7/16 vol14` and `2/8/14 vol14`, so this looks like a pocket rather than a single-point accident.

Remaining risk:
- Forward sample is still small.
- Best-forward neighbor and best-flat neighbor are not the same point.
- Search acceptance is now present, but promotion evidence is still below the bar for live capital.

Next step:
- Keep the EMA family fixed and run a narrow promotion-readiness cycle around the `2/8/16 vol14` pocket:
  - `selectionQuantile` neighborhood
  - hold vs entry threshold calibration
  - multiplicity-aware validation / plateau confirmation
