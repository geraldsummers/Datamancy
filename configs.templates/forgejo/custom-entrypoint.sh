#!/bin/bash
# Custom Forgejo Entrypoint Wrapper
# Starts Forgejo and runs token generation script in parallel

set -e

# Start original Forgejo entrypoint in background
# Suppress chown errors for read-only mounted repositories (stderr filtered)
/usr/bin/entrypoint 2> >(grep -v "Read-only file system" >&2) &
FORGEJO_PID=$!

# Run token generation script in background (it has its own wait logic)
/generate-runner-token.sh &
TOKEN_GEN_PID=$!

# Wait for Forgejo main process (token generation runs independently)
wait $FORGEJO_PID
