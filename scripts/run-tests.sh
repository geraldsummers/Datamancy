#!/bin/bash
set -e

cd "$(dirname "$0")/.."

docker run --rm \
  --network infra \
  -v "$(pwd)/scripts/test-runner:/tests" \
  -v "$(pwd)/data/tests:/results" \
  -v "$(pwd)/certs/ca.crt:/usr/local/share/ca-certificates/test-ca.crt:ro" \
  -e TEST_BASE_URL=https://grafana.test.local \
  -e RESULTS_DIR=/results \
  -e NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/test-ca.crt \
  -e TEST_RUN_ID="test-$(date +%s)" \
  --dns 172.28.0.53 \
  --add-host grafana.test.local:172.17.0.1 \
  -w /tests \
  mcr.microsoft.com/playwright:v1.42.0-focal \
  bash -c "update-ca-certificates && npm install -s && npx playwright test simple-smoke.spec.ts --reporter=list"
