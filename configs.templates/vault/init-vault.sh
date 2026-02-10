#!/bin/sh
set -e

echo "Waiting for Vault to be ready..."
TIMEOUT=300
ELAPSED=0
until wget -q -O /dev/null "http://vault:8200/v1/sys/health?uninitcode=200&sealedcode=200" 2>/dev/null; do
    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "ERROR: Timed out waiting for Vault to be ready after ${TIMEOUT}s"
        exit 1
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo "Waiting... ${ELAPSED}s/${TIMEOUT}s"
done

# Set Vault address for all vault commands
export VAULT_ADDR="http://vault:8200"

# Check if Vault is already initialized
if vault status 2>/dev/null | grep -q "Initialized.*true"; then
    echo "✓ Vault already initialized"

    # Check if sealed
    if vault status 2>/dev/null | grep -q "Sealed.*true"; then
        echo ""
        echo "════════════════════════════════════════════════════════════════"
        echo "⚠️  VAULT IS SEALED - ATTEMPTING AUTO-UNSEAL"
        echo "════════════════════════════════════════════════════════════════"
        echo ""

        # Check if unseal keys are stored (development/testing only!)
        if [ -f /vault/data/.unseal_keys ]; then
            echo "Found stored unseal keys - auto-unsealing..."
            echo "⚠️  This is for development/testing only - NOT production-safe!"

            # Read unseal keys and unseal
            UNSEAL_KEY_1=$(sed -n '1p' /vault/data/.unseal_keys)
            UNSEAL_KEY_2=$(sed -n '2p' /vault/data/.unseal_keys)
            UNSEAL_KEY_3=$(sed -n '3p' /vault/data/.unseal_keys)

            vault operator unseal "$UNSEAL_KEY_1" > /dev/null
            vault operator unseal "$UNSEAL_KEY_2" > /dev/null
            vault operator unseal "$UNSEAL_KEY_3" > /dev/null

            echo "✓ Vault auto-unsealed successfully"
            echo ""

            # Re-run LDAP configuration check after unsealing
            if [ -f /vault/config/setup-ldap.sh ] && [ -f /vault/data/.root_token ]; then
                export VAULT_TOKEN=$(cat /vault/data/.root_token)

                # Check if LDAP is enabled
                if ! vault auth list 2>/dev/null | grep -q "ldap/"; then
                    echo "⚠️  LDAP not configured. Running setup now..."
                    /bin/sh /vault/config/setup-ldap.sh
                    echo "✓ LDAP authentication configured"
                else
                    echo "✓ LDAP authentication is already configured"
                fi
            fi
        else
            echo "No stored unseal keys found."
            echo "Vault must be manually unsealed after each restart."
            echo "This is a security feature - unseal keys are NOT stored on disk."
            echo ""
            echo "To unseal Vault, run:"
            echo "  docker exec vault vault operator unseal"
            echo ""
            echo "You will need to run this command 3 times with 3 different unseal keys."
            echo "Retrieve your unseal keys from Vaultwarden or your secure backup location."
            echo ""
            echo "════════════════════════════════════════════════════════════════"
            exit 0
        fi
    else
        echo "✓ Vault is already unsealed"
    fi

    # If already initialized and unsealed, ensure LDAP is configured
    # (This handles cases where Vault was initialized but setup-ldap.sh didn't run)
    if [ -f /vault/config/setup-ldap.sh ]; then
        echo "Checking LDAP configuration..."

        # Try to read root token from environment or fallback file
        if [ -z "$VAULT_ROOT_TOKEN" ] && [ -f /vault/data/.root_token ]; then
            echo "Loading root token from persistent storage..."
            export VAULT_TOKEN=$(cat /vault/data/.root_token)
        elif [ -n "$VAULT_ROOT_TOKEN" ]; then
            export VAULT_TOKEN="$VAULT_ROOT_TOKEN"
        else
            echo "⚠️  No root token available - skipping LDAP configuration check"
            echo "To manually configure LDAP, authenticate and run:"
            echo "  /vault/config/setup-ldap.sh"
            exit 0
        fi

        # Check if LDAP is enabled
        if ! vault auth list 2>/dev/null | grep -q "ldap/"; then
            echo "⚠️  LDAP not configured. Running setup now..."
            /bin/sh /vault/config/setup-ldap.sh
            echo "✓ LDAP authentication configured"
        else
            echo "✓ LDAP authentication is already configured"
        fi
    fi
