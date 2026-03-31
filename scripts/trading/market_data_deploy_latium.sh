#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
REMOTE_MARKET_DB_ROOT="${REMOTE_MARKET_DB_ROOT:-/mnt/market}"
REMOTE_VECTOR_DB_ROOT="${REMOTE_VECTOR_DB_ROOT:-/mnt/labware/vectors}"
IMAGE_TAR_NAME="market-data-stack-images-$(date +%Y%m%d%H%M%S).tar"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$ROOT_DIR"

SERVICES=(
  market-data-sync
  market-data-repair
  market-data-persist
  market-data-minute-staging
  feature-materializer
  market-data-state-updater
)

REMOTE_SERVICE_NAMES=(
  market-data-sync
  raw-candle-repair
  market-data-persist
  market-data-minute-staging
  research-feature-materializer
  market-data-health-updater
)

CONTAINER_DIRS=(
  stack.containers/market-data-sync
  stack.containers/market-data-repair
  stack.containers/market-data-persist
  stack.containers/market-data-minute-staging
  stack.containers/feature-materializer
  stack.containers/market-data-state-updater
)

COMPOSE_FILES=(
  stack.compose/pipeline.yml
  stack.compose/grafana.yml
  stack.compose/market-postgres.yml
)

GRADLE_TASKS=(
  :trading-sdk:generateTradingPolicyArtifact
  :market-data-sync:shadowJar
  :market-data-repair:shadowJar
  :market-data-persist:shadowJar
  :market-data-minute-staging:shadowJar
  :feature-materializer:shadowJar
  :market-data-state-updater:shadowJar
)

JAR_DIRS=(
  stack.kotlin/market-data-sync/build/libs
  stack.kotlin/market-data-repair/build/libs
  stack.kotlin/market-data-persist/build/libs
  stack.kotlin/market-data-minute-staging/build/libs
  stack.kotlin/feature-materializer/build/libs
  stack.kotlin/market-data-state-updater/build/libs
)

sync_remote_storage_roots() {
  ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; tmp=\$(mktemp); \
    grep -v '^MARKET_DB_ROOT=' .env | grep -v '^VECTOR_DB_ROOT=' > \"\$tmp\"; \
    printf 'MARKET_DB_ROOT=%s\nVECTOR_DB_ROOT=%s\n' '$REMOTE_MARKET_DB_ROOT' '$REMOTE_VECTOR_DB_ROOT' >> \"\$tmp\"; \
    mv \"\$tmp\" .env"
}

echo "[market-data-deploy] building targeted jars"
./gradlew "${GRADLE_TASKS[@]}"

echo "[market-data-deploy] building local docker images"
for service in "${SERVICES[@]}"; do
  docker build -t "datamancy/${service}:local-build" -f "stack.containers/${service}/Dockerfile" .
done

echo "[market-data-deploy] creating image archive ${IMAGE_TAR_NAME}"
docker save -o "$TMP_DIR/$IMAGE_TAR_NAME" \
  datamancy/market-data-sync:local-build \
  datamancy/market-data-repair:local-build \
  datamancy/market-data-persist:local-build \
  datamancy/market-data-minute-staging:local-build \
  datamancy/feature-materializer:local-build \
  datamancy/market-data-state-updater:local-build

echo "[market-data-deploy] syncing compose, config, and container sources"
ssh "$REMOTE_HOST" "mkdir -p \
  $REMOTE_ROOT/stack.compose \
  $REMOTE_ROOT/stack.containers \
  $REMOTE_ROOT/stack.config/postgres \
  $REMOTE_ROOT/stack.config/grafana/provisioning \
  $REMOTE_ROOT/configs/trading \
  $REMOTE_ROOT/stack.kotlin"

rsync -az "${COMPOSE_FILES[@]}" "$REMOTE_HOST:$REMOTE_ROOT/stack.compose/"
rsync -az stack.config/postgres/ "$REMOTE_HOST:$REMOTE_ROOT/stack.config/postgres/"
rsync -az stack.config/grafana/provisioning/ "$REMOTE_HOST:$REMOTE_ROOT/stack.config/grafana/provisioning/"

for dir in "${CONTAINER_DIRS[@]}"; do
  rsync -az "$dir/" "$REMOTE_HOST:$REMOTE_ROOT/$dir/"
done

for dir in "${JAR_DIRS[@]}"; do
  ssh "$REMOTE_HOST" "mkdir -p $REMOTE_ROOT/$dir"
  rsync -az "$dir/" "$REMOTE_HOST:$REMOTE_ROOT/$dir/"
done

rsync -az stack.kotlin/trading-sdk/build/generated/trading-policy/trading-policy.json \
  "$REMOTE_HOST:$REMOTE_ROOT/configs/trading/trading-policy.json"
rsync -az "$TMP_DIR/$IMAGE_TAR_NAME" "$REMOTE_HOST:$REMOTE_ROOT/$IMAGE_TAR_NAME"

echo "[market-data-deploy] enforcing remote storage roots"
sync_remote_storage_roots

echo "[market-data-deploy] loading images on latium"
ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; docker load -i $IMAGE_TAR_NAME; rm -f $IMAGE_TAR_NAME"

echo "[market-data-deploy] applying schema and hard-cut cleanup"
ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; docker exec -i market-postgres psql -v ON_ERROR_STOP=1 -U postgres -d datamancy" \
  < stack.config/postgres/init-market-data-schema.sql

ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; docker exec -i market-postgres psql -v ON_ERROR_STOP=1 -U postgres -d datamancy" <<'SQL'
DROP VIEW IF EXISTS data_health_symbol_1m;
DROP VIEW IF EXISTS data_health_exchange_1m;
DROP TABLE IF EXISTS execution_context_1m;
DELETE FROM raw_sync_state
WHERE channel LIKE 'candle_%'
  AND channel NOT IN ('candle_5m', 'candle_4h');
DELETE FROM market_data
WHERE data_type LIKE 'candle_%'
  AND data_type NOT IN ('candle_5m', 'candle_4h');
DELETE FROM feature_materialization_state WHERE bar_size_minutes = 1;
DELETE FROM feature_coverage_state WHERE bar_size_minutes = 1;
SQL

echo "[market-data-deploy] removing retired archive container if present"
ssh "$REMOTE_HOST" "docker rm -f market-data-archive-importer >/dev/null 2>&1 || true"

echo "[market-data-deploy] starting market-data and grafana services"
ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; docker compose \
  -f docker-compose.yml \
  -f stack.compose/market-postgres.yml \
  -f stack.compose/pipeline.yml \
  -f stack.compose/grafana.yml \
  up -d --no-build market-data-sync raw-candle-repair market-data-persist market-data-minute-staging research-feature-materializer market-data-health-updater grafana"

echo "[market-data-deploy] waiting for service health"
ssh "$REMOTE_HOST" 'set -euo pipefail
for service in market-data-sync raw-candle-repair market-data-persist market-data-minute-staging research-feature-materializer market-data-health-updater grafana; do
  deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    cid="$(docker ps -aq --filter name="^${service}$" | head -n 1)"
    if [ -z "$cid" ]; then
      sleep 2
      continue
    fi
    state="$(docker inspect -f "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}" "$cid")"
    if [ "$state" = "running|healthy" ] || [ "$state" = "running|" ]; then
      break
    fi
    sleep 2
  done
  docker ps --filter name="^${service}$" --format "table {{.Names}}\t{{.Status}}"
done'

echo "[market-data-deploy] deployment complete"
