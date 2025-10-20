#!/bin/bash
# Example script: Check disk usage
# Returns disk usage percentage as a metric

set -e

USAGE=$(df -h / | awk 'NR==2 {print $5}' | sed 's/%//')

echo "disk_usage_percent $USAGE"
