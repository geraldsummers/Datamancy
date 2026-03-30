Timestamp: `2026-03-30T20:39:22Z`

Research basis:
- After the structural recheck, two branches survived with different strengths:
  - branch A: stronger forward on the center point
  - branch B: stronger backtest and flat-slice behavior
- The correct next question was neighborhood stability, not another broad search.

Hypothesis:
- If branch A is real, it should survive one-step geometry perturbations around `2/8/16`.
- If branch B is real, it should retain acceptance across the same local neighborhood and should also keep the narrow-tail preference.

Experiment:
- Fixed shared core:
  - `trendScoreMode=EMA_RETURN_STACK`
  - `factorLookbackDays=18`
  - `volatilityDays=14`
  - `fundingOverlayMode=LINEAR_FACTOR`
  - `fundingWeight=0.15`
- Branch A:
  - `EWMA / EQUAL_WEIGHT / EQUAL_WEIGHT`
  - control `2/8/16`: backtest `2.8125`, forward `3.8842`, `flat=10.6741`
  - `2/7/16`: backtest `0.1293`, rejected
  - `2/9/16`: backtest `0.0420`, rejected
  - `2/8/14`: backtest `1.4306`, rejected
  - `2/8/18`: backtest `-2.2040`, rejected
- Branch B:
  - `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED`
  - control `2/8/16`: backtest `3.4514`, forward `1.4014`, `flat=12.3508`
  - `2/7/16`: backtest `2.4264`, forward `0.6169`, accepted
  - `2/9/16`: backtest `2.7343`, forward `0.6210`, accepted
  - `2/8/14`: backtest `3.2042`, forward `1.1359`, accepted
  - `2/8/18`: backtest `-1.3397`, rejected
- Branch-B quantile sweep:
  - `q=0.020`: backtest `3.4514`, forward `1.4014`, `flat=12.3508`
  - `q=0.021`: identical to `q=0.020`
  - `q=0.022`: backtest `2.7528`, forward `1.4024`, `flat=10.8895`
  - `q=0.025`: identical to `q=0.022`

Result:
- Branch A is not robust enough to operate as the primary control. It is a forward-rich local spike.
- Branch B is the current operational lead because it has real one-step neighborhood support.
- Current operational lead:
  - `residualizationBetaMode=SIMPLE`
  - `residualizationMarketProxyMode=LIQUIDITY_WEIGHTED`
  - `tailWeightingMode=VOLATILITY_SCALED`
  - `fundingOverlayMode=LINEAR_FACTOR`
  - `fundingWeight=0.15`
  - `selectionQuantile in {0.020,0.021}`
  - `fast/medium/slow=2/8/16`
  - `factorLookbackDays=18`
  - `volatilityDays=14`
  - measured point:
    - backtest `3.4514 edge bps`, `184 trades`, calmar `13.5439`
    - forward `1.4014 edge bps`, `13 trades`
    - `market_trend=flat = 12.3508 edge bps`, `16 trades`

Remaining risk:
- Branch B gives up the headline forward spike that made branch A interesting.
- Branch A may still be useful as a diagnostic branch, but not as the control branch.
- `slowTrendDays=18` remains toxic for both branches.

Next step:
- Treat branch B as the locked control and move to final promotion-readiness confirmation on that branch only.
