#!/bin/bash
SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"
if [ ! -f "$SETTINGS_FILE" ]; then
    echo "Waiting for Seafile to create seahub_settings.py..."
    exit 0
fi
if grep -q "DATABASES" "$SETTINGS_FILE" 2>/dev/null; then
    echo "Database configuration already present in seahub_settings.py"
    exit 0
fi
if [ -f "$TEMPLATE_FILE" ]; then
    echo "Appending database configuration to $SETTINGS_FILE..."
    cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
    echo "✓ Database configuration added successfully"
else
    echo "Warning: Template file $TEMPLATE_FILE not found"
    exit 1
fi
