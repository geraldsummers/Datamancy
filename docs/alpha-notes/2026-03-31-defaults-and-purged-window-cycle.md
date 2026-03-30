Timestamp: `2026-03-30T22:26:16.316876738Z`

Research basis:
- `deep-research-report4.md` calls for purged / embargoed validation windows with enough observations to make multiplicity statistics meaningful.
- The prior control-plateau multiplicity run failed partly on a weak `5 x 7` daily-fold design and stale live defaults that still pointed operators at generic `240m` / wide-tail settings.

Hypothesis:
- If the validation design was the main blocker, widening purged folds and aligning live defaults to the locked daily control should improve the multiplicity read materially without changing the alpha definition.

Experiment:
- Deployed two operational fixes:
  - live discovery defaults now expose the locked daily control config (`1440`, `q in {0.020,0.021}`, `EMA_RETURN_STACK`, `2/8/16`, `18d`)
  - purged validation now widens daily control folds from `5 x 7` bars to `4 x 10` bars on the available sample
- Readiness recheck at `2026-03-30T22:23:13.834593286Z`: `READY`, `eligible=147`, `critical=0`, `coverage_fail=0`, `finalized_fail=0`, `execution_fail=0`
- Re-executed the same control-plateau `/run` on:
  - anchor `2/8/16`
  - neighbors `2/7/16`, `2/9/16`, `2/8/14`, `2/8/18`

Result:
- Live defaults are now aligned with the operational control branch.
- Raw survivor metrics were unchanged:
  - backtest `3.4514 edge bps`
  - forward `1.4014 edge bps`
- Neighborhood stability was unchanged:
  - pass ratio `0.75`
- The stricter multiplicity read still failed:
  - purged validation reduced to `4` folds
  - validation sample count `40`
  - purged fold pass ratio `0.50`
  - deflatedSharpeRatio `-1.0370`
  - White's Reality Check `p=0.9381`
  - `promotionAccepted=false`
- Fold detail:
  - fold 1: `10 bars`, `0 trades`, rejected
  - fold 2: `10 bars`, `19 trades`, `2.0676%`, accepted
  - fold 3: `10 bars`, `64 trades`, `2.9547%`, accepted
  - fold 4: `10 bars`, `67 trades`, `-4.8242%`, rejected

Remaining risk:
- The daily control branch is robust as a raw local survivor but still does not have promotion-grade validation evidence.
- Even after widening folds, the earliest purged slice still has `0` trades, which means the current historical window is not giving a clean, evenly informative promotion sample.
- This branch should not be treated as near-promotion until the multiplicity gate changes materially.

Next step:
- Do not broaden search next cycle.
- Either accumulate more honest forward / purged sample on the locked branch or set an explicit kill condition if repeated reruns keep failing the multiplicity gate.
