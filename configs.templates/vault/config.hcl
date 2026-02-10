ui = true

storage "raft" {
  path    = "/vault/data"
  node_id = "vault-node-1"

  # Performance tuning for high-concurrency test scenarios
  # Increase apply timeout to handle bursts of token creation
  apply_timeout = "30s"

  # Increase max append entries for better batch processing
  max_append_entries = 128
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1
}

api_addr = "http://vault:8200"
cluster_addr = "http://vault:8201"
disable_mlock = false

# Performance tuning
# Increase default lease durations to reduce token renewal overhead
default_lease_ttl = "1h"
max_lease_ttl = "24h"

# Log level for debugging (can be set to "info" in production)
log_level = "warn"
