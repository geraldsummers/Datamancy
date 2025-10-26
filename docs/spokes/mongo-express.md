# Mongo Express

**Service:** `mongo-express`
**Phase:** 5 (Management Tools)
**Image:** `mongo-express:1.0.2`

## Purpose

Web-based MongoDB admin interface for database management, collection browsing, document editing, and query execution. Provides visual interface for MongoDB operations.

## Dependencies

- **Network:** `frontend`, `backend`
- **Upstream:** MongoDB
- **Authentication:** Basic auth (own) + Authelia forward_auth via Caddy

## Configuration

- **MongoDB Server:** `mongodb:27017`
- **Admin Username:** `root` (from `MONGODB_ROOT_USER`)
- **Admin Password:** (from `MONGODB_ROOT_PASSWORD`)
- **Basic Auth:** Configured via `ME_CONFIG_BASICAUTH_*` (optional second layer)
- **Base URL:** `/` (root path)

## Endpoints

| Endpoint | Purpose | Access |
|----------|---------|--------|
| `:8081` | Web UI (internal) | Backend network |
| `mongo-express.stack.local` | Public HTTPS | Authelia-protected |

## Observability

- **Metrics:** Not exposed (Node.js application)
- **Logs:** Container stdout via Promtail
- **Health:** HTTP 200 on home page

## Security Notes

- Two-layer auth: Authelia at edge + optional basic auth
- Full MongoDB access (read/write/delete)
- No audit logging (use MongoDB audit features)
- Direct database connection on backend network

## Operations

**Access Web UI:**
```bash
# Navigate to mongo-express.stack.local
# Authenticate via Authelia
# If basic auth enabled: username/password from ME_CONFIG_BASICAUTH_*
```

**View Collections:**
- Select database from left sidebar
- Click collection name
- Browse/edit documents via UI

**Execute Queries:**
- Not supported (use MongoDB shell or Compass for complex queries)

## Provenance

Added Phase 5 for MongoDB management. Alternative to MongoDB Compass for browser-based access. LibreChat (Phase 5 AI) will use MongoDB; this provides admin interface for chat history/config management.

**Update 2025-10-26:** Migrated from caddy-docker-proxy labels to static Caddyfile routing. Service route configured in `configs/caddy/Caddyfile`.