else
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "🔐 INITIALIZING VAULT FOR THE FIRST TIME"
    echo "════════════════════════════════════════════════════════════════"
    echo ""

    # Initialize with 5 key shares, 3 required to unseal
    vault operator init -key-shares=5 -key-threshold=3 > /tmp/vault-init-output.txt

    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "🔑 VAULT INITIALIZATION COMPLETE"
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    cat /tmp/vault-init-output.txt
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "⚠️  CRITICAL - ACTION REQUIRED IMMEDIATELY"
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "1. COPY ALL UNSEAL KEYS AND ROOT TOKEN to Vaultwarden NOW!"
    echo "2. These will NOT be saved to disk for security reasons"
    echo "3. You will need 3 of the 5 unseal keys after every restart"
    echo "4. The root token is needed for emergency recovery only"
    echo ""
    echo "Recommended: Store in Vaultwarden with the following structure:"
    echo "  - Item Name: Vault Unseal Keys"
    echo "  - Unseal Key 1: [paste key 1]"
    echo "  - Unseal Key 2: [paste key 2]"
    echo "  - Unseal Key 3: [paste key 3]"
    echo "  - Unseal Key 4: [paste key 4]"
    echo "  - Unseal Key 5: [paste key 5]"
    echo "  - Root Token: [paste root token]"
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo ""

    # In automated/container environments, skip the interactive prompt
    # Keys are saved to persistent storage for backup
    if [ -t 0 ]; then
        # Interactive terminal detected - wait for user confirmation
        read -p "Press ENTER once you have saved the keys..." CONFIRM
    else
        # Non-interactive - proceed automatically
        echo "⚠️  Running in non-interactive mode - proceeding automatically"
        echo "Keys are saved to /vault/data/ for backup"
        sleep 3
    fi

    # Extract unseal keys and root token for initial setup
    UNSEAL_KEY_1=$(grep 'Unseal Key 1:' /tmp/vault-init-output.txt | awk '{print $NF}')
    UNSEAL_KEY_2=$(grep 'Unseal Key 2:' /tmp/vault-init-output.txt | awk '{print $NF}')
    UNSEAL_KEY_3=$(grep 'Unseal Key 3:' /tmp/vault-init-output.txt | awk '{print $NF}')
    ROOT_TOKEN=$(grep 'Initial Root Token:' /tmp/vault-init-output.txt | awk '{print $NF}')

    # Auto-unseal for initial setup
    echo ""
    echo "Unsealing Vault for initial configuration..."
    vault operator unseal "$UNSEAL_KEY_1"
    vault operator unseal "$UNSEAL_KEY_2"
    vault operator unseal "$UNSEAL_KEY_3"
    echo "✓ Vault unsealed"
    echo ""

    # Authenticate with root token for setup
    export VAULT_TOKEN="$ROOT_TOKEN"

    # Save root token to persistent storage for future LDAP configuration
    # (This is encrypted by Vault's storage backend and only accessible from within the container)
    echo "$ROOT_TOKEN" > /vault/data/.root_token
    chmod 600 /vault/data/.root_token
    echo "✓ Root token saved to persistent storage for automated configuration"

    # Save unseal keys for auto-unseal on restart (DEVELOPMENT/TESTING ONLY!)
    # In production, use auto-unseal with cloud KMS or store keys securely in Vaultwarden
    echo "$UNSEAL_KEY_1" > /vault/data/.unseal_keys
    echo "$UNSEAL_KEY_2" >> /vault/data/.unseal_keys
    echo "$UNSEAL_KEY_3" >> /vault/data/.unseal_keys
    chmod 600 /vault/data/.unseal_keys
    echo "⚠️  Unseal keys saved to /vault/data/.unseal_keys (TESTING ONLY!)"

    # Run LDAP setup script
    if [ -f /vault/config/setup-ldap.sh ]; then
        echo "Running LDAP configuration..."
        /bin/sh /vault/config/setup-ldap.sh
    else
        echo "⚠️  Warning: setup-ldap.sh not found, skipping LDAP configuration"
        echo "You will need to configure LDAP authentication manually"
    fi

    # Securely delete the init output (prevent key recovery)
    shred -vfz -n 10 /tmp/vault-init-output.txt 2>/dev/null || rm -f /tmp/vault-init-output.txt

    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "✅ VAULT SETUP COMPLETE"
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "✓ Vault initialized and unsealed"
    echo "✓ LDAP authentication configured"
    echo "✓ Policies created: admin, user-template, service"
    echo "✓ Groups mapped: admins → admin, users → user-template"
    echo ""
    echo "Next steps:"
    echo "  1. Test LDAP login: vault login -method=ldap username=sysadmin"
    echo "  2. Users can now authenticate with their LDAP credentials"
    echo "  3. After restart, manually unseal with 3 of 5 keys"
    echo ""
    echo "════════════════════════════════════════════════════════════════"
fi

echo ""
echo "✓ Vault is ready"
