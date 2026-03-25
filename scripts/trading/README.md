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
