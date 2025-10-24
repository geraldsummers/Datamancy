#!/bin/bash
# Reset Authentik admin password
# Usage: ./reset-admin-password.sh [username] [new_password]

set -e

USERNAME="${1:-akadmin}"
NEW_PASSWORD="${2:-admin}"

echo "Resetting password for user: $USERNAME"

docker exec authentik-server python -c "
from django.contrib.auth import get_user_model
import django
import os
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'authentik.root.settings')
django.setup()
User = get_user_model()
try:
    user = User.objects.get(username='$USERNAME')
    user.set_password('$NEW_PASSWORD')
    user.save()
    print('✅ Password reset successfully for $USERNAME')
except User.DoesNotExist:
    print('❌ User $USERNAME does not exist')
    exit(1)
"

echo ""
echo "You can now log in with:"
echo "  Username: $USERNAME"
echo "  Password: $NEW_PASSWORD"
