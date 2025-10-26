# ADR-001: Freshness Rules + Fingerprint-Based Change Tracking

**Status:** Accepted
**Date:** 2025-10-26
**Deciders:** Agent Bootstrap
**Phase:** 0.5 — Docs Automation Bootstrap

## Context

We need **automated gates** to ensure services are both **tested** and **documented** before being considered ready. Manual tracking is error-prone; we need machine-verifiable freshness.

**Requirements:**
1. Detect when a service's **configuration changes** (image, config files, env vars, compose stanza).
2. Track when the last **passing browser test** ran for that service.
3. Track when the service's **Spoke documentation** was last updated.
4. Block CI/deployments if docs or tests are **stale** relative to config changes.

## Decision

We implement **fingerprint-based freshness tracking** via a `docs-indexer` container.

### Fingerprint Calculation

Each service's `CONFIG_FINGERPRINT` is computed from:

```python
CONFIG_FINGERPRINT = sha256(
    image_digest +
    config_dir_hash +  # find configs/service/ -type f | xargs sha256sum | sort | sha256sum
    env_secrets_version +  # sha256 of relevant .env vars + mounted secrets
    compose_stanza  # sha256 of services.<name> block in docker-compose.yml
)
```

### Status Determination

A service is:

**🟢 Functional:** `last_test_pass > last_change` (where `last_change` = timestamp when `CONFIG_FINGERPRINT` changed)

**🔵 Documented:** `spoke_commit_time >= last_change` (git commit time of `docs/spokes/<service>.md`)

**⚠️ Stale:** Either condition fails.

### Automation Flow

1. **docs-indexer** container runs on-demand or via CI:
   - Computes `CONFIG_FINGERPRINT` for each service in `docker-compose.yml`
   - Reads `data/tests/<service>/last_pass.json` for test timestamps
   - Reads git log for Spoke commit times
   - Writes `docs/_data/status.json` with per-service status

2. **CI gates** (GitHub Actions / local pre-commit):
   - Fail if any service is ⚠️ Stale (unless explicitly allowed via `.freshness-exemptions`)
   - Check for broken links in docs (via `markdown-link-check`)

3. **MkDocs Material site** consumes `status.json`:
   - Renders badges (🟢🔵⚪⚠️) in service catalog
   - Shows "Last tested" / "Last changed" timestamps

## Consequences

### Positive
- **Automation at 0.5:** No manual tracking; CI enforces freshness.
- **Config-change detection:** Any material change triggers staleness (image bump, config edit, env change).
- **Git-native:** Spoke commit times = version control history (no separate metadata store).
- **Transparent:** `status.json` is human-readable; easy to debug why a service is stale.

### Negative
- **Initial overhead:** Must instrument all services for fingerprinting.
- **False positives:** Cosmetic changes (comments, whitespace) may trigger staleness (mitigated by excluding comment-only commits in git log parsing).
- **Bootstrap dependency:** Phase 0.5 must complete before Phase 1 tests can validate freshness.

### Trade-offs
- **Fingerprint granularity vs. complexity:** We include compose stanza (catches label changes, network additions) but not runtime state (logs, container uptime). This balances sensitivity with practicality.
- **Central tracker vs. per-service metadata:** `status.json` is centralized (simpler) vs. per-service `.freshness` files (more distributed but harder to aggregate).

## Alternatives Considered

1. **Manual checklists:** Rejected (human error, no enforcement).
2. **Timestamp-only tracking (no fingerprints):** Rejected (can't detect what changed, only when).
3. **Dedicated database (SQLite):** Rejected (adds state; JSON file is simpler for git-tracked projects).
4. **Per-service `.freshness` files:** Rejected (harder to aggregate for CI gates; centralized `status.json` is easier).

## Implementation Details

### docs-indexer Container

**Language:** Python 3.11
**Dependencies:** `docker`, `pyyaml`, `gitpython`

**Entrypoint script:**
```bash
#!/usr/bin/env bash
# 1. Parse docker-compose.yml → extract services
# 2. For each service:
#    - Compute CONFIG_FINGERPRINT
#    - Read last test pass from data/tests/<service>/last_pass.json
#    - Read Spoke commit time from git log docs/spokes/<service>.md
# 3. Write docs/_data/status.json
# 4. Exit 0 (success) or 1 (stale services found + not exempted)
```

**Volumes:**
- `./docker-compose.yml:/app/docker-compose.yml:ro`
- `./configs:/app/configs:ro`
- `./docs:/app/docs:rw` (writes `_data/status.json`)
- `./data:/app/data:ro` (reads test results)
- `./.git:/app/.git:ro` (git log access)
- `/run/user/${UID}/docker.sock:/var/run/docker.sock:ro` (image digest inspection)

### CI Gate (GitHub Actions)

```yaml
name: Freshness Check
on: [push, pull_request]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - run: docker compose run --rm docs-indexer
      - run: |
          if jq -e '.[] | select(.status == "stale")' docs/_data/status.json; then
            echo "❌ Stale services found"
            exit 1
          fi
```

### status.json Schema

```json
{
  "caddy": {
    "status": "untested",
    "fingerprint": "abc123...",
    "last_change": "2025-10-26T05:34:00Z",
    "last_test_pass": null,
    "spoke_updated": "2025-10-26T05:36:00Z",
    "functional": false,
    "documented": true
  },
  "grafana": {
    "status": "functional",
    "fingerprint": "def456...",
    "last_change": "2025-10-25T10:00:00Z",
    "last_test_pass": "2025-10-26T08:00:00Z",
    "spoke_updated": "2025-10-26T09:00:00Z",
    "functional": true,
    "documented": true
  }
}
```

## Related

- **ADR-000:** Caddy multi-hostname architecture (service discovery baseline)
- **Spokes:** Each service Spoke will include `Last change fingerprint` field
- **Next ADRs:**
  - ADR-002: Observability scrape policy (Phase 2)
  - ADR-003: Authelia forward_auth SSO/RBAC (Phase 3)

## Notes

- **Exemptions:** `.freshness-exemptions` file can list services to skip in CI (e.g., experimental services in development).
- **Test timestamp source:** Phase 1 test runner will write `data/tests/<service>/<timestamp>/last_pass.json` with `{"timestamp": "...", "commit": "...", "result": "pass"}`.
- **Image digest fetch:** Use `docker inspect <image> --format='{{.RepoDigests}}'` for pinned digests.

---

**Last updated:** 2025-10-26
