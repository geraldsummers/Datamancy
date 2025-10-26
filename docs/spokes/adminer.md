# Adminer

**Service:** `adminer`
**Phase:** 5 (Management Tools)
**Image:** `adminer:4.8.1`

## Purpose

Web-based database management UI supporting multiple database systems (MariaDB, PostgreSQL, MySQL, SQLite, etc.). Provides lightweight alternative to phpMyAdmin with support for multiple database types.

## Dependencies

- **Network:** `frontend`, `backend`
- **Upstream:** MariaDB (default), ClickHouse, MongoDB (via plugins)
- **Authentication:** Authelia forward_auth via Caddy

## Configuration

- **Default Server:** `mariadb` (configured via `ADMINER_DEFAULT_SERVER`)
- **Theme:** `dracula` for dark mode UI
- **Access:** `adminer.stack.local` via Caddy reverse proxy

## Endpoints

| Endpoint | Purpose | Access |
|----------|---------|--------|
| `:8080` | Web UI (internal) | Backend network |
| `adminer.stack.local` | Public HTTPS | Authelia-protected |

## Observability

- **Metrics:** Not exposed (PHP application)
- **Logs:** Container stdout via Promtail
- **Health:** HTTP 200 on login page

## Security Notes

- Database credentials required per connection (no default logins)
- Protected by Authelia SSO at edge
- No persistent state (stateless container)
- Direct database network access (backend network only)

## Operations

**Connect to MariaDB:**
```bash
# Via Adminer UI at adminer.stack.local
# System: MySQL
# Server: mariadb
# Username: root
# Password: (from .env MARIADB_ROOT_PASSWORD)
```

**Connect to ClickHouse:**
```bash
# System: ClickHouse
# Server: clickhouse
# Port: 8123 (HTTP interface)
```

## Provenance

Added Phase 5 for multi-database management. Replaces need for database-specific admin tools (phpMyAdmin, pgAdmin). Dracula theme chosen for consistency with dark mode preference.
