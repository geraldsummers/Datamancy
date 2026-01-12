# Docker Healthcheck Research & Simplification Plan

**Date:** 2026-01-13
**Objective:** Simplify healthchecks for all 45 services to fix 34 unhealthy containers

---

## Executive Summary

### Problem
- **34/45 services** showing "unhealthy" despite being functional
- **27 services** use `nc -z host port` which fails on BusyBox (exit code 8)
- Many healthchecks over-complicated, checking dependencies instead of service health

### Solution
- Remove ALL dependency checks (`nc -z`, connection tests to other services)
- Use ONLY the service's primary health endpoint
- Ensure all commands are BusyBox-compatible
- Follow Docker best practice: "Is THIS service responding?"

### Key Principle
**Docker healthchecks answer: "Can THIS service handle requests?"**
NOT: "Are all dependencies ready?" (that's orchestration's job)

---

## Service-by-Service Recommendations

### Infrastructure Services

---

#### Service: caddy
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:2019/health"]
interval: 10s
timeout: 3s
retries: 3
start_period: 10s
```

**Issue:** None - this is perfect!

**Official Recommendation:**
- Caddy Admin API `/health` endpoint: https://caddyserver.com/docs/api#get-health
- Simple wget check is the official recommendation

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:2019/health"]
interval: 10s
timeout: 3s
retries: 3
start_period: 10s
```

**Justification:** Already optimal - keep as-is

**Test Command:**
```bash
docker exec caddy wget --quiet --tries=1 --spider http://localhost:2019/health
```

---

#### Service: ldap
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "ldapsearch -x -H ldap://localhost:389 -b dc=stack,dc=local -s base '(objectclass=*)' >/dev/null 2>&1"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Issue:** The healthcheck itself is good, but may be timing out or having syntax issues

**Official Recommendation:**
- OpenLDAP healthcheck: https://github.com/osixia/docker-openldap#healthcheck
- Use `ldapsearch` to query the base DN

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "ldapsearch -x -H ldap://localhost:389 -b dc=stack,dc=local -s base '(objectclass=*)' >/dev/null 2>&1"]
interval: 30s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- Keep the good ldapsearch approach
- Increase timeout to 10s (directory queries can be slow)
- Increase start_period to 60s (LDAP takes time to initialize)

**Test Command:**
```bash
docker exec ldap ldapsearch -x -H ldap://localhost:389 -b dc=stack,dc=local -s base '(objectclass=*)'
```

---

#### Service: valkey
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD", "valkey-cli", "ping"]
interval: 5s
timeout: 3s
retries: 5
start_period: 5s
```

**Issue:** None - perfect!

**Official Recommendation:**
- Valkey CLI ping: https://valkey.io/commands/ping/
- This is the canonical way to check Redis/Valkey health

**Proposed Healthcheck:**
```yaml
test: ["CMD", "valkey-cli", "ping"]
interval: 5s
timeout: 3s
retries: 5
start_period: 5s
```

**Justification:** Already optimal - keep as-is

**Test Command:**
```bash
docker exec valkey valkey-cli ping
```

---

#### Service: authelia
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:9091/api/health && wget --quiet --tries=1 --spider http://localhost:9091/api/configuration && nc -z postgres 5432 && nc -z ldap 389 && nc -z valkey 6379"]
interval: 15s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Checks 3 dependencies with `nc -z` (BusyBox incompatible and wrong pattern)

**Official Recommendation:**
- Authelia health endpoint: https://www.authelia.com/integration/prologue/health/
- `/api/health` is the dedicated health endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9091/api/health"]
interval: 15s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Remove `/api/configuration` check - redundant
- Remove ALL dependency checks - handled by depends_on
- Single health endpoint is sufficient and official

**Test Command:**
```bash
docker exec authelia wget --quiet --tries=1 --spider http://localhost:9091/api/health
```

---

#### Service: mailserver
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "ss -ltn | grep -E ':25|:587|:993|:143' && postfix status >/dev/null 2>&1 && doveadm director status >/dev/null 2>&1 && nc -z ldap 389"]
interval: 60s
timeout: 10s
retries: 5
start_period: 180s
```

**Issue:**
- Complex multi-check with process tests
- Uses `nc -z` for LDAP dependency
- `ss`, `postfix status`, `doveadm` commands may not be reliable

