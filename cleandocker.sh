#!/usr/bin/env bash
set -euo pipefail

echo "===> Stopping all running containers..."
if [ -n "$(docker ps -q)" ]; then
  docker ps -q | xargs -r docker stop
  echo "✓ All running containers stopped."
else
  echo "ℹ️ No running containers to stop."
fi

echo "===> Removing all containers..."
if [ -n "$(docker ps -aq)" ]; then
  docker ps -aq | xargs -r docker rm
  echo "✓ All containers removed."
else
  echo "ℹ️ No containers to remove."
fi

echo "===> Removing all volumes..."
if [ -n "$(docker volume ls -q)" ]; then
  docker volume ls -q | xargs -r docker volume rm
  echo "✓ All volumes removed."
else
  echo "ℹ️ No volumes to remove."
fi

echo "===> Pruning unused networks..."
docker network prune -f
echo "✓ Network prune complete."

echo "✅ Docker cleanup complete."

