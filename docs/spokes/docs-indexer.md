# Docs Indexer — Spoke

**Status:** 🟡 In Progress
**Phase:** 0.5 — Docs Automation Bootstrap
**Hostname:** N/A (tools profile)
**Dependencies:** Docker, git

## Purpose

Computes `CONFIG_FINGERPRINT` for each service and generates `docs/_data/status.json` tracking freshness (Functional + Documented states).

## Configuration

**Image:** Built from `tools/docs-indexer/Dockerfile`
**Volumes:**
- `./docker-compose.yml:/app/docker-compose.yml:ro` — Service definitions
- `./configs:/app/configs:ro` — Config directories
- `./docs:/app/docs:rw` — Writes `_data/status.json`
- `./data:/app/data:ro` — Test artifacts
- `./.git:/app/.git:ro` — Git log for Spoke commit times
- `/run/user/${UID}/docker.sock:/var/run/docker.sock:ro` — Image digests

**Networks:** default (ephemeral)
**Ports:** None

### Key Settings

**Fingerprint components:**
1. Image digest (via `docker inspect`)
2. Config directory hash (`configs/<service>/`)
3. Environment variables hash
4. Compose service stanza hash

**Status determination:**
- **Functional:** `last_test_pass > last_change`
- **Documented:** `spoke_commit_time >= last_change`

### Fingerprint Inputs

- Image: Built from `tools/docs-indexer/` (Python 3.11-slim + git + docker CLI)
- Config dir: `tools/docs-indexer/` (hash: TBD)
- Secrets: None
- Compose stanza: `services.docs-indexer` block

## Access

- **URL:** N/A (CLI tool)
- **Auth:** None (local execution only)
- **Healthcheck:** Exit code 0 = success

## Runbook

### Run Indexer

```bash
docker compose run --rm docs-indexer
```

Output: `docs/_data/status.json` with per-service status.

### Inspect Status

```bash
cat docs/_data/status.json | jq '.'
```

### Common Issues

**Symptom:** `docker: command not found` inside container
**Cause:** Docker CLI not installed in image
**Fix:** Already resolved (Dockerfile installs `docker.io`)

**Symptom:** `TypeError: can't compare offset-naive and offset-aware datetimes`
**Cause:** Timezone mismatch between git timestamps and datetime.now()
**Fix:** Use `datetime.now().astimezone()` for timezone-aware timestamps

**Symptom:** Services show `documented: false` despite Spoke existing
**Cause:** Spoke file path or git log query issue
**Fix:** Check git log shows commits for `docs/spokes/<service>.md`

## Testing

**Smoke test:** Run indexer; verify `status.json` generated with all services present.
**Last pass:** 2025-10-26 (Phase 0-5 tracking active)
**Artifacts:** `docs/_data/status.json`

## Current Status Tracking

As of 2025-10-26:
- **17 services tracked**
- **13 services with test coverage** (caddy, grafana, prometheus, loki, promtail, authelia, ldap, mariadb, mongodb, clickhouse, adminer, mongo-express, portainer)
- **13 services functional** (tested + documented)
- **4 services untested** (browserless, test-runner, docs-indexer, mkdocs)

Status breakdown:
- `functional`: last_test_pass > last_change AND spoke_updated >= last_change
- `stale`: last_test_pass > last_change BUT spoke_updated < last_change
- `untested`: no last_test_pass
- `undocumented`: no Spoke file

## Related

- **ADR:** [ADR-001: Freshness Rules + Fingerprints](../adr/ADR-001-freshness-fingerprints.md)
- **CI:** `.github/workflows/freshness-check.yml` (placeholder)
- **Consumers:** MkDocs site, landing page badges

---

**Update 2025-10-26:** Successfully tracking 13 functional services across Phases 0-5. Freshness automation working as designed. All test artifacts properly consumed from `data/tests/{service}/last_pass.json`. Git commit times correctly parsed for Spoke documentation timestamps.

**Last updated:** 2025-10-26
