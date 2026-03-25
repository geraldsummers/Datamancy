# Trading Research Scripts

Single-name alpha proof scripts are deprecated.

- `alpha_proof.py` is disabled as an operator entrypoint.
- `forward_alpha_proof.py` is disabled as an operator entrypoint.
- Cross-sectional research is the only supported alpha discovery surface.

Use these instead:

- `GET /api/v1/alpha/cross-sectional/default-config`
- `POST /api/v1/alpha/cross-sectional/run`
- `GET /api/v1/alpha/cross-sectional/search/default-config`
- `POST /api/v1/alpha/cross-sectional/search/run`

Run them through `alpha-analytics-service` on `latium.local`.

For preflight gating before meaningful research or promotion attempts, use:

- `scripts/trading/alpha_readiness_latium.sh`

This checks:

- `data-health` summary and top issues
- cross-sectional cache status
- a simple `READY` vs `BLOCKED` verdict based on critical symbols, coverage failures, finalized failures, and cache errors

For live remote execution on `latium.local`, use:

- `scripts/trading/alpha_discovery_latium.sh search`
- `scripts/trading/alpha_discovery_latium.sh run`

This wrapper:

- runs readiness preflight by default
- fetches live default configs from `alpha-analytics-service`
- merges local JSON overrides
- optionally warms the RAM cache
- executes the action remotely
- saves timestamped request/response artifacts under `.tmp/alpha-discovery`
- prints a compact summary of the result

Useful examples:

```bash
scripts/trading/alpha_discovery_latium.sh search
scripts/trading/alpha_discovery_latium.sh search --overrides-file .tmp/search-override.json --warm-cache
scripts/trading/alpha_discovery_latium.sh run --overrides-json '{"barMinutes":60,"lookbackHours":720}'
```
