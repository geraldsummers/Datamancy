# Web3 Wallet Extension Testing Guide

Comprehensive testing strategy for the JupyterLab Web3 Wallet Extension.

## Test Pyramid

```
        ┌─────────────────────────┐
        │   E2E Browser Tests     │  ← Playwright with MetaMask
        │   (ui-tests/)           │
        ├─────────────────────────┤
        │  Integration Tests      │  ← test-runner suite
        │  (test-runner)          │
        ├─────────────────────────┤
        │   Unit Tests            │  ← Python pytest + TypeScript
        │   (tests/)              │
        └─────────────────────────┘
```

## 1. Unit Tests

### Python Backend Tests

**Location:** `containers.src/jupyterlab-web3-wallet/tests/`

**Run:**
```bash
cd containers.src/jupyterlab-web3-wallet
pip install -r tests/requirements.txt
pytest tests/ -v
```

**Coverage:**
- `Web3WalletHandler` HTTP endpoints
- `Web3WalletMagicHandler` magic command
- Extension registration and metadata
- Error handling for invalid requests

### TypeScript/JavaScript Tests

**Location:** `containers.src/jupyterlab-web3-wallet/src/`

**Run:**
```bash
cd containers.src/jupyterlab-web3-wallet
jlpm install
jlpm test
```

**Coverage:**
- `Web3WalletManager` connection logic
- `Web3WalletWidget` UI rendering
- State persistence via `IStateDB`
- MetaMask/WalletConnect integration

## 2. Integration Tests

### Kotlin Test Runner Suite

**Location:** `kotlin.src/test-runner/src/main/kotlin/org/datamancy/testrunner/suites/Web3WalletTests.kt`

**Run:**
```bash
# Inside Docker stack
docker exec test-runner java -jar test-runner.jar --suite=web3-wallet

# From localhost (with stack running)
./gradlew :test-runner:shadowJar
java -jar kotlin.src/test-runner/build/libs/test-runner-*-all.jar \
  --suite=web3-wallet \
  --env=localhost
```

**Test Coverage:**

1. **Extension Installation**
   - Verifies extension is loaded in JupyterLab
   - Checks HTTP handlers are registered
   - Validates package metadata

2. **API Endpoints**
   - `GET /datamancy/web3-wallet` - wallet status
   - `GET /datamancy/web3-wallet/magic` - magic command
   - `POST /datamancy/web3-wallet` - transaction signing

3. **Trading SDK Integration**
   - `Web3WalletProvider` class availability
   - `TxGateway.fromWallet()` method exists
   - Correct error messages when disconnected

4. **Cross-Service Communication**
   - test-runner → JupyterHub connectivity
   - Wallet provider → JupyterHub API calls
   - TX Gateway → Wallet coordination

**Environment Variables:**
```bash
JUPYTERHUB_URL=http://jupyterhub:8000
TX_GATEWAY_URL=http://tx-gateway:8080
```

## 3. E2E Browser Tests

### Playwright Tests

**Location:** `containers.src/jupyterlab-web3-wallet/ui-tests/`

**Run:**
```bash
cd containers.src/jupyterlab-web3-wallet
npm install -D @playwright/test
npx playwright install chromium
npx playwright test
```

**Test Scenarios:**

1. **Widget Interaction**
   - Open wallet widget from command palette
   - Click "Connect Wallet" button
   - Verify UI state changes

2. **MetaMask Integration** (requires MetaMask)
   - Trigger MetaMask connection popup
   - Approve connection
   - Verify address and chain ID displayed

3. **Notebook Usage**
   - Create Kotlin notebook
   - Run `%walletConnect` magic
   - Import trading SDK
   - Call `TxGateway.fromWallet()`

4. **State Persistence**
   - Connect wallet
   - Refresh JupyterLab
   - Verify wallet stays connected

**MetaMask Setup for Tests:**
```typescript
// playwright.config.ts
use: {
  args: [
    `--disable-extensions-except=${METAMASK_PATH}`,
    `--load-extension=${METAMASK_PATH}`,
  ],
}
```

## 4. Manual Testing Checklist

### Initial Setup
- [ ] JupyterLab extension appears in extension manager
- [ ] No console errors on JupyterLab startup
- [ ] Command palette shows "🦊 Connect Web3 Wallet"

### Wallet Connection
- [ ] Click "Connect Wallet" → MetaMask popup appears
- [ ] Approve connection → Status shows "Connected"
- [ ] Address displayed in truncated format
- [ ] Correct chain ID shown (1 for mainnet, 8453 for Base, etc.)
- [ ] Status indicator turns green

### Wallet Disconnection
- [ ] Click "Disconnect" → Status returns to "Not Connected"
- [ ] Address and chain cleared
- [ ] Status indicator turns gray

