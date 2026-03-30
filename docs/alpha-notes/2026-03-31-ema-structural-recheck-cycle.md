Timestamp: `2026-03-30T18:07:02Z`

Research basis:
- `deep-research-report4.md` supports retesting structural controls when the alpha definition changes materially.
- The current cycle changed the daily alpha definition and tail boundary enough that prior structural winners should be treated as hypotheses, not facts.

Hypothesis:
- The old structural winner `EWMA / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED` may no longer be optimal on the improved EMA daily pocket with `q in [0.0175,0.0210]`.

Experiment:
- Structural matrix on the locked EMA winner:
  - fixed alpha core:
    - `trendScoreMode=EMA_RETURN_STACK`
    - `factorLookbackDays=18`
    - `selectionQuantile=0.021`
    - `fast/medium/slow=2/8/16`
    - `volatilityDays=14`
    - `fundingOverlayMode=LINEAR_FACTOR`
    - `fundingWeight=0.15`
  - tested:
    - `residualizationBetaMode in {EWMA, SIMPLE}`
    - `residualizationMarketProxyMode in {LIQUIDITY_WEIGHTED, EQUAL_WEIGHT}`
    - `tailWeightingMode in {VOLATILITY_SCALED, EQUAL_WEIGHT}`
- Key results:
  - control `EWMA / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED`:
    - backtest `2.9955`, forward `1.4067`, `flat=5.0705`, accepted
  - `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED`:
    - backtest `3.4514`, forward `1.4014`, `flat=12.3508`, accepted
  - `EWMA / EQUAL_WEIGHT / VOLATILITY_SCALED`:
    - backtest `3.5389`, forward `1.3503`, `flat=10.7638`, accepted
  - `EWMA / EQUAL_WEIGHT / EQUAL_WEIGHT`:
    - backtest `2.8125`, forward `3.8842`, `flat=10.6741`, accepted
  - `SIMPLE / EQUAL_WEIGHT / EQUAL_WEIGHT`:
    - backtest `1.8202`, forward `3.2197`, `flat=10.6874`, accepted
- Funding retest on the strongest forward challenger `EWMA / EQUAL_WEIGHT / EQUAL_WEIGHT`:
  - `LINEAR_FACTOR 0.15`: backtest `2.8125`, forward `3.8842`, `flat=10.6741`
  - `NONE 0.0`: backtest `2.1693`, forward `2.9190`, `flat=2.9315`
  - `LINEAR_FACTOR 0.30`: backtest `2.6361`, forward `3.8828`, `flat=10.6741`
  - `BOUNDED_REINFORCEMENT 0.15`: backtest `1.9699`, forward `4.0024`, `flat=3.4178`
- Funding retest on the strongest backtest challenger `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED`:
  - `LINEAR_FACTOR 0.15`: backtest `3.4514`, forward `1.4014`, `flat=12.3508`
  - `NONE 0.0`: backtest `2.9680`, forward `6.0247`, `4 trades`, `flat=11.9569`
  - `LINEAR_FACTOR 0.30`: backtest `3.3829`, forward `1.4792`, `flat=12.2048`
  - `BOUNDED_REINFORCEMENT 0.15`: backtest `2.6231`, forward `7.3450`, `4 trades`, `flat=14.0781`

Result:
- The old structural control is no longer the default winner.
- Current best-balanced live point is:
  - `residualizationBetaMode=EWMA`
  - `residualizationMarketProxyMode=EQUAL_WEIGHT`
  - `tailWeightingMode=EQUAL_WEIGHT`
  - `fundingOverlayMode=LINEAR_FACTOR`
  - `fundingWeight=0.15`
  - with the locked EMA core:
    - `factorLookbackDays=18`
    - `selectionQuantile in [0.0175,0.0210]`
    - `fast/medium/slow=2/8/16`
    - `volatilityDays=14`
  - measured result:
    - backtest `2.8125 edge bps`, `194 trades`, calmar `8.5248`
    - forward `3.8842 edge bps`, `14 trades`
    - `market_trend=flat = 10.6741 edge bps`, `15 trades`
- Strongest backtest-first alternative remains:
  - `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED / LINEAR_FACTOR 0.15`
  - backtest `3.4514`, forward `1.4014`, `flat=12.3508`

Remaining risk:
- Forward samples are still small enough that the large-forward branches need caution.
- Some alternative funding settings produce eye-catching forward numbers on only `4` trades; do not overread them.
- The two leading branches are now close enough in different dimensions that a promotion decision still needs a robustness-first view, not a single-metric winner.

Next step:
- Run a direct neighborhood stability check around the two surviving structural leaders only:
  - current best-balanced lead:
    - `EWMA / EQUAL_WEIGHT / EQUAL_WEIGHT / LINEAR_FACTOR 0.15`
  - strongest backtest-first alternative:
    - `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED / LINEAR_FACTOR 0.15`