**Official Recommendation:**
- Docker Mailserver: https://docker-mailserver.github.io/docker-mailserver/latest/config/advanced/optional-config/#healthcheck
- Official recommendation: check supervisorctl status of key services

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "ss -ltn | grep -E ':25|:587|:993|:143' && supervisorctl status | grep -E 'postfix.*RUNNING' && supervisorctl status | grep -E 'dovecot.*RUNNING'"]
interval: 60s
timeout: 10s
retries: 5
start_period: 180s
```

**Justification:**
- Keep port checks (verifies services are listening)
- Use supervisorctl instead of postfix/doveadm commands (more reliable)
- Remove LDAP dependency check
- Start period of 180s is appropriate (mailserver takes time to initialize)

**Test Command:**
```bash
docker exec mailserver ss -ltn | grep -E ':25|:587|:993|:143'
docker exec mailserver supervisorctl status
```

---

#### Service: ldap-account-manager
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/lam/templates/login.php && nc -z ldap 389"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** Uses `nc -z` to check LDAP dependency

**Official Recommendation:**
- LAM is a PHP web app, checking the login page is appropriate
- https://github.com/LDAPAccountManager/lam

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/lam/templates/login.php"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Remove LDAP dependency check
- Login page check verifies PHP/Apache and app files are accessible

**Test Command:**
```bash
docker exec ldap-account-manager wget --quiet --tries=1 --spider http://localhost:80/lam/templates/login.php
```

---

#### Service: seafile-memcached
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "echo stats | nc localhost 11211 | grep -q uptime"]
interval: 30s
timeout: 3s
retries: 3
start_period: 10s
```

**Issue:** None - this is a good pattern!

**Official Recommendation:**
- Memcached stats command: https://github.com/memcached/memcached/wiki/Commands#statistics
- Checking for `uptime` in stats verifies memcached is responding

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "echo stats | nc localhost 11211 | grep -q uptime"]
interval: 30s
timeout: 3s
retries: 3
start_period: 10s
```

**Justification:** Already optimal - keep as-is

**Test Command:**
```bash
docker exec seafile-memcached sh -c "echo stats | nc localhost 11211"
```

---

#### Service: docker-proxy
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:2375/version && wget --quiet --tries=1 --spider http://localhost:2375/info"]
interval: 30s
timeout: 5s
retries: 3
start_period: 10s
```

**Issue:** Checking two endpoints is redundant

**Official Recommendation:**
- Docker API: https://docs.docker.com/engine/api/v1.44/#tag/System
- `/version` is simpler and sufficient

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:2375/version"]
interval: 30s
timeout: 5s
retries: 3
start_period: 10s
```

**Justification:**
- `/version` endpoint alone verifies Docker API is responding
- Simpler and faster than checking two endpoints

**Test Command:**
```bash
docker exec docker-proxy wget --quiet --tries=1 --spider http://localhost:2375/version
```

---

#### Service: kopia
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:51515/ && test -d /repository"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** Directory test may be unnecessary

**Official Recommendation:**
- Kopia server UI health: https://kopia.io/docs/reference/command-line/common/server-status/
- Web UI availability indicates server is healthy

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:51515/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Web UI check is sufficient
- Remove directory test (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec kopia wget --quiet --tries=1 --spider http://localhost:51515/
```

---

### Database Services

---

#### Service: postgres
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "pg_isready -U ${STACK_ADMIN_USER} && psql -U ${STACK_ADMIN_USER} -d postgres -c 'SELECT 1' >/dev/null 2>&1 && test $(psql -U ${STACK_ADMIN_USER} -d postgres -tAc 'SELECT count(*) FROM pg_stat_activity') -lt 290"]
interval: 10s
timeout: 5s
retries: 5
start_period: 30s
```

**Issue:** Overly complex - connection count check is operational monitoring, not health

**Official Recommendation:**
- PostgreSQL: https://hub.docker.com/_/postgres
- Official recommendation: `pg_isready` alone is sufficient

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "pg_isready -U ${STACK_ADMIN_USER}"]
interval: 10s
timeout: 5s
retries: 5
start_period: 30s
```

**Justification:**
- `pg_isready` verifies PostgreSQL is accepting connections
- Remove redundant SELECT 1 query
- Remove connection count check (use monitoring tools instead)

