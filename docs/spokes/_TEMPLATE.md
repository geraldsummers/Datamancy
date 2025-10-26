# [Service Name] — Spoke

**Status:** ⚪ Not Started | 🟡 In Progress | 🟢 Functional | 🔵 Documented
**Phase:** [0-7]
**Hostname:** `service.stack.local`
**Dependencies:** [list]

## Purpose

[1-2 sentences: what does this service do?]

## Configuration

**Image:** `vendor/image:tag@sha256:...`
**Volumes:** [list mounted volumes]
**Networks:** [frontend/backend/socket]
**Ports:** [exposed ports if any]

### Key Settings

[Environment variables, config files, or notable settings]

### Fingerprint Inputs

- Image digest: `sha256:...`
- Config dir: `configs/service/` (hash via `find configs/service/ -type f -exec sha256sum {} \; | sort | sha256sum`)
- Secrets: [list env vars/files contributing to fingerprint]
- Compose stanza: [service block in docker-compose.yml]

## Access

- **URL:** `https://service.stack.local`
- **Auth:** [Authelia RBAC role / none / basic auth]
- **Healthcheck:** `GET /health` (or equivalent)

## Runbook

### Start/Stop

```bash
docker compose --profile [profile] up -d service
docker compose stop service
```

### Logs

```bash
docker compose logs -f service
```

### Common Issues

**Symptom:** [describe problem]
**Cause:** [why it happens]
**Fix:** [how to resolve]

## Testing

**Smoke test:** [describe browser/API test]
**Last pass:** [timestamp or "never"]
**Artifacts:** `data/tests/service/<timestamp>/`

## Related

- ADR: [link to relevant ADR]
- Dependencies: [links to dependency Spokes]
- Upstream docs: [external links]

---

**Last updated:** [YYYY-MM-DD]
**Last change fingerprint:** [sha256]
