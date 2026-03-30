# 2026-03-30 Correlation-Compression Diagnostic

- readiness
  - `2026-03-30T06:41:12.747333245Z`
  - `READY`
  - `eligible=148`
  - `critical=0`
  - `coverage_fail=0`
  - `execution_fail=0`
  - `live_sparse=27`

- research basis
  - `deep-research-report3.md`
  - recommendation under test:
    - new-entry correlation-compression penalty
    - diagnose transport first before implementing any live behavior change

- hypothesis
  - high residual correlation compression should predict worse next-`72h` factor drift on the accepted daily survivor
  - `PC1Share` should be more stable than tail-only `CoMom` because the live traded tail is too narrow

- experiment
  - diagnostic run on accepted daily survivor at `2026-03-30T06:43:29.843039757Z`
  - survivor kept unchanged:
    - `signalBarMinutes=1440`
    - `forwardHours=72`
    - `factorLookbackDays=18`
    - `selectionQuantile=0.025`
    - `residualizationBetaMode=EWMA`
    - `residualizationMarketProxyMode=LIQUIDITY_WEIGHTED`
    - `tailWeightingMode=VOLATILITY_SCALED`
    - `fundingOverlayMode=LINEAR_FACTOR`
    - `fundingWeight=0.15`
    - `fast/medium/slow=3/7/14`
    - `regressionDays=14`
    - `volatilityDays=10`
    - `slopeWeight=0.25`
  - engine behavior unchanged
  - inspection-only diagnostics added:
    - `PC1Share` on residual returns across broader winner/loser sleeves
    - broader-sleeve `CoMom`
    - windows tested: `7d`, `10d`, `14d`
    - sleeve sizes tested per side: `10`, `12`, `16`
  - sample:
    - `35` rebalance dates
    - `315` diagnostic observations

- result
  - baseline survivor unchanged:
    - backtest: `1.5940 edge bps`
    - forward: `1.4867 edge bps`
    - `market_trend=flat`: `-5.5040 edge bps`
  - `PC1Share` shows provisional transport in a neighborhood:
    - top-quintile `PC1ShareZ` minus bottom-quintile next-`72h` factor return:
      - `10d x 10` sleeves: `-186.4 bps`
      - `10d x 12` sleeves: `-174.2 bps`
      - `14d x 10` sleeves: `-181.8 bps`
      - `14d x 12` sleeves: `-173.7 bps`
    - interpretation:
      - high residual compression states are followed by materially worse factor drift in this `10d-14d` / `10-12` neighborhood
  - broader-sleeve `CoMom` does not transport on this sample:
    - the sign is mostly wrong or unstable
    - do not implement `CoMom` as the first control
  - strict flat-state evidence is still thin:
    - only `2` strict `|marketTrendScore| < 0.15` dates in this run
    - treat this as transition/hazard evidence first, not full flat-slice proof

- remaining risk
  - this is still a diagnostic, not a promoted control
  - the `PC1Share` signal is not clean across every window/sleeve combination
  - the current evidence supports a narrow implementation region, not a broad parameter sweep

- next step
  - implement one new-entry-only `PC1Share` penalty
  - initial region only:
    - window in `10d-14d`
    - sleeve size per side in `10-12`
  - no `CoMom`
  - no hard gate
  - no incumbent changes
