# Comprehensive Healthcheck Simplification Task

## Problem
The Datamancy stack has 34/45 services showing as "unhealthy" despite being functional.

**Immediate blocker:** 27 healthcheck commands use `nc -z host port` to test dependencies, but containers run BusyBox `nc` which doesn't support the `-z` flag, causing healthchecks to fail with exit code 8.

**Broader issue:** Many healthchecks are over-complicated, checking multiple endpoints, testing dependencies, and using complex shell commands when they should only verify the service itself is responding.

## Current Situation
- 45 services total in the stack
- 34 showing "unhealthy", 11 showing "healthy"
- 27 healthchecks use `nc -z` (immediate blocker causing failures)
- Many others check multiple endpoints unnecessarily
- Services are actually running fine - healthchecks are just poorly designed

## Task
**Comprehensive healthcheck review and simplification for ALL 45 services:**

1. Research official/recommended healthcheck for each service
2. Simplify to check ONLY the service's primary health endpoint
3. Remove ALL dependency checks (`nc -z`, connection tests to other services)
4. Remove redundant endpoint checks (e.g., checking `/health` AND `/api/config` when `/health` alone is sufficient)
5. Remove process checks (`ps aux | grep`) where a proper endpoint exists
6. Ensure all commands are BusyBox-compatible (no GNU-specific flags)
7. Review services currently showing "healthy" to ensure they follow best practices too

**Docker healthcheck best practice:** Healthchecks should answer "is THIS service responding?" NOT "are all dependencies ready?" Dependencies are handled by `depends_on` and orchestration, not healthchecks.

## All 45 Services to Review

Based on dist/docker-compose.yml, here are ALL services with their current healthcheck status and what needs review:

### ✅ Currently Healthy (11) - Still Review These
1. **caddy** (line 445): `wget http://localhost:2019/health` - Good, keep as-is
2. **docker-proxy** (line 687): `wget /version && wget /info` - Review: is /info check necessary?
3. **element** (line 997): `wget / && test -d /usr/share/nginx/html` - Review: is directory test necessary?
4. **homepage** (line 1264): `wget / && nc -z docker-proxy 2375` - **Remove nc -z**
5. **node-exporter** (line 2023): `wget /metrics && test -d /host/proc && test -d /host/sys` - Review: directory tests necessary?
6. **onlyoffice** (line 1759): Complex with supervisorctl - **Simplify**
7. **postgres** (line 748): `pg_isready && psql SELECT 1 && check connection count` - **Too complex, simplify**
8. **prometheus** (line 2004): `wget /-/healthy && wget /-/ready` - Review: both needed?
9. **qbittorrent** (line 1369): `wget / && nc -z localhost 6881` - **Remove nc -z**
10. **radicale** (line 1693): `wget /.web/ && test -d /data` - Review: directory test necessary?
11. **valkey** (line 487): `valkey-cli ping` - Good, keep as-is
12. **cadvisor** (line 2042): `wget /healthz && wget /metrics && test -d /rootfs` - Review: all checks necessary?

### ❌ Currently Unhealthy (34) - Fix These

#### Authentication & Directory Services
13. **ldap** (line 472): `ldapsearch -x -H ldap://localhost:389 ...` - Good approach, verify syntax
14. **authelia** (line 552): `wget /api/health && wget /api/configuration && nc -z postgres 5432 && nc -z ldap 389 && nc -z valkey 6379`
    - **Fix:** Keep only `/api/health`, remove everything else

#### Mail Services
15. **mailserver** (line 622): `ss -ltn | grep ports && postfix status && doveadm director status && nc -z ldap 389`
    - **Research:** Docker Mailserver official healthcheck
16. **ldap-account-manager** (line 649): `wget /lam/templates/login.php && nc -z ldap 389`
    - **Fix:** Keep login page, remove nc -z
17. **roundcube** (line 1675): `wget / && nc -z mailserver 143 && nc -z mailserver 587 && nc -z postgres 5432`
    - **Fix:** Keep only wget /, remove all nc -z

#### Databases
18. **mariadb** (line 827): `healthcheck.sh --connect --innodb_initialized && mariadb -u root -p... -e 'SELECT 1'`
    - **Fix:** Keep only healthcheck.sh or simple connection test
19. **clickhouse** (line 881): `wget /ping && wget /?query=SELECT%201`
    - Review: Is query check necessary or just /ping?
20. **qdrant** (line 902): `wget /health && wget /readyz && nc -z localhost 6334`
    - **Fix:** Keep /health OR /readyz (not both), remove nc -z

#### Vector Databases & Caching
21. **seafile-memcached** (line 661): `echo stats | nc localhost 11211 | grep -q uptime`
    - Good approach, keep as-is
