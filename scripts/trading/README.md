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
