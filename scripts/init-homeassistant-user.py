#!/usr/bin/env python3
"""
Create Home Assistant owner user for trusted_networks auto-login
"""
import json
import uuid
import os
from pathlib import Path

AUTH_FILE = Path("/config/.storage/auth")
USERNAME = os.environ.get("HA_ADMIN_USER", "admin")
FULL_NAME = os.environ.get("HA_ADMIN_NAME", "Administrator")

def create_owner_user():
    print("=== Home Assistant User Creation ===")

    if not AUTH_FILE.exists():
        print("Auth file doesn't exist yet, waiting for HA to create it...")
        return False

    # Read existing auth data
    with open(AUTH_FILE, 'r') as f:
        auth_data = json.load(f)

    # Check if owner already exists
    users = auth_data['data']['users']
    for user in users:
        if user.get('is_owner', False) and not user.get('system_generated', False):
            print(f"✅ Owner user already exists: {user['name']}")
            return True

    # Find admin group ID
    admin_group_id = None
    for group in auth_data['data']['groups']:
        if group['name'] == 'Administrators':
            admin_group_id = group['id']
            break

    if not admin_group_id:
        print("❌ Admin group not found")
        return False

    # Create new owner user
    user_id = str(uuid.uuid4()).replace('-', '')
    new_user = {
        "id": user_id,
        "group_ids": [admin_group_id],
        "is_owner": True,
        "is_active": True,
        "name": FULL_NAME,
        "system_generated": False,
        "local_only": False
    }

    # Add credentials entry (trusted_networks auth provider)
    new_credentials = {
        "id": str(uuid.uuid4()).replace('-', ''),
        "user_id": user_id,
        "auth_provider_type": "trusted_networks",
        "auth_provider_id": None,
        "data": {"username": USERNAME}
    }

    # Add refresh token
    new_token = {
        "id": str(uuid.uuid4()).replace('-', ''),
        "user_id": user_id,
        "client_id": None,
        "client_name": "Trusted Networks",
        "client_icon": None,
        "token_type": "normal",
        "created_at": "2025-10-31T00:00:00.000000+00:00",
        "access_token_expiration": 1800.0,
        "token": str(uuid.uuid4()).replace('-', ''),
        "jwt_key": str(uuid.uuid4()).replace('-', ''),
        "last_used_at": None,
        "last_used_ip": None,
        "expire_at": None,
        "credential_id": None,
        "version": "2025.10.4"
    }

    # Add to auth data
    auth_data['data']['users'].append(new_user)
    auth_data['data']['credentials'].append(new_credentials)
    auth_data['data']['refresh_tokens'].append(new_token)

    # Write back
    with open(AUTH_FILE, 'w') as f:
        json.dump(auth_data, f, indent=2)

    print(f"✅ Created owner user: {FULL_NAME} ({USERNAME})")
    print("=== User creation complete ===")
    return True

if __name__ == "__main__":
    import time
    import sys

    # Wait for auth file to exist (up to 30 seconds)
    for i in range(30):
        if AUTH_FILE.exists():
            time.sleep(2)  # Wait a bit more for HA to finish writing
            if create_owner_user():
                sys.exit(0)
            else:
                print("Retrying in 1 second...")
                time.sleep(1)
        else:
            if i == 0:
                print("Waiting for Home Assistant to create auth file...")
            time.sleep(1)

    print("❌ Timeout waiting for auth file or user creation failed")
    sys.exit(1)
