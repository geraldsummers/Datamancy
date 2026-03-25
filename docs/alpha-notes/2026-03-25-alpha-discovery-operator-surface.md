# Alpha Discovery Operator Surface

## Hypothesis

Alpha discovery moves faster when the operator surface is standardized: preflight, config resolution, remote execution, artifact capture, and result summaries should all be one command path rather than manual shell work.

## Experiment

- Added `scripts/trading/alpha_discovery_latium.sh`.
- Kept readiness preflight as the default gate.
- Wired remote default-config fetch, override merging, optional cache warm, remote execution, and local artifact capture.
- Smoke-tested both `search` and `run` modes on `latium.local` in `--dry-run` mode.

## Result

- Both discovery entrypoints now have a stable operator wrapper:
  - `scripts/trading/alpha_discovery_latium.sh search`
  - `scripts/trading/alpha_discovery_latium.sh run`
- The wrapper:
  - checks readiness unless explicitly skipped
  - can override readiness with `--allow-blocked`
  - fetches live defaults from `alpha-analytics-service`
  - merges local JSON overrides
  - optionally warms the cross-sectional RAM cache
  - saves `default.json`, `request.json`, optional `cache-warm.json`, `response.json`, and `summary.txt` under `.tmp/alpha-discovery`
- Smoke-test artifacts were produced successfully for both modes:
  - `.tmp/alpha-discovery/20260325T121139Z-search-smoke`
  - `.tmp/alpha-discovery/20260325T121154Z-run-smoke`
- The operator surface is ready, but the platform is still blocked on data freshness and finalized coverage, not on execution tooling.

## Remaining Risk

- The wrapper does not solve stale `research_features_1m`.
- A full live run will still be misleading until readiness flips from `BLOCKED` to `READY`.

## Next Step

1. Use the wrapper for all future alpha search and analysis runs.
2. Fix ingestion / materialization issues until readiness passes without `--allow-blocked`.
3. Only then resume substantive alpha discovery runs.
