#!/bin/bash
SETTINGS_FILE="/shared/seafile/conf/seahub_settings.py"
TEMPLATE_FILE="/tmp/seahub_settings_template.py"
if [ -f "$TEMPLATE_FILE" ]; then
    if ! grep -q "DATABASES" "$SETTINGS_FILE" 2>/dev/null; then
        echo "Appending database configuration to seahub_settings.py..."
        cat "$TEMPLATE_FILE" >> "$SETTINGS_FILE"
        echo "Database configuration added successfully"
    else
        echo "Database configuration already present in seahub_settings.py"
    fi
else
    echo "Warning: Template file $TEMPLATE_FILE not found"
fi