### Kotlin Notebook Integration
- [ ] Create new Kotlin notebook
- [ ] Import trading SDK: `@file:DependsOn("org.datamancy:trading-sdk:1.0.0")`
- [ ] Call `TxGateway.fromWallet()` → prints wallet info
- [ ] Call without connected wallet → helpful error message

### Transaction Signing
- [ ] Execute `tx.evm.transfer(...)` → MetaMask popup appears
- [ ] Approve in MetaMask → transaction submitted
- [ ] Reject in MetaMask → error returned to notebook
- [ ] Check TX Gateway logs for signed transaction

### Session Persistence
- [ ] Connect wallet
- [ ] Close JupyterLab tab
- [ ] Reopen → Wallet still connected
- [ ] Run notebook → Wallet info available

### Multi-Chain Support
- [ ] Switch to Base in MetaMask → Widget updates chain ID
- [ ] Switch to Arbitrum → Widget updates
- [ ] Switch to Ethereum → Widget updates

## 5. Performance Testing

### Load Testing
```bash
# Simulate 100 wallet connections
for i in {1..100}; do
  curl -X POST http://localhost:8888/datamancy/web3-wallet \
    -H "Content-Type: application/json" \
    -d '{"operation":"get_wallet_info"}'
done
```

### Stress Testing
- Open 10 notebooks simultaneously
- All call `TxGateway.fromWallet()`
- Verify no race conditions or memory leaks

## 6. Security Testing

### Input Validation
- [ ] Invalid JSON → 400 Bad Request
- [ ] Unknown operation → 400 with error message
- [ ] Missing transaction fields → proper validation

### Authentication
- [ ] Unauthenticated requests rejected (if auth enabled)
- [ ] CSRF protection enabled
- [ ] No wallet private keys in logs

### XSS Prevention
- [ ] Address display escapes HTML
- [ ] Transaction data sanitized
- [ ] No `eval()` or unsafe innerHTML

## 7. Debugging

### Enable Verbose Logging

**Python:**
```python
import logging
logging.basicConfig(level=logging.DEBUG)
```

**Browser Console:**
```javascript
localStorage.setItem('debug', 'jupyterlab:*');
```

**Test Runner:**
```bash
java -jar test-runner.jar --suite=web3-wallet --verbose
```

### Common Issues

**Extension not loading:**
- Check `jupyter labextension list`
- Verify build: `jlpm build`
- Reinstall: `jupyter labextension develop . --overwrite`

**Wallet not connecting:**
- Check MetaMask is unlocked
- Verify `window.ethereum` exists in console
- Check browser console for errors

**API endpoints 404:**
- Verify handler registration in logs
- Check JupyterHub base URL
- Confirm extension loaded: `pip show datamancy-jupyterlab-web3-wallet`

## 8. CI/CD Integration

### GitHub Actions Workflow

```yaml
# .github/workflows/test.yml
- Run Python unit tests
- Run TypeScript build
- Run Playwright E2E tests
- Run integration tests (with full stack)
- Generate coverage reports
- Upload artifacts
```

### Test Environments

**Development:**
- Local JupyterLab with `--watch` mode
- Manual MetaMask testing
- Quick iteration

**CI:**
- Automated pytest + Playwright
- Mock wallet connections
- Headless browser testing

**Staging:**
- Full stack deployment
- Real blockchain testnets (Sepolia, Base Goerli)
- Integration with live TX Gateway

**Production:**
- Smoke tests only
- Monitoring for extension load errors
- Real user analytics

## 9. Test Data

### Mock Wallet Info
```json
{
  "address": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
  "chainId": 8453,
  "provider": "metamask",
  "connected": true
}
```

### Test Transactions
```kotlin
// Safe test transfer (always fails in tests, no real funds)
tx.evm.transfer(
    toAddress = "0x0000000000000000000000000000000000000000",
    amount = 0.toBigDecimal(),
    token = Token.USDC,
    chain = Chain.BASE
)
```

## 10. Metrics & Monitoring

**Track:**
- Extension load time
- Wallet connection success rate
- Transaction signing latency
- Error rates by type
- Browser compatibility

**Tools:**
- JupyterLab built-in logging
- Browser performance API
- Custom telemetry to Prometheus

## Running All Tests

```bash
# Python unit tests
pytest tests/ -v --cov

# TypeScript build check
jlpm build

# Integration tests
java -jar test-runner.jar --suite=web3-wallet

# E2E browser tests
npx playwright test

# Full suite
./scripts/run-all-wallet-tests.sh
```

## Next Steps

1. Set up CI/CD pipeline for automated testing
2. Add MetaMask automation to Playwright tests
3. Implement performance benchmarks
4. Create test fixtures for common scenarios
5. Add visual regression testing for widget UI
