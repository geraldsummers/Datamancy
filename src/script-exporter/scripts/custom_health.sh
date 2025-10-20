#!/bin/bash
# Example script: Custom health check
# Add your own health check logic here

set -e

# Example: Check if a specific service is reachable
if curl -sf http://localhost:9090/-/healthy >/dev/null 2>&1; then
    echo "service_healthy 1"
else
    echo "service_healthy 0"
fi

# You can return multiple metrics from a single script
echo "script_execution_success 1"
