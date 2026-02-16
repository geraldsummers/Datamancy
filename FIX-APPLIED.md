# Fix Applied: Authelia OIDC Template Substitution

**Date:** 2026-02-16 14:35 AEDT
**Commit:** b8caded
**Status:** ✅ FIXED

---

## What Was Fixed

### The Bug
OAuth secret hashes in `authelia/configuration.yml` were NOT being substituted, causing `invalid_client` errors in OIDC authentication.

### The Root Cause
The template rule for Authelia only substituted the RSA key and didn't have `then_convert_remaining: true`, so OAuth hashes remained as literal `{{TEMPLATE_VARS}}`.

### The Fix
Added `then_convert_remaining: true` to the authelia template rule in `compose.settings/credentials.schema.yaml`:

```yaml
- name: authelia_oidc_key
  path_pattern: "authelia/configuration.yml"
  substitutions:
    AUTHELIA_OIDC_PRIVATE_KEY:
      transform: multiline_yaml_indent
  then_convert_remaining: true  # ← ADDED THIS LINE
```

---

## Verification

### Before Fix
```yaml
# dist/configs/authelia/configuration.yml (WRONG)
client_secret: '{{TEST_RUNNER_OAUTH_SECRET_HASH}}'  # ← Template variable!
```

### After Fix
```yaml
# dist/configs/authelia/configuration.yml (CORRECT)
client_secret: '$argon2id$v=19$m=65536,t=3,p=4$ceKhiQEcUX/a0dbkBnzo6w$WRRu+7AKn62a3WxRNjm1j/52Xjwlqy/8La+RncyQpa8'  # ← Actual hash!
```

### Confirmed
- ✅ No remaining `{{}}` templates in dist/configs/authelia/configuration.yml
- ✅ OAuth secret hashes properly substituted
- ✅ Build successful with 125 credentials generated
- ✅ All unit tests passing (Kotlin, Python, TypeScript)

---

## Impact

### Tests Expected to Pass After Deployment
1. **OIDC Phase 2: Authorization code flow** ✅
2. **OIDC Phase 2: ID token validation** ✅
3. **OIDC Phase 2: Refresh token flow** ✅

### Additional Fixes Likely
4. **JupyterHub OIDC login** (Playwright) - Depends on OIDC fix
5. **Forgejo OIDC login** (Playwright) - Depends on OIDC fix
6. **Planka OIDC login** (Playwright) - Depends on OIDC fix

### Success Rate Improvement
- **Before:** 369/382 (96.6%)
- **After:** Expected 376/382+ (98.4%+)

---

## Next Steps

### 1. Deploy to Server
```bash
# On your local machine, copy dist/ to server
# (Your deployment process)

# Then on server
ssh gerald@latium.local "cd ~/datamancy && docker compose restart authelia integration-test-runner"
```

### 2. Verify on Server
```bash
# Check Authelia has correct hash
ssh gerald@latium.local "grep -A 3 'client_id: test-runner' ~/datamancy/configs/authelia/configuration.yml | grep client_secret"
# Should show: client_secret: '$argon2id$...' (NOT {{TEMPLATE}})

# Check test runner has plaintext
ssh gerald@latium.local "docker compose exec integration-test-runner env | grep OIDC_CLIENT_SECRET"
# Should show: OIDC_CLIENT_SECRET=<plaintext>
```

### 3. Run Tests
```bash
ssh gerald@latium.local "cd ~/datamancy && docker compose exec integration-test-runner java -jar /app/test-runner.jar --env container --suite enhanced-auth"
```

### 4. Expected Results
- All 3 OIDC Phase 2 tests should pass
- Likely 4 Playwright tests will also pass (they depend on OIDC)
- **Total:** 377+/382 tests passing

---

## Files Changed

1. **compose.settings/credentials.schema.yaml** - Fixed template rule
2. **AUTOMATED-HASHING-GUIDE.md** - Documentation on hashing system
3. **HASH-BUG-FIX.md** - Detailed bug analysis and fix guide
4. **PRECISE-DIAGNOSIS-AND-FIXES-20260216.md** - All 13 test failures diagnosed
5. **test-report-20260216-comprehensive.md** - Full test report

---

## Lessons Learned

### Why This Bug Existed
1. Template processing had two modes:
   - Direct substitution (with transforms)
   - Bulk conversion (`then_convert_remaining: true`)
2. Authelia rule only used direct substitution for RSA key
3. OAuth hashes were not in the substitution list
4. Without `then_convert_remaining`, they were never processed

### Prevention
Add validation to build script to catch unsubstituted templates:
```kotlin
fun validateTemplateSubstitution(outputDir: File) {
    val unsubstituted = Regex("\\{\\{([A-Z_]+)}}").findAll(content)
    if (unsubstituted.isNotEmpty()) {
        error("Unsubstituted templates found!")
    }
}
```

---

## Status

**FIX COMPLETE ✅**

Ready for deployment and testing!

---

**Fixed by:** Claude (with your direction)
**Build Version:** b8caded
**Next:** Deploy to latium.local and verify
