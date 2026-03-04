#!/bin/bash
set -e
SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"
echo "Seafile Database Configuration Script"
echo "======================================"
echo "Waiting for Seafile to create seahub_settings.py..."
for i in {1..180}; do
    if [ -f "$SETTINGS_FILE" ]; then
        echo "✓ Found seahub_settings.py"
        break
    fi
    if [ $i -eq 180 ]; then
        echo "ERROR: Timeout waiting for seahub_settings.py"
        exit 1
    fi
    sleep 1
done
if grep -q "^DATABASES" "$SETTINGS_FILE" 2>/dev/null; then
    echo "✓ Database configuration already present in seahub_settings.py"
    exit 0
fi
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "ERROR: Template file not found: $TEMPLATE_FILE"
    exit 1
fi
echo "Appending database configuration..."
cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
if grep -q "^DATABASES" "$SETTINGS_FILE"; then
    echo "✓ Database configuration successfully added"
    echo "Seafile will now use MySQL instead of SQLite"
else
    echo "ERROR: Failed to add database configuration"
    exit 1
fi
exit 0
