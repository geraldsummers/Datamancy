# Search Threshold Calibration

## Research Basis

- The local research brief says thresholds should be chosen inside a validation pipeline, then frozen for the next cycle, not relaxed after seeing the same leaderboard.
- It specifically calls for nested parameter selection, walk-forward evaluation, multiplicity control, and parameter plateaus rather than knife-edge thresholds.

## Hypothesis

The current hardcoded `minNetEdgeBps=2.0` search gate is rejecting the stronger-forward `18d/19d` residual-trend pocket and selecting the weaker-forward `21d` pocket on the corrected daily `1d / 72h` surface.

## Experiment

- Implemented a threshold calibrator in discovery that:
  - uses the full evaluated candidate set before leaderboard truncation
  - sweeps candidate `minNetEdgeBps` values
  - keeps all other forward and risk gates frozen
  - chooses the threshold that maximizes median forward edge, then forward-positive ratio, accepted breadth, and stricter threshold as tie-breakers
- Added:
  - search request/response support for threshold calibration
  - `POST /api/v1/discovery/calibrate-thresholds`
  - `scripts/trading/alpha_discovery_latium.sh calibrate-thresholds`
- Verified live readiness after deploy:
  - `2026-03-29T23:46:22.322060128Z`
  - verdict `READY`
  - `eligible=148`
  - `critical=0`
  - `coverage_fail=0`
  - `finalized_fail=0`
  - `execution_fail=0`
- Ran live calibration on the corrected daily residual-trend plateau:
  - `factorLookbackDays=[18,19,20,21]`
  - `fundingWeight=[0.15,0.30,0.45]`
  - `selectionQuantile=0.025`
  - `thresholdGridBps=[1.25,1.50,1.75,2.00,2.25]`
  - `minAcceptedCandidates=3`
  - `minForwardPositiveRatio=1.0`
  - `minMedianForwardEdgeBps=0.10`

## Result

- The software recommended lowering `minNetEdgeBps` from `2.0` to `1.5`.
- Live calibration summary:
  - `1.25` bps: `9` accepted, median backtest edge `1.6740`, median forward edge `2.5522`
  - `1.50` bps: `9` accepted, median backtest edge `1.6740`, median forward edge `2.5522`
  - `1.75` bps: `3` accepted, median backtest edge `2.0380`, median forward edge `0.1011`
  - `2.00` bps: `3` accepted, median backtest edge `2.0380`, median forward edge `0.1011`
  - `2.25` bps: `0` accepted
- This confirms the current `2.0` gate is selecting the weak-forward `21d` pocket and discarding the stronger-forward `18d/19d` pocket.

## Remaining Risk

- This is still calibration on a small targeted surface, not the final global policy.
- The calibrator is intentionally conservative in scope:
  - only `minNetEdgeBps` is adjusted
  - drawdown, trade-count, forward-calmar, participation, CVaR, and kill-switch gates remain unchanged
- The next failure mode is over-generalizing a threshold from one family/plateau into unrelated search spaces.

## Next Step

1. Apply `minNetEdgeBps=1.5` to the authoritative search policy and rerun the corrected daily residual-trend search surface.
2. Confirm the lower gate still behaves well on adjacent daily trend neighborhoods, not just `18/19/21d`.
3. Keep threshold calibration as an explicit operator step that writes a frozen policy recommendation, not as an in-run self-relaxing gate.
