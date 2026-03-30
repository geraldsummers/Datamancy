Timestamp: `2026-03-30T17:23:52Z`

Research basis:
- `deep-research-report4.md` supports narrow-tail portfolio construction, residualized daily trend ranking, and honest separation of alpha-definition changes from threshold/overlay tuning.
- `deep-research-report4.md` and the multiplicity brief support local plateau confirmation over single-point optimization.

Hypothesis:
- The new EMA daily winner can be improved materially by narrower tail selection without changing execution assumptions.
- If the improvement is real, it should survive nearby EMA geometries and the `18d/19d/20d/21d` residualization-window battleground.

Experiment:
- Readiness recheck at `2026-03-30T16:11:13.661702088Z`: `READY`, `eligible=148`, `critical=0`, `coverage_fail=0`, `finalized_fail=0`, `execution_fail=0`, `live_sparse=27`.
- Promotion-readiness sweep on `2/8/16`, `vol=14`, `18d`, `LINEAR_FACTOR 0.15`:
  - control `q=0.025`: backtest `2.0662`, forward `1.4078`, `flat=3.1536`, accepted
  - `q=0.020`: backtest `2.9955`, forward `1.4067`, `flat=5.0705`, accepted
  - `q=0.030`: backtest `1.8181`, forward `1.4078`, accepted
  - `q=0.035`: backtest `1.6215`, forward `1.1255`, accepted
  - `entryEdgeFloorBps in {0.75,1.25}`: identical to control
  - `holdEdgeFloorBps in {0.15,0.35}`: identical to control
- Quantile plateau follow-up:
  - `2/8/16`, `q=0.020`: backtest `2.9955`, forward `1.4067`, `flat=5.0705`, accepted
  - `3/7/16`, `q=0.020`: backtest `2.6699`, forward `0.5888`, `flat=5.0431`, accepted
  - `2/8/14`, `q=0.020`: backtest `2.8033`, forward `1.1673`, `flat=-3.7245`, accepted
  - `2/7/16`, `q=0.020`: backtest `3.3354`, forward `0.5562`, `flat=10.0158`, accepted
  - `3/7/14`, `q=0.020`: backtest `2.6525`, forward `0.6372`, `flat=-1.7064`, accepted
- Factor battleground on `2/8/16`, `q=0.020`:
  - `18d`: backtest `2.9955`, forward `1.4067`, `flat=5.0705`
  - `19d`: backtest `2.3175`, forward `1.1382`, `flat=3.2486`
  - `20d`: backtest `2.7923`, forward `1.1554`, `flat=5.9925`
  - `21d`: backtest `2.6340`, forward `1.2777`, `flat=5.9907`
- Fine quantile boundary on the best point:
  - `q in {0.0175,0.0180,0.0200,0.0210}`: identical stronger set
  - `q in {0.0220,0.0225,0.0240,0.0250}`: identical weaker prior set

Result:
- Strongest surviving strategy family remains daily interday residual trend, now defined by `EMA_RETURN_STACK`.
- Strongest surviving parameter region is now:
  - `signalBarMinutes=1440`
  - `forwardHours=72`
  - `factorLookbackDays=18`
  - `residualizationMode=MARKET`
  - `residualizationBetaMode=EWMA`
  - `residualizationMarketProxyMode=LIQUIDITY_WEIGHTED`
  - `selectionQuantile in [0.0175,0.0210]`
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
  - headline measured point:
    - backtest `2.9955 edge bps`, `180 trades`, calmar `10.3376`
    - forward `1.4067 edge bps`, `13 trades`
    - `market_trend=flat = 5.0705 edge bps`, `15 trades`
- The improved point is supported by nearby accepted geometries, but none beat it on the combined backtest/forward/flat balance.

Remaining risk:
- Forward sample is still small.
- The quantile lift is driven by a discrete portfolio-membership boundary between `0.0210` and `0.0220`; this needs to be treated as a real step, not a smooth optimum.
- Some neighbors show higher backtest or higher forward alone, but fail the full profile or collapse in backtest.

Next step:
- Keep the alpha definition fixed and move to multiplicity-aware confirmation / promotion-readiness evidence on the locked region:
  - same-window neighborhood stability summary
  - prior-window threshold calibration
  - if policy tooling allows, multiplicity-aware validation output on the locked plateau
