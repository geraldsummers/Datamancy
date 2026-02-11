# Testing the Web3 Wallet Extension

## Quick Start

Run all Web3 wallet tests using the test-runner container:

```bash
docker exec test-runner java -jar /app/test-runner.jar --suite=web3-wallet
```

Or run as part of the full test suite:

```bash
docker exec test-runner java -jar /app/test-runner.jar --suite=all
```

## Test Coverage

The `web3-wallet` test suite covers:

- ✅ JupyterLab extension installation and loading
- ✅ HTTP API endpoints (`/datamancy/web3-wallet/*`)
- ✅ Trading SDK integration (`TxGateway.fromWallet()`)
- ✅ Wallet provider class structure
- ✅ Cross-service communication (test-runner ↔ JupyterHub)
- ✅ TX Gateway integration readiness

## What Gets Tested

### 1. Extension Installation
- Extension is loaded in JupyterLab
- HTTP handlers are registered
- Python package metadata is correct

### 2. API Endpoints
- `GET /datamancy/web3-wallet` returns wallet status
- `GET /datamancy/web3-wallet/magic` responds for magic command
- `POST /datamancy/web3-wallet` accepts transaction signing requests

### 3. Kotlin SDK Integration
- `Web3WalletProvider` class exists
- `WalletInfo` data class has correct fields
- `TxGateway.fromWallet()` method is available

### 4. Integration Points
- Network connectivity between test-runner and JupyterHub
- TX Gateway can coordinate with wallet
- Error messages guide users correctly

## Manual Testing

For browser-based testing with MetaMask:

1. **Open JupyterLab:**
   ```
   http://your-domain:8000
   ```

2. **Connect Wallet:**
   - Open command palette: `Ctrl+Shift+C`
   - Search: "Connect Web3 Wallet"
   - Approve in MetaMask

3. **Test in Kotlin Notebook:**
   ```kotlin
   @file:DependsOn("org.datamancy:trading-sdk:1.0.0")

   import org.datamancy.trading.*

   val tx = TxGateway.fromWallet(
       url = "http://tx-gateway:8080",
       token = System.getenv("TX_AUTH_TOKEN")
   )

   // Should print: ✓ Connected to metamask wallet: 0x...
   ```

4. **Execute Transaction:**
   ```kotlin
   tx.evm.transfer(
       toUser = "alice",
       amount = 100.toBigDecimal(),
       token = Token.USDC,
       chain = Chain.BASE
   )
   // MetaMask popup should appear for approval
   ```

## Test Results

Results are saved to `/app/test-results/` with:
- `summary.txt` - Test summary
- `detailed.log` - Full output
- `failures.log` - Failed tests (if any)
- `metadata.txt` - Run metadata

## CI/CD Integration

The test-runner container automatically runs Web3 wallet tests as part of the standard CI/CD pipeline. No additional configuration needed.

## Troubleshooting

**Extension not found:**
- Check: `docker exec jupyterhub jupyter labextension list`
- Should show: `@datamancy/jupyterlab-web3-wallet`

**API endpoints 404:**
- Extension may not be installed
- Check JupyterHub logs: `docker logs jupyterhub`

**Trading SDK tests fail:**
- Verify trading-sdk is compiled: `./gradlew :trading-sdk:build`
- Check SDK version matches in tests

## Development

For local Python unit tests (optional):

```bash
cd containers.src/jupyterlab-web3-wallet
pip install -r tests/requirements.txt
pytest tests/ -v
```

For TypeScript build verification (optional):

```bash
cd containers.src/jupyterlab-web3-wallet
jlpm install
jlpm build
```

These are already handled in the Docker build, so only needed for local development iteration.