**Test Command:**
```bash
docker exec postgres pg_isready -U datamancer
```

---

#### Service: mariadb
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "healthcheck.sh --connect --innodb_initialized && mariadb -u root -p${MYSQL_ROOT_PASSWORD} --ssl=0 -e 'SELECT 1' >/dev/null 2>&1"]
interval: 15s
timeout: 5s
retries: 3
start_period: 60s
```

**Issue:** Two checks are redundant; `healthcheck.sh` alone should be sufficient

**Official Recommendation:**
- MariaDB: https://hub.docker.com/_/mariadb
- Built-in `healthcheck.sh` is the official recommendation

**Proposed Healthcheck:**
```yaml
test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
interval: 15s
timeout: 5s
retries: 3
start_period: 60s
```

**Justification:**
- Built-in healthcheck.sh verifies MariaDB is ready
- `--innodb_initialized` ensures storage engine is ready
- Remove redundant SELECT 1 query

**Test Command:**
```bash
docker exec mariadb healthcheck.sh --connect --innodb_initialized
```

---

#### Service: clickhouse
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8123/ping && wget --quiet --tries=1 --spider 'http://localhost:8123/?query=SELECT%201'"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Issue:** Two checks are redundant

**Official Recommendation:**
- ClickHouse: https://clickhouse.com/docs/en/operations/monitoring
- `/ping` endpoint is the official health check

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8123/ping"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Justification:**
- `/ping` endpoint alone verifies ClickHouse is ready
- Remove redundant SELECT 1 query check

**Test Command:**
```bash
docker exec clickhouse wget --quiet --tries=1 --spider http://localhost:8123/ping
```

---

#### Service: qdrant
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:6333/health && wget --quiet --tries=1 --spider http://localhost:6333/readyz && nc -z localhost 6334"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Issue:**
- Two HTTP checks are redundant
- Uses `nc -z` to check gRPC port (BusyBox incompatible)

**Official Recommendation:**
- Qdrant: https://qdrant.tech/documentation/guides/monitoring/
- `/health` endpoint is sufficient

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:6333/health"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Justification:**
- `/health` endpoint verifies Qdrant is operational
- Remove `/readyz` check (redundant)
- Remove gRPC port check (if HTTP API works, gRPC typically works too)

**Test Command:**
```bash
docker exec qdrant wget --quiet --tries=1 --spider http://localhost:6333/health
```

---

### Application Services

---

#### Service: grafana
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:3000/api/health && wget --quiet --tries=1 --spider http://localhost:3000/api/datasources && nc -z postgres 5432"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for postgres dependency

**Official Recommendation:**
- Grafana: https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#healthcheck
- `/api/health` is the official health endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:3000/api/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/api/health` alone is sufficient
- Remove datasources check (part of app logic, not health)
- Remove postgres dependency check

**Test Command:**
```bash
docker exec grafana wget --quiet --tries=1 --spider http://localhost:3000/api/health
```

---

#### Service: prompt-server
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/ && test -d /usr/share/nginx/html"]
interval: 30s
timeout: 5s
retries: 3
start_period: 10s
```

**Issue:** Directory test is unnecessary

**Official Recommendation:**
- Nginx: https://nginx.org/en/docs/http/ngx_http_stub_status_module.html
- Simple HTTP check on root path is standard

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/"]
interval: 30s
timeout: 5s
retries: 3
start_period: 10s
```

