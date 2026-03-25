# Alpha Readiness Preflight

## Hypothesis

The new readiness preflight should separate real alpha blockers from background noise before any substantial search run starts.

## Experiment

- Added `scripts/trading/alpha_readiness_latium.sh`.
- Ran it against `latium.local` for `hyperliquid_mainnet`.
- Queried:
  - compose service health
  - `/api/v1/data-health/summary`
  - `/api/v1/data-health/issues`
  - `/api/v1/alpha/cross-sectional/cache/status`

## Result

- Service reachability is fine:
  - `alpha-analytics-service` healthy
  - `postgres` healthy
  - `tx-gateway` healthy
- The research platform is still blocked for serious alpha discovery:
  - tracked symbols: `191`
  - active symbols: `190`
  - healthy symbols: `0`
  - degraded symbols: `159`
  - critical symbols: `31`
  - coverage fail symbols: `190`
  - finalized fail symbols: `180`
  - execution fail symbols: `10`
- Sample critical failures:
  - `BOME`: candle lag `4391s`, feature lag `4391s`, orderbook lag `139s`
  - `HMSTR`: candle lag `3851s`, feature lag `3851s`, orderbook lag `154s`
  - `IOTA`: candle lag `1991s`, feature lag `1991s`, orderbook lag `139s`
  - `XAI`: candle lag `1151s`, feature lag `1151s`, orderbook lag `127s`
  - `RSR`: candle lag `971s`, feature lag `971s`, orderbook lag `142s`
- The RAM snapshot cache is not the bottleneck right now:
  - enabled
  - `280` hits
  - `6` misses
  - `6` reloads
  - last load `864ms`
  - no cache error

## Remaining Risk

- If the engine keeps searching on this state, it will waste compute and produce misleading results.
- The current blocker is not search breadth. It is stale and insufficiently finalized feature coverage across the active universe.
- Until this is fixed, higher-level alpha evaluation will be contaminated by data-plane failures.

## Next Step

1. Treat ingestion and feature freshness as the immediate blocker, not search logic.
2. Fix the specific causes of stale candle and feature lag on the affected symbols.
3. Re-run `scripts/trading/alpha_readiness_latium.sh` after each fix until the verdict flips from `BLOCKED` to `READY`.
4. Resume substantive alpha discovery only after the preflight passes.
