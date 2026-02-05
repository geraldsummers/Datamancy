#!/usr/bin/env sh
set -eu
echo "[mastodon-init] Starting Mastodon database setup..."
echo "[mastodon-init] Waiting for mastodon-web container..."
RETRIES=30
for i in $(seq 1 $RETRIES); do
    if docker exec mastodon-web echo "ready" 2>/dev/null; then
        echo "[mastodon-init] mastodon-web container is ready"
        break
    fi
    if [ "$i" -eq "$RETRIES" ]; then
        echo "[mastodon-init] ERROR: mastodon-web container not ready after $RETRIES attempts"
        exit 1
    fi
    echo "[mastodon-init] Waiting for mastodon-web... ($i/$RETRIES)"
    sleep 2
done
echo "[mastodon-init] Running database migrations (idempotent)..."
if docker exec mastodon-web bundle exec rails db:migrate 2>&1; then
    echo "[mastodon-init] Migrations completed successfully"
else
    echo "[mastodon-init] WARNING: Migrations failed, will retry on next startup"
    exit 1
fi
echo "[mastodon-init] Creating/updating search indices (idempotent)..."
if docker exec mastodon-web bundle exec rails chewy:upgrade 2>&1; then
    echo "[mastodon-init] Search indices updated successfully"
else
    echo "[mastodon-init] WARNING: Search indices update failed (non-fatal)"
fi
echo "[mastodon-init] Setting up default follows..."
ADMIN_USERNAME="${MASTODON_ADMIN_USERNAME:-admin}"
DEFAULT_FOLLOWS=(
    "wikimediafoundation@wikimedia.social"
    "internetarchive@mastodon.archive.org"
    "creativecommons@mastodon.social"
    "openstreetmap@en.osm.town"
    "ProPublica@newsie.social"
    "edyong209@mastodon.xyz"
    "marynmck@mastodon.social"
    "briankrebs@infosec.exchange"
    "NASA@mstdn.social"
    "ourworldindata@mas.to"
    "AdamMGrant@mastodon.social"
    "calnewport@mastodon.social"
    "b0rk@jvns.ca"
    "simon@fedi.simonwillison.net"
    "prusaresearch@mastodon.social"
    "VoronDesign@fosstodon.org"
    "natgeo@mastodon.social"
    "philosophybites@mastodon.social"
    "tomscott@mastodon.social"
    "standupmaths@mastodon.social"
    "financialtimes@mastodon.social"
)
for account in "${DEFAULT_FOLLOWS[@]}"; do
    echo "[mastodon-init] Making $ADMIN_USERNAME follow $account..."
    if docker exec mastodon-web bundle exec tootctl accounts follow "$ADMIN_USERNAME" "$account" 2>&1; then
        echo "[mastodon-init] Successfully following $account"
    else
        echo "[mastodon-init] WARNING: Could not follow $account (account may not exist yet)"
    fi
done
echo "[mastodon-init] Mastodon initialization complete."
exit 0