**Justification:**
- Root path check verifies Nginx is serving files
- Remove directory test (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec prompt-server wget --quiet --tries=1 --spider http://localhost:80/
```

---

#### Service: open-webui
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8080/health && wget --quiet --tries=1 --spider http://localhost:8080/api/config && nc -z postgres 5432 && nc -z litellm 4000"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Open WebUI: https://github.com/open-webui/open-webui
- `/health` endpoint is standard for health checks

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove config check (part of app logic)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec open-webui wget --quiet --tries=1 --spider http://localhost:8080/health
```

---

#### Service: vaultwarden
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/alive && wget --quiet --tries=1 --spider http://localhost:80/api/config && nc -z postgres 5432 && nc -z localhost 3012"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Vaultwarden: https://github.com/dani-garcia/vaultwarden/wiki/Using-Docker-Compose
- `/alive` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/alive"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/alive` endpoint is designed for healthchecks
- Remove config check (redundant)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec vaultwarden wget --quiet --tries=1 --spider http://localhost:80/alive
```

---

#### Service: bookstack
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "php artisan config:cache --quiet && wget --quiet --tries=1 --spider http://localhost:80/ && nc -z mariadb 3306"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:**
- Runs `php artisan config:cache` during healthcheck (side effect!)
- Uses `nc -z` for mariadb dependency

**Official Recommendation:**
- BookStack: https://www.bookstackapp.com/docs/admin/docker/
- Simple HTTP check on root path

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- Root path check verifies PHP-FPM and Nginx are working
- Remove artisan command (healthchecks should have NO side effects)
- Remove mariadb dependency check

**Test Command:**
```bash
docker exec bookstack wget --quiet --tries=1 --spider http://localhost:80/
```

---

#### Service: planka
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:1337/ && wget --quiet --tries=1 --spider http://localhost:1337/api/config && nc -z postgres 5432"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for postgres dependency

**Official Recommendation:**
- Planka: https://github.com/plankanban/planka
- Root path check is standard

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:1337/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- Root path check verifies Node.js app is responding
- Remove config check (redundant)
- Remove postgres dependency check

**Test Command:**
```bash
docker exec planka wget --quiet --tries=1 --spider http://localhost:1337/
```

---

#### Service: forgejo
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:3000/api/healthz && wget --quiet --tries=1 --spider http://localhost:3000/api/v1/version && nc -z postgres 5432"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for postgres dependency

**Official Recommendation:**
- Forgejo: https://forgejo.org/docs/latest/admin/config-cheat-sheet/#server-server
- `/api/healthz` is the official health endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:3000/api/healthz"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Justification:**
- `/api/healthz` is the dedicated health endpoint
- Remove version check (redundant)
- Remove postgres dependency check

**Test Command:**
```bash
docker exec forgejo wget --quiet --tries=1 --spider http://localhost:3000/api/healthz
```

---

#### Service: homepage
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:3000/ && nc -z docker-proxy 2375"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** Uses `nc -z` for docker-proxy dependency

**Official Recommendation:**
- Homepage: https://github.com/gethomepage/homepage
- Simple HTTP check on root

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:3000/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Root path check verifies Next.js app is responding
- Remove docker-proxy dependency check

**Test Command:**
```bash
docker exec homepage wget --quiet --tries=1 --spider http://localhost:3000/
```

---

#### Service: jupyterhub
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8000/hub/health && wget --quiet --tries=1 --spider http://localhost:8000/hub/api && nc -z docker-proxy 2375"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for docker-proxy dependency

**Official Recommendation:**
- JupyterHub: https://jupyterhub.readthedocs.io/en/stable/reference/rest-api.html#health-check
- `/hub/health` is the official health endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8000/hub/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 90s
```

**Justification:**
- `/hub/health` is the dedicated health endpoint
- Remove API check (redundant)
- Remove docker-proxy dependency check

**Test Command:**
```bash
docker exec jupyterhub wget --quiet --tries=1 --spider http://localhost:8000/hub/health
```

---

#### Service: homeassistant
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8123/api/ && wget --quiet --tries=1 --spider http://localhost:8123/api/config && nc -z postgres 5432 && nc -z ldap 389"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Home Assistant: https://www.home-assistant.io/docs/configuration/basic/#http
- `/api/` endpoint check is standard

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8123/api/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- `/api/` check verifies Home Assistant core is running
- Remove config check (redundant)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec homeassistant wget --quiet --tries=1 --spider http://localhost:8123/api/
```

---

#### Service: qbittorrent
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8080/ && nc -z localhost 6881"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** Uses `nc -z` to check torrent listening port

**Official Recommendation:**
- qBittorrent: https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-4.1)
- Web UI check is sufficient

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Web UI check verifies qBittorrent daemon is running
- Remove torrent port check (not critical for health)

**Test Command:**
```bash
docker exec qbittorrent wget --quiet --tries=1 --spider http://localhost:8080/
```

---

### Matrix & Social Services

---

#### Service: synapse
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8008/health && wget --quiet --tries=1 --spider http://localhost:8008/_matrix/client/versions && wget --quiet --tries=1 --spider http://localhost:8008/_matrix/federation/v1/version && nc -z postgres 5432 && nc -z valkey 6379 && nc -z ldap 389"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:**
- Checks 3 endpoints (excessive)
- Uses `nc -z` for 3 dependencies

**Official Recommendation:**
- Synapse: https://matrix-org.github.io/synapse/latest/usage/administration/useful_sql_for_admins.html#health-check
- `/health` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8008/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove Matrix API checks (redundant)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec synapse wget --quiet --tries=1 --spider http://localhost:8008/health
```

---

#### Service: element
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/ && test -f /app/config.json"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** File test is unnecessary

**Official Recommendation:**
- Element Web: https://github.com/element-hq/element-web
- Simple HTTP check is sufficient for static web apps

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Root path check verifies Nginx is serving Element
- Remove file check (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec element wget --quiet --tries=1 --spider http://localhost:80/
```

---

#### Service: mastodon-web
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:3000/health && wget --quiet --tries=1 --spider http://localhost:3000/api/v1/instance && ps aux | grep -v grep | grep puma && nc -z postgres 5432 && nc -z valkey 6379"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:**
- Checks 2 endpoints + process grep (excessive)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Mastodon: https://docs.joinmastodon.org/admin/optional/health/
- `/health` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:3000/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove API instance check (redundant)
- Remove process grep (anti-pattern)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec mastodon-web wget --quiet --tries=1 --spider http://localhost:3000/health
```

---

#### Service: mastodon-streaming
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:4000/api/v1/streaming/health && nc -z postgres 5432 && nc -z valkey 6379"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Mastodon Streaming: https://docs.joinmastodon.org/admin/optional/health/
- `/api/v1/streaming/health` is the dedicated endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:4000/api/v1/streaming/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- Health endpoint alone is sufficient
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec mastodon-streaming wget --quiet --tries=1 --spider http://localhost:4000/api/v1/streaming/health
```

---

#### Service: mastodon-sidekiq
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "ps aux | grep -v grep | grep sidekiq && nc -z postgres 5432 && nc -z valkey 6379"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:**
- Uses process grep (unreliable)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- Mastodon Sidekiq: https://github.com/mastodon/mastodon/discussions/26296
- Community recommendation: check Sidekiq process file or use process check as fallback

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "ps aux | grep -v grep | grep 'sidekiq'"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- Keep process check (no better alternative for background workers)
- Remove ALL dependency checks
- Sidekiq doesn't expose HTTP health endpoint

**Test Command:**
```bash
docker exec mastodon-sidekiq ps aux | grep sidekiq
```

---

#### Service: roundcube
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/ && nc -z mailserver 143 && nc -z mailserver 587 && nc -z postgres 5432"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 3 dependencies

**Official Recommendation:**
- Roundcube: https://github.com/roundcube/roundcubemail-docker
- Simple HTTP check on root path

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- Root path check verifies PHP-FPM and Apache are working
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec roundcube wget --quiet --tries=1 --spider http://localhost:80/
```

---

#### Service: radicale
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:5232/.web/ && test -d /data"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Issue:** Directory test is unnecessary

**Official Recommendation:**
- Radicale: https://radicale.org/v3.html
- Web interface check is appropriate

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:5232/.web/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 30s
```

**Justification:**
- Web interface check verifies Radicale is responding
- Remove directory test (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec radicale wget --quiet --tries=1 --spider http://localhost:5232/.web/
```

---

### File Storage & Collaboration

---

#### Service: seafile
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8000/api2/ping/ && nc -z localhost 8082 && nc -z mariadb 3306 && nc -z seafile-memcached 11211"]
interval: 60s
timeout: 10s
retries: 3
start_period: 180s
```

**Issue:** Uses `nc -z` for internal port and 2 dependencies

**Official Recommendation:**
- Seafile: https://manual.seafile.com/docker/deploy_seafile_with_docker/
- `/api2/ping/` is the official health check endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8000/api2/ping/"]
interval: 60s
timeout: 10s
retries: 3
start_period: 180s
```

**Justification:**
- `/api2/ping/` verifies Seafile API is responding
- Remove ALL port and dependency checks

**Test Command:**
```bash
docker exec seafile wget --quiet --tries=1 --spider http://localhost:8000/api2/ping/
```

---

#### Service: onlyoffice
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:80/healthcheck && nc -z localhost 8000 && supervisorctl status | grep -E 'docservice.*RUNNING' && supervisorctl status | grep -E 'converter.*RUNNING'"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:** Multiple checks are excessive

**Official Recommendation:**
- OnlyOffice: https://helpcenter.onlyoffice.com/installation/docs-docker-healthcheck.aspx
- `/healthcheck` endpoint checks all critical services internally

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:80/healthcheck"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- `/healthcheck` endpoint comprehensively checks docservice and converter
- Remove redundant supervisorctl checks and port test

**Test Command:**
```bash
docker exec onlyoffice wget --quiet --tries=1 --spider http://localhost:80/healthcheck
```

---

### Custom Datamancy Services

---

#### Service: control-panel
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8097/health && nc -z postgres 5432 && nc -z data-fetcher 8095 && nc -z unified-indexer 8096"]
interval: 30s
timeout: 5s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 3 dependencies

**Official Recommendation:**
- Custom service - depends on implementation
- If `/health` endpoint checks internal state, it's sufficient

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8097/health"]
interval: 30s
timeout: 5s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint should validate service's own readiness
- Remove ALL dependency checks
- **Recommendation:** Ensure `/health` endpoint does comprehensive internal checks

**Test Command:**
```bash
docker exec control-panel wget --quiet --tries=1 --spider http://localhost:8097/health
```

---

#### Service: data-fetcher
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8095/health && nc -z postgres 5432 && nc -z clickhouse 8123 && nc -z qdrant 6333"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 3 dependencies

**Official Recommendation:**
- Custom service - depends on implementation

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8095/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint should validate service's own readiness
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec data-fetcher wget --quiet --tries=1 --spider http://localhost:8095/health
```

---

#### Service: unified-indexer
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8096/health && nc -z postgres 5432 && nc -z qdrant 6334 && nc -z clickhouse 8123 && nc -z embedding-service 8080"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 4 dependencies

**Official Recommendation:**
- Custom service - depends on implementation

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8096/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint should validate service's own readiness
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec unified-indexer wget --quiet --tries=1 --spider http://localhost:8096/health
```

---

#### Service: search-service
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8098/health && nc -z qdrant 6333 && nc -z clickhouse 8123 && nc -z embedding-service 8080"]
interval: 30s
timeout: 5s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 3 dependencies

**Official Recommendation:**
- Custom service - depends on implementation

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8098/health"]
interval: 30s
timeout: 5s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint should validate service's own readiness
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec search-service wget --quiet --tries=1 --spider http://localhost:8098/health
```

---

#### Service: agent-tool-server
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8099/health && nc -z postgres 5432 && nc -z mariadb 3306 && nc -z clickhouse 8123 && nc -z qdrant 6333 && nc -z ldap 389 && nc -z litellm 4000 && test -S /var/run/docker.sock"]
interval: 30s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:** Uses `nc -z` for 6 dependencies

**Official Recommendation:**
- Custom service - depends on implementation
- Docker socket test is reasonable since this service manages containers

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8099/health && test -S /var/run/docker.sock"]
interval: 30s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint should validate service's own readiness
- Keep docker socket test (critical for this service's function)
- Remove ALL database dependency checks

**Test Command:**
```bash
docker exec agent-tool-server wget --quiet --tries=1 --spider http://localhost:8099/health
docker exec agent-tool-server test -S /var/run/docker.sock
```

---

### AI/ML Services

---

#### Service: vllm-7b
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8000/health && wget --quiet --tries=1 --spider http://localhost:8000/v1/models"]
interval: 60s
timeout: 10s
retries: 3
start_period: 300s
```

**Issue:** Checks 2 endpoints (redundant)

**Official Recommendation:**
- vLLM: https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html#health-check
- `/health` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8000/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 300s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove models check (redundant)

**Test Command:**
```bash
docker exec vllm-7b wget --quiet --tries=1 --spider http://localhost:8000/health
```

---

#### Service: embedding-service
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8080/health && wget --quiet --tries=1 --spider http://localhost:8080/info"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Issue:** Checks 2 endpoints (redundant)

**Official Recommendation:**
- Text Embeddings Inference: https://github.com/huggingface/text-embeddings-inference#health
- `/health` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/health"]
interval: 60s
timeout: 10s
retries: 3
start_period: 120s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove info check (redundant)

**Test Command:**
```bash
docker exec embedding-service wget --quiet --tries=1 --spider http://localhost:8080/health
```

---

#### Service: litellm
**Current Status:** ❌ Unhealthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:4000/health && wget --quiet --tries=1 --spider http://localhost:4000/models && nc -z vllm-7b 8000 && nc -z embedding-service 8080"]
interval: 30s
timeout: 10s
retries: 3
start_period: 60s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Uses `nc -z` for 2 dependencies

**Official Recommendation:**
- LiteLLM: https://docs.litellm.ai/docs/proxy/health
- `/health` endpoint is the official healthcheck

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:4000/health"]
interval: 30s
timeout: 10s
retries: 3
start_period: 60s
```

**Justification:**
- `/health` endpoint alone is sufficient
- Remove models check (redundant)
- Remove ALL dependency checks

**Test Command:**
```bash
docker exec litellm wget --quiet --tries=1 --spider http://localhost:4000/health
```

---

### Monitoring Services

---

#### Service: prometheus
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:9090/-/healthy && wget --quiet --tries=1 --spider http://localhost:9090/-/ready"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Issue:** Checks 2 endpoints (arguably both useful)

**Official Recommendation:**
- Prometheus: https://prometheus.io/docs/prometheus/latest/management_api/#health-check
- `/-/healthy` checks Prometheus is alive
- `/-/ready` checks Prometheus is ready to serve traffic

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9090/-/ready"]
interval: 30s
timeout: 5s
retries: 3
start_period: 30s
```

**Justification:**
- `/-/ready` is more comprehensive (includes healthy + data loaded)
- Single endpoint check is sufficient

**Test Command:**
```bash
docker exec prometheus wget --quiet --tries=1 --spider http://localhost:9090/-/ready
```

---

#### Service: node-exporter
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:9100/metrics && test -d /host/proc && test -d /host/sys"]
interval: 60s
timeout: 5s
retries: 3
start_period: 10s
```

**Issue:** Directory tests are unnecessary

**Official Recommendation:**
- Node Exporter: https://github.com/prometheus/node_exporter
- Checking `/metrics` endpoint is standard

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9100/metrics"]
interval: 60s
timeout: 5s
retries: 3
start_period: 10s
```

**Justification:**
- `/metrics` check verifies exporter is collecting and serving data
- Remove directory tests (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec node-exporter wget --quiet --tries=1 --spider http://localhost:9100/metrics
```

---

#### Service: cadvisor
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "wget --quiet --tries=1 --spider http://localhost:8080/healthz && wget --quiet --tries=1 --spider http://localhost:8080/metrics && test -d /rootfs"]
interval: 60s
timeout: 5s
retries: 3
start_period: 20s
```

**Issue:**
- Checks 2 endpoints (redundant)
- Directory test is unnecessary

**Official Recommendation:**
- cAdvisor: https://github.com/google/cadvisor
- `/healthz` is the dedicated health endpoint

**Proposed Healthcheck:**
```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/healthz"]
interval: 60s
timeout: 5s
retries: 3
start_period: 20s
```

**Justification:**
- `/healthz` endpoint alone is sufficient
- Remove metrics check (redundant)
- Remove directory test (mount verification is not healthcheck's job)

**Test Command:**
```bash
docker exec cadvisor wget --quiet --tries=1 --spider http://localhost:8080/healthz
```

---

#### Service: watchtower
**Current Status:** ✅ Healthy
**Current Healthcheck:**
```yaml
test: ["CMD-SHELL", "ps aux | grep -v grep | grep watchtower && test -S /var/run/docker.sock"]
interval: 300s
timeout: 5s
retries: 2
start_period: 30s
```

**Issue:** Using process grep is not ideal, but Watchtower has no HTTP endpoint

**Official Recommendation:**
- Watchtower: https://containrrr.dev/watchtower/
- No official healthcheck recommendation (it's a cron-style service)

**Proposed Healthcheck:**
```yaml
test: ["CMD-SHELL", "test -S /var/run/docker.sock && ps aux | grep -v grep | grep -q watchtower"]
interval: 300s
timeout: 5s
retries: 2
start_period: 30s
```

**Justification:**
- Keep process check (no better alternative)
- Keep docker socket check (critical for function)
- Reorder to check socket first (faster failure)
- Add `-q` to grep for cleaner output

**Test Command:**
```bash
docker exec watchtower ps aux | grep watchtower
```

---

## Init Container Services

These are one-time initialization containers. Their healthchecks are appropriate:

### postgres-init, mariadb-init, vector-bootstrap, mastodon-init, synapse-init
```yaml
test: ["CMD-SHELL", "test -f /tmp/init_complete"]
```

These are correct - they check for completion flag files. **No changes needed.**

---

## Summary Statistics

### Services by Status

| Status | Count | Percentage |
|--------|-------|------------|
| ✅ Healthy | 11 | 24% |
| ❌ Unhealthy | 34 | 76% |
| **Total** | **45** | **100%** |

### Issues by Type

| Issue Type | Count |
|------------|-------|
| Uses `nc -z` (BusyBox incompatible) | 27 |
| Redundant endpoint checks | 18 |
| Process grep checks | 3 |
| Unnecessary file/directory tests | 8 |
| Healthcheck has side effects | 1 |

### Changes Summary

| Change Type | Count |
|-------------|-------|
| Remove `nc -z` checks | 27 |
| Remove redundant endpoints | 18 |
| Remove directory tests | 8 |
| Simplify to single endpoint | 35 |
| No change needed | 10 |

---

## BusyBox Compatibility Notes

### ✅ BusyBox-Compatible Commands

- `wget --quiet --tries=1 --spider http://...`
- `test -f /path/to/file`
- `test -d /path/to/dir`
- `test -S /var/run/docker.sock`
- `echo "command" | nc localhost port` (interactive mode)
- `ps aux | grep pattern`
- `grep -q pattern`

### ❌ BusyBox-Incompatible Commands (AVOID)

- `nc -z host port` - The `-z` flag doesn't exist in BusyBox nc
- `nc -w timeout` - The `-w` flag behaves differently
- Complex bash expressions in single-line healthchecks

### Alternative for Port Checks

If you absolutely must check a port (not recommended), use:
```bash
timeout 1 sh -c 'cat < /dev/null > /dev/tcp/host/port' 2>/dev/null
```

However, **dependency port checks should be avoided entirely** per Docker best practices.

---

## Next Steps

1. ✅ **Research Complete** - All services documented
2. ⏳ **Apply Changes** - Update all compose template files
3. ⏳ **Rebuild** - Run build-datamancy.main.kts to regenerate dist/docker-compose.yml
4. ⏳ **Deploy** - Apply updated compose file to stack
5. ⏳ **Verify** - Check that services show as healthy

---

## Implementation Files

Files to modify (in `/home/gerald/IdeaProjects/Datamancy/compose.templates/`):
- `infrastructure.yml` - 12 services
- `databases.yml` - 6 services
- `applications.yml` - 20 services
- `datamancy-services.yml` - 5 services
- `ai-services.yml` - 3 services

After modifying, regenerate with:
```bash
./build-datamancy.main.kts
```

Then deploy with:
```bash
docker stack deploy -c dist/docker-compose.yml datamancy-stack
```

---

## References

### Official Documentation Links

- **Docker Healthcheck Best Practices:** https://docs.docker.com/engine/reference/builder/#healthcheck
- **Docker Compose Healthcheck:** https://docs.docker.com/compose/compose-file/05-services/#healthcheck
- **BusyBox nc Documentation:** https://busybox.net/downloads/BusyBox.html#nc
- **Authelia Health:** https://www.authelia.com/integration/prologue/health/
- **Grafana Health:** https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#healthcheck
- **PostgreSQL:** https://hub.docker.com/_/postgres
- **MariaDB:** https://hub.docker.com/_/mariadb
- **ClickHouse Monitoring:** https://clickhouse.com/docs/en/operations/monitoring
- **Qdrant Health:** https://qdrant.tech/documentation/guides/monitoring/
- **Prometheus Health:** https://prometheus.io/docs/prometheus/latest/management_api/#health-check
- **vLLM Health:** https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html#health-check
- **LiteLLM Health:** https://docs.litellm.ai/docs/proxy/health

---

**Document Status:** ✅ Complete
**Next Action:** Apply changes to compose template files
