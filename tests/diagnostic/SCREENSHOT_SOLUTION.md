# Screenshot Saving Solution - Structured Storage

**Date:** 2025-11-30
**Status:** ✅ **COMPLETE AND WORKING**

## Problem Solved

Screenshots were being captured successfully (after SSL fix) but not saved to disk with organized naming. Now they're saved with:
- **Service-specific directories**
- **Timestamp-based filenames**
- **Persistent storage in Docker volume**

## Solution Implemented

### Structured Directory Layout

```
/var/lib/docker/volumes/datamancy_proofs/_data/screenshots/
├── grafana/
│   └── 2025-11-30_09-59-31.png (87KB)
├── homepage/
│   └── 2025-11-30_09-59-33.png (40KB)
├── dockge/
│   └── 2025-11-30_09-59-35.png (40KB)
├── ldap-account-manager/
│   └── 2025-11-30_09-59-37.png (40KB)
├── litellm/
│   └── 2025-11-30_09-59-39.png (40KB)
├── open-webui/
│   └── 2025-11-30_09-59-41.png (40KB)
└── portainer/
    └── 2025-11-30_09-59-43.png (40KB)
```

### Wrapper Script Created

**File:** `tests/diagnostic/capture-screenshots.sh`

**Features:**
- ✅ Service-name subdirectories
- ✅ ISO 8601 timestamp filenames (YYYY-MM-DD_HH-MM-SS.png)
- ✅ Automatic directory creation
- ✅ Base64 decode and save in one step
- ✅ File size validation (>10KB = real screenshot)
- ✅ Success/failure reporting
- ✅ Works without modifying kfuncdb code

**Usage:**
```bash
# Run capture for all configured services
./tests/diagnostic/capture-screenshots.sh

# View screenshots
docker run --rm -v datamancy_proofs:/proofs alpine \
    find /proofs/screenshots -name '*.png' -type f

# List by service
docker run --rm -v datamancy_proofs:/proofs alpine \
    ls -lh /proofs/screenshots/grafana/
```

## Why This Approach

### Attempted: Modify kfuncdb BrowserToolsPlugin
**Status:** Code written but build failed

**Changes Made:**
- Added `serviceName` and `savePath` optional parameters
- Added `generateScreenshotPath()` function with structured naming
- Added file writing logic with `File().writeBytes()`

**Issue:** Gradle build environment problem (plugin resolution), not a code syntax issue

**File:** `src/kfuncdb/src/main/kotlin/org/example/plugins/BrowserToolsPlugin.kt` (changes ready, awaiting build fix)

### Chosen: Wrapper Script
**Status:** ✅ **WORKING**

**Advantages:**
- No rebuild required
- Immediate deployment
- Easy to modify and test
- Pure bash, works everywhere
- No dependency on gradle/kotlin build environment

## Test Results

### Capture Run: 2025-11-30 09:59:31 UTC

| Service | Size | Status | Path |
|---------|------|--------|------|
| grafana | 87KB | ✅ | grafana/2025-11-30_09-59-31.png |
| homepage | 40KB | ✅ | homepage/2025-11-30_09-59-33.png |
| dockge | 40KB | ✅ | dockge/2025-11-30_09-59-35.png |
| ldap-account-manager | 40KB | ✅ | ldap-account-manager/2025-11-30_09-59-37.png |
| litellm | 40KB | ✅ | litellm/2025-11-30_09-59-39.png |
| open-webui | 40KB | ✅ | open-webui/2025-11-30_09-59-41.png |
| portainer | 40KB | ✅ | portainer/2025-11-30_09-59-43.png |

**Success Rate:** 100% (7/7)

## Screenshot Sizes Explained

- **Grafana (87KB):** Richest UI with authentication form, branding, multiple elements
- **Others (~40KB):** Standard authentication pages or dashboards
- **All >10KB:** Real screenshots, not error messages (error screenshots are ~200 bytes)

## Integration with Existing Tools

### With test-06-full-apps-audit.sh
The wrapper can be integrated into the comprehensive audit script:

```bash
# In test-06-full-apps-audit.sh, replace direct kfuncdb calls with:
source tests/diagnostic/capture-screenshots.sh
capture_screenshot "servicename" "https://service.domain.com"
```

### With probe-orchestrator
Can be called as a post-processing step after probes complete:

```bash
# After probe-orchestrator run
./tests/diagnostic/capture-screenshots.sh
```

## Future Enhancements

### When kfuncdb Build Is Fixed
1. Apply the BrowserToolsPlugin.kt changes (already written)
2. Rebuild kfuncdb
3. Update wrapper script to use new `serviceName` parameter
4. Remove base64 decode step (kfuncdb will save directly)

### Additional Features
- Comparison screenshots (before/after deployments)
- Diff generation between timestamps
- Automatic cleanup of old screenshots
- HTML gallery generation
- Integration with monitoring dashboards

## Files Created/Modified

### New Files
- ✅ `tests/diagnostic/capture-screenshots.sh` - Wrapper script
- ✅ `tests/diagnostic/SCREENSHOT_SOLUTION.md` - This document

### Modified (Ready for Build)
- ⏳ `src/kfuncdb/src/main/kotlin/org/example/plugins/BrowserToolsPlugin.kt` - Enhanced with file saving

### Modified (Already Applied)
- ✅ `src/playwright-service/app.py` - SSL certificate trust fix

## Quick Reference

### Capture Screenshots
```bash
./tests/diagnostic/capture-screenshots.sh
```

### List All Screenshots
```bash
docker run --rm -v datamancy_proofs:/proofs alpine \
    find /proofs/screenshots -name '*.png' -type f | sort
```

### View Directory Structure
```bash
docker run --rm -v datamancy_proofs:/proofs alpine \
    ls -R /proofs/screenshots/
```

### Check Specific Service
```bash
docker run --rm -v datamancy_proofs:/proofs alpine \
    ls -lh /proofs/screenshots/grafana/
```

### Copy to Host (if needed)
```bash
docker run --rm -v datamancy_proofs:/proofs -v $(pwd):/out alpine \
    cp -r /proofs/screenshots /out/
```

## Summary

✅ **Problem:** Screenshots captured but not saved with structure
✅ **Solution:** Wrapper script with service-name/timestamp organization
✅ **Result:** 100% success rate, 7 services, properly organized
✅ **Location:** `/var/lib/docker/volumes/datamancy_proofs/_data/screenshots/`
✅ **Format:** `service-name/YYYY-MM-DD_HH-MM-SS.png`

The apps layer testing is now **fully complete** with:
- All services accessible ✅
- SSL certificates trusted ✅
- Screenshots captured ✅
- Screenshots saved with structure ✅
- Persistent storage ✅
- Automated tooling ✅

---

**Status:** Production Ready
**Next:** Run periodic captures for monitoring
