#!/bin/bash
# Local testing script for auth-status-dashboard
# This helps verify the setup before running in Docker

echo "🧪 Auth Status Dashboard - Local Test"
echo "======================================"

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
  echo "📦 Installing dependencies..."
  npm install
fi

# Set test environment variables
export PORT=3000
export BASE_DOMAIN=${BASE_DOMAIN:-lab.localhost}
export BROWSERLESS_URL=${BROWSERLESS_URL:-http://browserless:3000}
export TEST_USERNAME=authtest
export TEST_PASSWORD=TestAuth123!
export TEST_INTERVAL=60000

echo ""
echo "Configuration:"
echo "  BASE_DOMAIN: $BASE_DOMAIN"
echo "  BROWSERLESS_URL: $BROWSERLESS_URL"
echo "  TEST_USERNAME: $TEST_USERNAME"
echo "  TEST_INTERVAL: ${TEST_INTERVAL}ms"
echo ""

# Check if services are accessible
echo "🔍 Checking service connectivity..."

if curl -sf http://authentik-server:9000/-/health/ready/ > /dev/null 2>&1; then
  echo "✅ Authentik is accessible"
else
  echo "❌ Authentik is not accessible at http://authentik-server:9000"
  echo "   Make sure the stack is running: docker-compose up -d"
fi

if curl -sf $BROWSERLESS_URL > /dev/null 2>&1; then
  echo "✅ Browserless is accessible"
else
  echo "❌ Browserless is not accessible at $BROWSERLESS_URL"
fi

echo ""
echo "🚀 Starting server..."
echo "   Dashboard: http://localhost:$PORT"
echo "   API: http://localhost:$PORT/api/status"
echo "   Metrics: http://localhost:$PORT/metrics"
echo ""

node server.js
