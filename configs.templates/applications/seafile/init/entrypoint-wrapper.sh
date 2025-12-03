#!/bin/bash
# Seafile entrypoint wrapper
# Applies custom seahub_settings.py after Seafile initialization

set -e

# Run the original Seafile entrypoint
/sbin/my_init -- /scripts/enterpoint.sh "$@" &
SEAFILE_PID=$!

# Wait for Seafile to create the config directory
echo "Waiting for Seafile to initialize..."
for i in {1..60}; do
    if [ -f "/shared/seafile/conf/seahub_settings.py" ]; then
        echo "Seafile config directory created"
        break
    fi
    sleep 1
done

# Apply our custom settings if not already applied
SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"

if [ -f "$TEMPLATE_FILE" ] && [ -f "$SETTINGS_FILE" ]; then
    if ! grep -q "DATABASES" "$SETTINGS_FILE" 2>/dev/null; then
        echo "Appending database configuration to seahub_settings.py..."
        cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
        echo "Database configuration added successfully"
    else
        echo "Database configuration already present"
    fi
fi

# Wait for the Seafile process
wait $SEAFILE_PID
