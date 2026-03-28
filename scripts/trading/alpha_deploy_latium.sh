#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-gerald@latium.local}"
REMOTE_ROOT="${REMOTE_ROOT:-~/datamancy}"
IMAGE_TAR_NAME="alpha-stack-images-$(date +%Y%m%d%H%M%S).tar"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$ROOT_DIR"

SERVICES=(
  alpha-analytics-service
  alpha-dataset-service
  alpha-discovery-service
  alpha-portfolio-service
  alpha-execution-agent
  alpha-execution-monitor
  alpha-orchestrator
  market-data-archive-importer
)

COMPOSE_FILES=(
  stack.compose/alpha-analytics-service.yml
  stack.compose/alpha-dataset-service.yml
  stack.compose/alpha-discovery-service.yml
  stack.compose/alpha-portfolio-service.yml
  stack.compose/alpha-execution-agent.yml
  stack.compose/alpha-execution-monitor.yml
  stack.compose/alpha-orchestrator.yml
  stack.compose/market-data-archive-importer.yml
)

CONTAINER_DIRS=(
  stack.containers/alpha-analytics-service
  stack.containers/alpha-dataset-service
  stack.containers/alpha-discovery-service
  stack.containers/alpha-portfolio-service
  stack.containers/alpha-execution-agent
  stack.containers/alpha-execution-monitor
  stack.containers/alpha-orchestrator
  stack.containers/market-data-archive-importer
)

GRADLE_TASKS=(
  :trading-sdk:generateTradingPolicyArtifact
  :alpha-analytics-service:shadowJar
  :alpha-dataset-service:shadowJar
  :alpha-discovery-service:shadowJar
  :alpha-portfolio-service:shadowJar
  :alpha-execution-agent:shadowJar
  :alpha-execution-monitor:shadowJar
  :alpha-orchestrator:shadowJar
  :market-data-archive-importer:shadowJar
)

echo "[alpha-deploy] building targeted jars"
./gradlew "${GRADLE_TASKS[@]}"

echo "[alpha-deploy] building local docker images"
for service in "${SERVICES[@]}"; do
  docker build -t "datamancy/${service}:local-build" -f "stack.containers/${service}/Dockerfile" .
done

echo "[alpha-deploy] creating image archive ${IMAGE_TAR_NAME}"
docker save -o "$TMP_DIR/$IMAGE_TAR_NAME" \
  datamancy/alpha-analytics-service:local-build \
  datamancy/alpha-dataset-service:local-build \
  datamancy/alpha-discovery-service:local-build \
  datamancy/alpha-portfolio-service:local-build \
  datamancy/alpha-execution-agent:local-build \
  datamancy/alpha-execution-monitor:local-build \
  datamancy/alpha-orchestrator:local-build \
  datamancy/market-data-archive-importer:local-build

echo "[alpha-deploy] syncing compose and container sources"
ssh "$REMOTE_HOST" "mkdir -p $REMOTE_ROOT/stack.compose $REMOTE_ROOT/stack.containers $REMOTE_ROOT/configs/trading $REMOTE_ROOT/stack.kotlin"
rsync -az "${COMPOSE_FILES[@]}" "$REMOTE_HOST:$REMOTE_ROOT/stack.compose/"
for dir in "${CONTAINER_DIRS[@]}"; do
  rsync -az "$dir/" "$REMOTE_HOST:$REMOTE_ROOT/$dir/"
done

for service in "${SERVICES[@]}"; do
  ssh "$REMOTE_HOST" "mkdir -p $REMOTE_ROOT/stack.kotlin/$service/build/libs"
  rsync -az "stack.kotlin/$service/build/libs/" "$REMOTE_HOST:$REMOTE_ROOT/stack.kotlin/$service/build/libs/"
done

rsync -az stack.kotlin/trading-sdk/build/generated/trading-policy/trading-policy.json "$REMOTE_HOST:$REMOTE_ROOT/configs/trading/trading-policy.json"
rsync -az "$TMP_DIR/$IMAGE_TAR_NAME" "$REMOTE_HOST:$REMOTE_ROOT/$IMAGE_TAR_NAME"

echo "[alpha-deploy] loading images and starting services on latium"
ssh "$REMOTE_HOST" "set -euo pipefail; cd $REMOTE_ROOT; docker load -i $IMAGE_TAR_NAME; rm -f $IMAGE_TAR_NAME; docker compose \
  -f docker-compose.yml \
  -f stack.compose/alpha-analytics-service.yml \
  -f stack.compose/alpha-dataset-service.yml \
  -f stack.compose/alpha-discovery-service.yml \
  -f stack.compose/alpha-portfolio-service.yml \
  -f stack.compose/alpha-execution-agent.yml \
  -f stack.compose/alpha-execution-monitor.yml \
  -f stack.compose/alpha-orchestrator.yml \
  -f stack.compose/market-data-archive-importer.yml \
  up -d --no-build alpha-analytics-service alpha-dataset-service alpha-discovery-service alpha-portfolio-service alpha-execution-agent alpha-execution-monitor alpha-orchestrator market-data-archive-importer"

echo "[alpha-deploy] waiting for service health"
ssh "$REMOTE_HOST" 'set -euo pipefail
for service in alpha-analytics-service alpha-dataset-service alpha-discovery-service alpha-portfolio-service alpha-execution-agent alpha-execution-monitor alpha-orchestrator market-data-archive-importer; do
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

echo "[alpha-deploy] deployment complete"
