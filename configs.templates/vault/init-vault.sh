#!/bin/sh
set -e

echo "Waiting for Vault to be ready..."
until wget -q -O /dev/null "http://localhost:8200/v1/sys/health?uninitcode=200&sealedcode=200" 2>/dev/null; do
    sleep 1
done

# Check if Vault is already initialized
if vault status 2>/dev/null | grep -q "Initialized.*true"; then
    echo "Vault already initialized"
    
    # Check if sealed
    if vault status 2>/dev/null | grep -q "Sealed.*true"; then
        echo "Vault is sealed, attempting auto-unseal..."
        
        # Try to unseal using stored keys if available
        if [ -f /vault/data/unseal-keys.txt ]; then
            echo "Found unseal keys, unsealing..."
            UNSEAL_KEY_1=$(sed -n '1p' /vault/data/unseal-keys.txt)
            UNSEAL_KEY_2=$(sed -n '2p' /vault/data/unseal-keys.txt)
            UNSEAL_KEY_3=$(sed -n '3p' /vault/data/unseal-keys.txt)
            
            vault operator unseal "$UNSEAL_KEY_1" || true
            vault operator unseal "$UNSEAL_KEY_2" || true
            vault operator unseal "$UNSEAL_KEY_3" || true
            
            echo "Vault unsealed"
        else
            echo "ERROR: Vault is sealed but no unseal keys found!"
            echo "Manual intervention required. Run:"
            echo "  docker exec vault vault operator unseal"
            exit 1
        fi
    else
        echo "Vault is already unsealed"
    fi
else
    echo "Initializing Vault for the first time..."
    
    # Initialize with 5 key shares, 3 required to unseal
    vault operator init -key-shares=5 -key-threshold=3 > /vault/data/init-output.txt
    
    # Extract keys and root token
    grep 'Unseal Key' /vault/data/init-output.txt | awk '{print $NF}' > /vault/data/unseal-keys.txt
    grep 'Initial Root Token' /vault/data/init-output.txt | awk '{print $NF}' > /vault/data/root-token.txt
    
    # Set restrictive permissions
    chmod 600 /vault/data/unseal-keys.txt
    chmod 600 /vault/data/root-token.txt
    chmod 600 /vault/data/init-output.txt
    
    echo "Vault initialized successfully"
    echo "Unseal keys and root token saved to /vault/data/"
    echo ""
    echo "⚠️  IMPORTANT: Backup these files immediately!"
    echo "   - /vault/data/init-output.txt (all 5 keys + root token)"
    echo "   - /vault/data/unseal-keys.txt (5 unseal keys)"
    echo "   - /vault/data/root-token.txt (root token)"
    echo ""
    
    # Auto-unseal after initialization
    echo "Auto-unsealing Vault..."
    UNSEAL_KEY_1=$(sed -n '1p' /vault/data/unseal-keys.txt)
    UNSEAL_KEY_2=$(sed -n '2p' /vault/data/unseal-keys.txt)
    UNSEAL_KEY_3=$(sed -n '3p' /vault/data/unseal-keys.txt)
    
    vault operator unseal "$UNSEAL_KEY_1"
    vault operator unseal "$UNSEAL_KEY_2"
    vault operator unseal "$UNSEAL_KEY_3"
    
    echo "Vault unsealed successfully"
    
    # Copy token to shared config volume for services to read
    cp /vault/data/root-token.txt /vault/config/token
    chmod 644 /vault/config/token

    ROOT_TOKEN=$(cat /vault/data/root-token.txt)
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "🔑 VAULT ROOT TOKEN (SAVE THIS FOR BACKUP!)"
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "$ROOT_TOKEN"
    echo ""
    echo "✓ Token automatically distributed to services via shared volume"
    echo "════════════════════════════════════════════════════════════════"
fi

echo "Vault is ready"
