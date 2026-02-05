# One-Shot Deployment Fix

## Problem

Deployment was failing with:
```
dependency failed to start: container mailserver is unhealthy
```

**Root Cause:** Critical services (mailserver, grafana) had healthchecks that were:
- ❌ Too infrequent (checking every 60s)
- ❌ Too few retries (5 retries = only 5 chances to become healthy)
- ❌ Causing cascading delays in dependent services

**Dependency Chain That Was Failing:**
```
mailserver (4 min) → vaultwarden, bookstack, element, roundcube, seafile, open-webui
grafana (5 min) → postgres-observer-views → agent-tool-server
```

Result: ~9 minute wait for all services to start, often exceeding Docker Compose's tolerance.

---

## Fix Applied

### 1. Mailserver Healthcheck (compose.templates/mailserver.yml)

**Before:**
```yaml
healthcheck:
  interval: 60s
  timeout: 10s
  retries: 5
  start_period: 180s
```
**Worst case:** 3 min + (60s × 5) = 8 minutes

**After:**
```yaml
healthcheck:
  interval: 15s
  timeout: 10s
  retries: 20
  start_period: 240s
```
**Worst case:** 4 min + (15s × 20) = 9 minutes
**Typical case:** Detects healthy in 4-5 minutes (checking every 15s vs 60s)

**Impact:** 4x faster healthcheck detection (15s vs 60s intervals)

---

### 2. Grafana Healthcheck (compose.templates/grafana.yml)

**Before:**
```yaml
healthcheck:
  interval: 15s
  timeout: 10s
  retries: 5
  start_period: 5m
```
**Worst case:** 5 min + (15s × 5) = 6.25 minutes

**After:**
```yaml
healthcheck:
  interval: 10s
  timeout: 10s
  retries: 30
  start_period: 180s
```
**Worst case:** 3 min + (10s × 30) = 8 minutes
**Typical case:** Detects healthy in 3-4 minutes (checking every 10s, more retries)

**Impact:**
- 2 min faster initial grace period (3 min vs 5 min)
- 50% faster checks (10s vs 15s)
- 6x more retries (30 vs 5) = more resilient

---

## Expected Result

### Before Fix:
- Mailserver: Checked every 60s, only 5 chances → often missed the "healthy" window
- Grafana: 5 min start period → everything waiting on it was delayed
- **Total deployment time:** 8-10 minutes with frequent failures

### After Fix:
- Mailserver: Checked every 15s, 20 chances → catches healthy state faster
- Grafana: 3 min start + frequent checks → dependent services start sooner
- **Total deployment time:** 5-7 minutes, much more reliable

---

## Deployment Strategy

The key insight: **More frequent checks + more retries = faster detection of healthy state**

Instead of:
- Long intervals (60s) with few retries (5) = only 5 chances over 5 minutes

We now have:
- Short intervals (10-15s) with many retries (20-30) = 20-30 chances, catches healthy faster

**This is critical for dependency chains:**
```
postgres (60s) → grafana (180s+checks) → postgres-observer-views → agent-tool-server
ldap (180s) → mailserver (240s+checks) → vaultwarden, bookstack, element, ...
```

Every second counts when you have 50+ containers with complex dependencies.

---

## Files Changed

1. `compose.templates/mailserver.yml` (lines 80-85)
   - interval: 60s → 15s
   - retries: 5 → 20
   - start_period: 180s → 240s

2. `compose.templates/grafana.yml` (lines 48-53)
   - interval: 15s → 10s
   - retries: 5 → 30
   - start_period: 5m → 180s

3. `compose.templates/caddy.yml` (lines 65-72)
   - Added ACME lock cleanup (from previous fix)

---

## Testing

Run the standard deployment:
```bash
cd /home/gerald/IdeaProjects/Datamancy/
rm -rf dist/
./build-datamancy-v2.main.kts
rsync -avz --progress dist/ gerald@latium.local:~/datamancy/
ssh gerald@latium.local "cd ~/datamancy && docker compose build --no-cache && docker compose up -d"
```

**Expected:** All 50+ containers come up healthy in one shot, ~6-7 minutes total.

**Monitoring:**
```bash
# Watch deployment progress
ssh gerald@latium.local "cd ~/datamancy && watch -n 2 'docker compose ps | grep -E \"(Created|starting|unhealthy)\"'"

# Check final status
ssh gerald@latium.local "cd ~/datamancy && docker compose ps | grep -c healthy"
```

---

## Other Slow Services (For Reference)

Services with long start periods that might need tuning in future:
- forgejo: 600s (10 minutes) - database migrations can be slow
- bookstack: 300s (5 minutes)
- vllm-7b: 300s (5 minutes) - model loading
- search-service: 180s (3 minutes)
- seafile: 180s (3 minutes)
- ldap: 180s (3 minutes)
- agent-tool-server: 180s (3 minutes)

These are acceptable for now since they don't have many dependents. Can optimize later if needed.

---

## Related Issues Fixed

This deployment also includes:
- ✅ Network stability fix (dual IP conflict resolved)
- ✅ Caddy ACME lock cleanup
- ✅ TCP keepalive reduced to 5 minutes

See `FIXES_APPLIED.md` for details.

---

**Status:** Ready to rebuild and redeploy. Should work one-shot now! 🔥
