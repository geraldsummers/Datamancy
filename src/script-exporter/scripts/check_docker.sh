#!/bin/bash
# Example script: Check if Docker is running
# Returns 1 if Docker is accessible, 0 otherwise

set -e

if docker info >/dev/null 2>&1; then
    echo "docker_running 1"
else
    echo "docker_running 0"
fi
