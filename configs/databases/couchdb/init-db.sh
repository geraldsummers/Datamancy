#!/bin/bash
# CouchDB initialization script to create required system databases

set -e

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."
until curl -s http://localhost:5984/_up | grep -q '"status":"ok"'; do
  sleep 2
done

echo "CouchDB is ready. Creating system databases..."

# Create _users database if it doesn't exist
if ! curl -s http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_users | grep -q 'db_name'; then
  echo "Creating _users database..."
  curl -X PUT http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_users
else
  echo "_users database already exists"
fi

# Create _replicator database if it doesn't exist
if ! curl -s http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_replicator | grep -q 'db_name'; then
  echo "Creating _replicator database..."
  curl -X PUT http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_replicator
else
  echo "_replicator database already exists"
fi

# Create _global_changes database if it doesn't exist
if ! curl -s http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_global_changes | grep -q 'db_name'; then
  echo "Creating _global_changes database..."
  curl -X PUT http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@localhost:5984/_global_changes
else
  echo "_global_changes database already exists"
fi

echo "CouchDB initialization complete!"
