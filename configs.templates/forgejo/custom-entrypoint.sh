#!/bin/bash
set -e
/usr/bin/entrypoint 2> >(grep -v "Read-only file system" >&2) &
FORGEJO_PID=$!
/generate-runner-token.sh &
TOKEN_GEN_PID=$!
wait $FORGEJO_PID
