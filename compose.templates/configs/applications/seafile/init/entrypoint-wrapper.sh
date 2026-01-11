#!/bin/bash
# Seafile entrypoint wrapper - IDEMPOTENT (safe to run multiple times)
# Applies custom seahub_settings.py after Seafile initialization

set -e

SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"
MARKER_FILE="/shared/seafile/conf/.datamancy_db_configured"

# Run the original Seafile entrypoint
/sbin/my_init -- /scripts/enterpoint.sh "$@" &
SEAFILE_PID=$!

# Wait for Seafile to create the config directory
echo "Waiting for Seafile to initialize..."
for i in {1..60}; do
    if [ -f "$SETTINGS_FILE" ]; then
        echo "Seafile config directory created"
        break
    fi
    sleep 1
done

# Apply our custom settings (idempotent - uses marker file)
if [ -f "$TEMPLATE_FILE" ] && [ -f "$SETTINGS_FILE" ]; then
    if [ ! -f "$MARKER_FILE" ]; then
        echo "Applying database configuration to seahub_settings.py..."

        # Backup original settings
        if [ ! -f "$SETTINGS_FILE.original" ]; then
            cp "$SETTINGS_FILE" "$SETTINGS_FILE.original"
        fi

        # Remove any previous DATABASES configuration block (idempotent cleanup)
        sed -i '/^# Datamancy PostgreSQL Configuration/,/^# End Datamancy Configuration/d' "$SETTINGS_FILE"

        # Add marker comments and append template
        echo "" >> "$SETTINGS_FILE"
        echo "# Datamancy PostgreSQL Configuration" >> "$SETTINGS_FILE"
        cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
        echo "# End Datamancy Configuration" >> "$SETTINGS_FILE"

        # Create marker file
        touch "$MARKER_FILE"
        echo "Database configuration added successfully"
    else
        echo "Database configuration already applied (marker file exists)"
    fi
fi

# Wait for the Seafile process
wait $SEAFILE_PID