22. **init-qdrant-collections** (line 802): `test -f /tmp/init_complete`
    - Good for init container, keep as-is
23. **init-clickhouse-tables** (line 861): `test -f /tmp/init_complete`
    - Good for init container, keep as-is

#### Backup & Monitoring
24. **kopia** (line 715): `wget / && test -d /repository`
    - Review: directory test necessary?
25. **grafana** (line 981): `wget /api/health && wget /api/datasources && nc -z postgres 5432`
    - **Fix:** Keep only /api/health, remove datasources and nc -z
26. **prometheus** - Already listed as healthy
27. **node-exporter** - Already listed as healthy
28. **cadvisor** - Already listed as healthy
29. **watchtower** (line 2064): `ps aux | grep -v grep | grep watchtower && test -S /var/run/docker.sock`
    - Review: Is there a better check than ps aux?

#### Web Applications
30. **open-webui** (line 1045): `wget /health && wget /api/config && nc -z postgres 5432 && nc -z litellm 4000`
    - **Fix:** Keep only /health
31. **vaultwarden** (line 1104): `wget /alive && wget /api/config && nc -z postgres 5432 && nc -z localhost 3012`
    - **Fix:** Keep only /alive
32. **bookstack** (line 1142): `php artisan config:cache && wget / && nc -z mariadb 3306`
    - **Fix:** Keep only wget /, remove artisan and nc -z
33. **planka** (line 1189): `wget / && wget /api/config && nc -z postgres 5432`
    - **Fix:** Keep only /
34. **forgejo** (line 1233): `wget /api/healthz && wget /api/v1/version && nc -z postgres 5432`
    - **Fix:** Keep only /api/healthz
35. **jupyterhub** (line 1295): `wget /hub/health && wget /hub/api && nc -z docker-proxy 2375`
    - **Fix:** Keep only /hub/health
36. **homeassistant** (line 1346): `wget /api/ && wget /api/config && nc -z postgres 5432 && nc -z ldap 389`
    - **Fix:** Keep only /api/ (or research better endpoint)

#### Matrix & Social
37. **synapse** (line 1456): `wget /health && wget /_matrix/client/versions && wget /_matrix/federation/v1/version && nc -z postgres 5432 && nc -z valkey 6379 && nc -z ldap 389`
    - **Fix:** Keep only /health
38. **element** - Already listed as healthy
39. **mastodon-web** (line 1570): `wget /health && wget /api/v1/instance && ps aux | grep puma && nc -z postgres 5432 && nc -z valkey 6379`
    - **Fix:** Keep only /health
40. **mastodon-streaming** (line 1597): `wget /api/v1/streaming/health && nc -z postgres 5432 && nc -z valkey 6379`
    - **Fix:** Keep only /api/v1/streaming/health
41. **mastodon-sidekiq** (line 1642): `ps aux | grep sidekiq && nc -z postgres 5432 && nc -z valkey 6379`
    - **Research:** Better healthcheck than process grep?

#### File Storage & Collaboration
42. **seafile** (line 1739): `wget /api2/ping/ && nc -z localhost 8082 && nc -z mariadb 3306 && nc -z seafile-memcached 11211`
    - **Fix:** Keep only /api2/ping/
43. **onlyoffice** - Already listed as healthy but needs review

#### Custom Data Pipeline Services
44. **agent-tool-server** (line 1795): `wget /health && nc -z postgres 5432 && nc -z data-fetcher 8095 && nc -z unified-indexer 8096`
    - **Fix:** Keep only /health
45. **data-fetcher** (line 1844): `wget /health && nc -z postgres 5432 && nc -z clickhouse 8123 && nc -z qdrant 6333`
    - **Fix:** Keep only /health
46. **unified-indexer** (line 1885): `wget /health && nc -z postgres 5432 && nc -z qdrant 6334 && nc -z clickhouse 8123 && nc -z embedding-service 8080`
    - **Fix:** Keep only /health
47. **search-service** (line 1916): `wget /health && nc -z qdrant 6333 && nc -z clickhouse 8123 && nc -z embedding-service 8080`
    - **Fix:** Keep only /health
48. **prompt-server** - Check if it has a healthcheck defined
49. **control-panel** (line 1983): `wget /health && nc -z postgres 5432 && nc -z mariadb 3306 && nc -z clickhouse 8123 && nc -z qdrant 6333 && nc -z ldap 389 && nc -z litellm 4000 && test -S /var/run/docker.sock`
    - **Fix:** Keep only /health and docker socket test

#### AI/ML Services
50. **embedding-service** - Check current healthcheck
51. **vllm-7b** (line 2113): `wget /health && wget /v1/models`
    - Review: Are both endpoints necessary?
