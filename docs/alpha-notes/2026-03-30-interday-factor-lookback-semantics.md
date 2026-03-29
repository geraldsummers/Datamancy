# Interday Factor Lookback Semantics

## Hypothesis

The current `1d / 72h` residual-trend search is misreading the true factor-lookback plateau because the engine may be clamping `factorLookbackDays` above the requested daily window.

## Experiment

- Verified live readiness on `latium.local` after the targeted discovery deploy:
  - `2026-03-29T23:27:23.992380175Z`
  - verdict `READY`
  - `eligible=148`
  - `critical=0`
  - `coverage_fail=0`
  - `finalized_fail=0`
  - `execution_fail=0`
- Reproduced the pre-fix daily residual-trend baseline on live discovery:
  - `signalBarMinutes=1440`
  - `forwardHours=72`
  - `rebalanceCadenceHours=24`
  - `selectionQuantile=0.025`
  - `fast/medium/slow=3/7/14`
  - `regressionDays=14`
  - `residualizationMode=MARKET`
  - `fundingWeight=0.30`
  - `openInterestWeight=0.00`
- Ran a midpoint search on live discovery and observed that `factorLookbackDays=14`, `16`, and `18` produced identical results, while `21` changed the behavior.
- Fixed the residualization lookback clamp so the structural factor window now honors the requested daily lookback once the true minimum feature history is satisfied.
- Added a regression test for daily `factorLookbackDays=14/16/18/21`.
- Deployed the patched `alpha-discovery-service` only and reran the live factor-lookback sweep on `latium.local`.

## Result

- The old “`factorLookbackDays=14`” result was not actually `14d`. It was silently using an effective `19`-bar lookback because the engine was clamping to `indicators.requiredBars`.
- After the fix, the true daily residualization surface is:
  - `14d`: weak, backtest edge `0.0978` bps, forward edge `0.1129` bps
  - `16d`: bad, backtest edge `0.1861` bps, forward edge `-0.3364` bps
  - `18d`: strongest forward pocket, backtest edge `1.6883` bps, forward edge `2.5518` bps
  - `19d`: matches the old mislabeled survivor, backtest edge `1.5868` bps, forward edge `2.6388` bps
  - `21d`: only region clearing the current backtest gate, backtest edge `2.0400` bps, forward edge `0.1009` bps
- Funding remains a second-order overlay on this pocket. The main regime change is the actual factor-lookback length, not the funding weight.
- The current search gate prefers `21d` because it crosses `2.0` bps backtest edge, but that same region materially weakens forward edge versus `18d` and `19d`.

## Remaining Risk

- No tested region is clearly promotable yet:
  - `18d/19d` keep the stronger forward behavior but fail the current backtest gate.
  - `21d` clears the gate but looks weak as deployable money because forward edge drops toward flat.
- The old empirical note that “`14d` was best” is no longer trustworthy. The real result was a hidden `19d` residualization window.

## Next Step

1. Calibrate the backtest gate against the now-correct `18d/19d/21d` plateau instead of treating `2.0` bps as automatically correct.
2. Keep the core daily `3/7/14` trend stack fixed while testing whether selection-tail or validation-rule changes can separate the strong-forward `18d/19d` pocket from the weak-forward `21d` pocket.
3. Do not promote the current `21d` survivor to live trading without a stricter threshold-calibration pass.
