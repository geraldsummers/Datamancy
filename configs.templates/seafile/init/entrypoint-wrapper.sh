#!/bin/bash
set -e
SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"
MARKER_FILE="/shared/seafile/conf/.datamancy_db_configured"
/sbin/my_init -- /scripts/enterpoint.sh "$@" &
SEAFILE_PID=$!
echo "Waiting for Seafile to initialize..."
for i in {1..60}; do
    if [ -f "$SETTINGS_FILE" ]; then
        echo "Seafile config directory created"
        break
    fi
    sleep 1
done
if [ -f "$TEMPLATE_FILE" ] && [ -f "$SETTINGS_FILE" ]; then
    if [ ! -f "$MARKER_FILE" ]; then
        echo "Applying database configuration to seahub_settings.py..."
        if [ ! -f "$SETTINGS_FILE.original" ]; then
            cp "$SETTINGS_FILE" "$SETTINGS_FILE.original"
        fi
        sed -i '/^# Datamancy PostgreSQL Configuration/,/^# End Datamancy Configuration/d' "$SETTINGS_FILE"
        echo "" >> "$SETTINGS_FILE"
        echo "# Datamancy PostgreSQL Configuration" >> "$SETTINGS_FILE"
        cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
        echo "# End Datamancy Configuration" >> "$SETTINGS_FILE"
        touch "$MARKER_FILE"
        echo "Database configuration added successfully"
    else
        echo "Database configuration already applied (marker file exists)"
    fi
fi
wait $SEAFILE_PID
