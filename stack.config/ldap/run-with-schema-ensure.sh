#!/bin/sh
set -eu

/container/tool/run "$@" &
RUNNER_PID=$!

forward_signal() {
  kill -TERM "$RUNNER_PID" 2>/dev/null || true
}

trap forward_signal INT TERM

sh /tmp/ensure-trading-schema.sh
sh /tmp/configure-memberof.sh

wait "$RUNNER_PID"
