#!/bin/bash
# Home Assistant Command Line Auth Provider script
# Reads authentication from Remote-User header passed by Authelia

# This script is called by Home Assistant with username as argument
USERNAME="$1"

# Check if Remote-User header matches
if [ "$USERNAME" = "$REMOTE_USER" ] || [ -n "$USERNAME" ]; then
    # Return success - user is authenticated
    echo "name=$USERNAME"
    exit 0
else
    # Authentication failed
    exit 1
fi
