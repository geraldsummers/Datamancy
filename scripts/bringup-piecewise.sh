#!/usr/bin/env bash
# bringup-piecewise.sh — Bring the stack up piece-by-piece and test
#
# Usage: scripts/bringup-piecewise.sh <command>
# Commands:
#   core         Start ingress/auth/db core (caddy, redis, ldap, authelia, postgres)
#   ai           Start AI path (localai, litellm, open-webui)
#   kfuncdb      Build+start kfuncdb and wait until healthy
#   uis-min      Start a minimal set of UI apps (grafana, homepage, portainer, dockge, couchdb, jupyterhub)
#   test         Run Puppeteer browser checks (build & run test-runner)
#   all          core -> ai -> kfuncdb -> uis-min -> test
#   status       Show docker ps table
#   down         docker compose down (keeps volumes)
#
# Notes:
# - Requires .env at repo root (for full stack). Populate real secrets before running.
# - For a light local demo, use scripts/bootstrap-stack.sh instead.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

compose() {
  docker compose -f "$repo_root/docker-compose.yml" --env-file "$repo_root/.env" "$@"
}

need_env() {
  if [[ ! -f "$repo_root/.env" ]]; then
    echo "ERROR: $repo_root/.env not found. Populate it for project-saturn.com and retry." >&2
    exit 1
  fi
}

# wait_healthy <container_name> [timeout_seconds]
wait_healthy() {
  local name="$1"; shift
  local timeout="${1:-180}"
  local start_ts now status
  start_ts=$(date +%s)
  echo "[wait] $name: waiting for 'healthy' (timeout ${timeout}s)"
  while true; do
    if ! status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null); then
      status="unknown"
    fi
    case "$status" in
      healthy)
        echo "[ok] $name is healthy";
        return 0 ;;
      none)
        # No healthcheck defined; consider 'running' as OK
        if docker inspect --format '{{.State.Running}}' "$name" 2>/dev/null | grep -q true; then
          echo "[ok] $name is running (no healthcheck)";
          return 0
        fi ;;
      unhealthy)
        echo "[warn] $name is unhealthy; continuing to wait..." ;;
      *)
        : ;;
    esac
    now=$(date +%s)
    if (( now - start_ts > timeout )); then
      echo "[fail] Timeout waiting for $name to be healthy (last status: $status)" >&2
      docker logs "$name" --tail 200 || true
      return 1
    fi
    sleep 3
  done
}

cmd_core() {
  need_env
  compose up -d caddy redis ldap authelia postgres
  wait_healthy caddy 180
  wait_healthy redis 90
  wait_healthy ldap 120
  wait_healthy authelia 180
  wait_healthy postgres 180
}

cmd_ai() {
  need_env
  compose up -d localai
  wait_healthy localai 600
  compose up -d litellm
  wait_healthy litellm 180
  compose up -d open-webui
  wait_healthy open-webui 180
}

cmd_kfuncdb() {
  need_env
  compose build kfuncdb
  compose up -d kfuncdb
  wait_healthy kfuncdb 120
}

cmd_uis_min() {
  need_env
  compose up -d grafana homepage portainer dockge couchdb jupyterhub
  wait_healthy grafana 240
  wait_healthy homepage 120
  wait_healthy portainer 240
  wait_healthy dockge 240
  wait_healthy couchdb 180
  wait_healthy jupyterhub 240
}

cmd_test() {
  need_env
  compose --profile testing build test-runner
  set +e
  compose --profile testing up --abort-on-container-exit --exit-code-from test-runner test-runner
  rc=$?
  set -e
  compose --profile testing rm -f test-runner >/dev/null 2>&1 || true
  if [[ $rc -ne 0 ]]; then
    echo "[fail] UI browser checks failed (exit $rc). See ./screenshots and test-runner logs." >&2
    exit $rc
  fi
  echo "[ok] UI browser checks passed"
}

cmd_all() {
  cmd_core
  cmd_ai
  cmd_kfuncdb
  cmd_uis_min
  cmd_test
}

cmd_status() {
  docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
}

cmd_down() {
  need_env
  compose down
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") <command>
Commands:
  core         Start core (caddy, redis, ldap, authelia, postgres)
  ai           Start LocalAI, LiteLLM, Open WebUI
  kfuncdb      Build+start kfuncdb
  uis-min      Start minimal UI set (grafana, homepage, portainer, dockge, couchdb, jupyterhub)
  test         Run browser checks (Puppeteer) via test-runner
  all          Run all steps sequentially
  status       Show docker ps
  down         docker compose down
USAGE
}

case "${1:-}" in
  core)      cmd_core ;;
  ai)        cmd_ai ;;
  kfuncdb)   cmd_kfuncdb ;;
  uis-min)   cmd_uis_min ;;
  test)      cmd_test ;;
  all)       cmd_all ;;
  status)    cmd_status ;;
  down)      cmd_down ;;
  *)         usage; exit 1 ;;
esac
