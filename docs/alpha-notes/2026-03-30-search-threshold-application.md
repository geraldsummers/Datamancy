# Search Threshold Application

## Research Basis

- Thresholds should be calibrated from prior evaluated search windows, then frozen into policy for the next cycle.
- The previous calibration run on the corrected daily residual-trend plateau recommended `minNetEdgeBps=1.5` for search, not `2.0`.

## Hypothesis

Applying the calibrated `1.5` bps search gate to the authoritative trading policy should cause live discovery to rank the stronger-forward `18d/19d` residual-trend pocket ahead of the weaker-forward `21d` pocket.

## Experiment

- Updated the authoritative search-policy default from `2.0` to `1.5` bps.
- Regenerated the trading-policy artifact locally.
- Synced the updated artifact to `latium.local`.
- Restarted `alpha-discovery-service` so it reloaded the mounted policy file.
- Verified the loaded server policy file reports `research.discovery.search.minNetEdgeBps = 1.5`.
- Rechecked readiness:
  - `2026-03-29T23:52:15.725798196Z`
  - verdict `READY`
  - `eligible=148`
  - `critical=0`
  - `coverage_fail=0`
  - `finalized_fail=0`
  - `execution_fail=0`
- Reran the corrected daily search surface:
  - `factorLookbackDays=[18,19,20,21]`
  - `fundingWeight=[0.15,0.30,0.45]`
  - `selectionQuantile=0.025`

## Result

- The live leaderboard flipped to the stronger-forward region:
  - rank 1: `19d`, `fundingWeight=0.15`, backtest `1.5802` bps, forward `2.6465` bps
  - rank 2: `19d`, `fundingWeight=0.30`, backtest `1.5868` bps, forward `2.6387` bps
  - rank 3: `19d`, `fundingWeight=0.45`, backtest `1.5784` bps, forward `2.6372` bps
  - rank 4: `18d`, `fundingWeight=0.30`, backtest `1.6883` bps, forward `2.5522` bps
- The weak-forward `21d` region is still accepted but is now ranked below the stronger-forward `18d/19d` pocket:
  - best `21d` row: backtest `2.0380` bps, forward `0.1049` bps
- `20d` remains dead under the new gate:
  - backtest `1.1715` to `1.2235` bps
  - rejected because `backtest edgeAfterCostBps < 1.5`

## Remaining Risk

- This improves search alignment with forward utility, but it does not yet prove promotion quality.
- The promotion gate is still stricter than the search gate, which is intentional.
- The `18d/19d` pocket still needs broader neighborhood and regime validation before promotion.

## Next Step

1. Run a wider daily residual-trend search around the `18d/19d` pocket now that discovery is ranking it correctly.
2. Check whether the `1.5` bps search gate still behaves well on adjacent daily selection tails and funding overlays.
3. Keep promotion thresholds unchanged until the broader forward-quality evidence holds.
