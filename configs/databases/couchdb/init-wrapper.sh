#!/bin/bash
set -e

# Start CouchDB in the background
/docker-entrypoint.sh /opt/couchdb/bin/couchdb &
COUCH_PID=$!

# Wait for CouchDB to be available
echo "Waiting for CouchDB to be ready..."
until curl -s http://127.0.0.1:5984/_up 2>/dev/null | grep -q '"status":"ok"'; do
  sleep 2
done

echo "CouchDB is ready. Initializing..."

# Setup single-node cluster with proper configuration
echo "Configuring single-node cluster..."
curl -X POST -H "Content-Type: application/json" http://admin:${COUCHDB_PASSWORD}@127.0.0.1:5984/_cluster_setup \
  -d '{"action": "enable_single_node", "bind_address":"0.0.0.0", "username": "admin", "password": "'"${COUCHDB_PASSWORD}"'"}' 2>/dev/null || true

echo "Finalizing cluster setup..."
curl -X POST -H "Content-Type: application/json" http://admin:${COUCHDB_PASSWORD}@127.0.0.1:5984/_cluster_setup \
  -d '{"action": "finish_cluster"}' 2>/dev/null || true

# Create system databases if they don't exist
echo "Creating system databases..."
curl -X PUT http://admin:${COUCHDB_PASSWORD}@127.0.0.1:5984/_users 2>/dev/null || true
curl -X PUT http://admin:${COUCHDB_PASSWORD}@127.0.0.1:5984/_replicator 2>/dev/null || true
curl -X PUT http://admin:${COUCHDB_PASSWORD}@127.0.0.1:5984/_global_changes 2>/dev/null || true

echo "CouchDB initialization complete!"

# Keep container running
wait $COUCH_PID
