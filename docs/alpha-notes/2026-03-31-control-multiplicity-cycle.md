Timestamp: `2026-03-30T22:03:54.486259078Z`

Research basis:
- `deep-research-report4.md` requires purged / embargoed validation, Deflated Sharpe, and White / SPA style multiplicity control before promotion.
- The locked control branch already had local plateau evidence; the missing question was whether that plateau survives an honest multiplicity gate.

Hypothesis:
- The locked daily control branch (`EMA_RETURN_STACK`, `SIMPLE / LIQUIDITY_WEIGHTED / VOLATILITY_SCALED`, `2/8/16`, `18d`, `q=0.020`) would keep its raw edge and could clear a first promotion-grade multiplicity check against its one-step neighborhood.

Experiment:
- Readiness recheck at `2026-03-30T22:00:02.947619094Z`: `READY`, `eligible=147`, `critical=0`, `coverage_fail=0`, `finalized_fail=0`, `execution_fail=0`.
- Deployed discovery-service multiplicity patch plus Grafana run-surface updates to `latium`.
- Executed one persisted `/run` on the locked control with explicit neighborhood:
  - anchor `2/8/16`
  - neighbors `2/7/16`, `2/9/16`, `2/8/14`, `2/8/18`

Result:
- Raw control metrics reproduced exactly:
  - backtest `3.4514 edge bps`, `184 trades`
  - forward `1.4014 edge bps`, `13 trades`
- Neighborhood stability held:
  - pass ratio `0.75`
  - `2/7/16`: backtest `2.4264`, forward `0.6169`, accepted
  - `2/9/16`: backtest `2.7343`, forward `0.6210`, accepted
  - `2/8/14`: backtest `3.2042`, forward `1.1359`, accepted
  - `2/8/18`: backtest `-1.3397`, forward `1.7154`, rejected
- Promotion gate failed on the first honest multiplicity read:
  - purged fold pass ratio `0.40`
  - deflatedSharpeRatio `-0.7578`
  - White's Reality Check `p=0.4132`
  - `promotionAccepted=false`
- Purged fold detail:
  - fold 1: `0 trades`, `0.0000%`, rejected
  - fold 2: `-0.0232%`, rejected
  - fold 3: `0.4481%`, accepted
  - fold 4: `9.2057%`, accepted
  - fold 5: `-2.5499%`, rejected

Remaining risk:
- The raw control survives, but the current validation sample is too fragile to support promotion.
- The control branch now has verified plateau support without verified multiplicity significance.
- Live discovery defaults remain generic/stale relative to the locked control branch, so operators must keep using explicit request payloads.

Next step:
- Keep the control branch fixed and move to promotion-threshold calibration / more honest sample accumulation on this branch before any new alpha mechanism search.