52. **litellm** (line 2175): `wget /health && wget /models && nc -z vllm-7b 8000 && nc -z embedding-service 8080`
    - **Fix:** Keep only /health

## Research Methodology

For each service, look up:
1. **Official Docker image documentation** - Most images document recommended healthchecks
2. **Application's health endpoint documentation** - What does the app officially recommend?
3. **Common patterns** - What do other users in production use?

Example resources:
- Docker Hub image pages (see "How to use this image" section)
- GitHub repos for official images (often have example compose files)
- Application official docs (search for "docker healthcheck" or "health endpoint")
- Community best practices (search: "[service name] docker healthcheck best practice")

## Output Required

### 1. Research Document
Create `HEALTHCHECK-RESEARCH.md` with this structure:

```markdown
# Service: [name]
**Current Status:** healthy/unhealthy
**Current Healthcheck:** [current command]
**Issue:** [what's wrong with current check]

## Official Recommendation
[Link to documentation]
[Recommended healthcheck from official sources]

## Proposed Healthcheck
```yaml
healthcheck:
  test: ["CMD", "..."]
  interval: Xs
  timeout: Xs
  retries: X
  start_period: Xs
```

## Justification
[Why this is better - simpler, official recommendation, removes dependencies, etc.]

## Test Command
```bash
docker exec [container] [test command]
```

---
[Repeat for each service]
```

### 2. Updated Compose Files
Modify files in `/home/gerald/IdeaProjects/Datamancy/compose/` directory:
- Update each service's healthcheck definition
- Keep ONLY the service's own health endpoint
- Remove ALL `nc -z` dependency checks
- Remove redundant endpoint checks
- Use only BusyBox-compatible commands
- Preserve interval/timeout/retries unless research suggests changes

## Constraints
- **DO NOT** add new dependencies or install packages
- **DO NOT** modify any service configuration besides healthcheck
- **USE ONLY** BusyBox-compatible commands (wget, test, nc without -z, etc.)
- **KEEP** interval/timeout/retries/start_period unless research says otherwise
- **PRESERVE** the intent: ensure service is ready to handle requests

## Files to Work With

### Source files to modify:
- `/home/gerald/IdeaProjects/Datamancy/compose/*.yml` - All compose files
- Find which file each service is defined in
- Modify ONLY the `healthcheck:` section

### Generated file (do not edit directly):
- `/home/gerald/IdeaProjects/Datamancy/dist/docker-compose.yml` - Auto-generated, will be regenerated

### Build script:
- `/home/gerald/IdeaProjects/Datamancy/build-datamancy.main.kts` - Merges compose files

## Docker Healthcheck Best Practices

Research and apply these principles:

### Liveness vs Readiness
- Docker has ONE healthcheck type (not separate liveness/readiness like Kubernetes)
- Should check: "Can this service handle requests RIGHT NOW?"
- Should NOT check: "Are dependencies available?" (that's orchestration's job)

### What to Check
- ✅ Service's own health endpoint (e.g., `/health`, `/api/health`, `/healthz`)
- ✅ Critical local resources (e.g., docker socket if service manages containers)
- ❌ Database connections (service should retry internally)
- ❌ Other services' availability
- ❌ Multiple endpoints when one is sufficient
- ❌ Process existence (use proper endpoints instead)

### Command Best Practices
- Use `CMD` format when possible (better than `CMD-SHELL`)
- Keep commands simple and fast (healthcheck runs frequently)
- Return proper exit codes: 0 = healthy, 1 = unhealthy
- Consider startup time (set appropriate `start_period`)

### BusyBox Compatibility
- ✅ `wget --quiet --tries=1 --spider http://...`
- ✅ `test -f /path/to/file`
- ✅ `test -d /path/to/dir`
- ✅ `echo "command" | nc localhost port`
- ❌ `nc -z host port` (use `timeout 1 nc -w 1 host port </dev/null` if really needed)
- ❌ GNU-specific flags

## Success Criteria

- [ ] All 45 services reviewed and documented
- [ ] All 27 services using `nc -z` fixed
- [ ] All services with redundant checks simplified
- [ ] All healthchecks use BusyBox-compatible commands only
- [ ] Services show as "healthy" when they are actually functional
- [ ] No false negatives (working services marked unhealthy)
- [ ] Clear documentation explaining each healthcheck
- [ ] Testing notes for manual verification

## Timeline Estimate

- Research phase: 2-3 hours (45 services, ~3-4 minutes each)
- Implementation: 1-2 hours (updating compose files)
- Documentation: 1 hour (research notes + summary)
- Testing plan: 30 minutes

Total: ~4-6 hours of focused work
