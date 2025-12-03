# Datamancy

**Sovereign Compute Stack** - A complete self-hosted infrastructure stack for AI, collaboration, and automation.

## 🚀 Quick Start

### Prerequisites
- Linux server (Ubuntu 22.04+ recommended)
- Docker Engine 24.0+ with Compose v2
- 16GB RAM minimum (32GB+ recommended)
- 100GB storage minimum (500GB+ recommended)
- Optional: NVIDIA GPU with 12GB+ VRAM for AI services

### One-Time Setup

```bash
# 1. Clone repository
git clone <repository-url>
cd Datamancy

# 2. Generate all configuration (creates ~/.config/datamancy/)
./stack-controller.main.kts config generate
./stack-controller.main.kts config process
./stack-controller.main.kts ldap bootstrap

# 3. Create volume directories
./stack-controller.main.kts volumes create

# 4. Deploy full stack (53 services)
docker compose --env-file ~/.config/datamancy/.env.runtime \
  --profile bootstrap \
  --profile databases \
  --profile vector-dbs \
  --profile infrastructure \
  --profile applications \
  up -d

# 5. Wait for services to initialize (~2 minutes)
sleep 120

# 6. Check deployment status
./stack-controller.main.kts health
```

**Expected Result:** 51-52 of 53 services healthy
- Portainer will timeout after 5min (requires admin setup)
- Seafile may need one restart after initial setup

### Access Services

After deployment, access services at:
- **Homepage**: https://stack.local (or your configured domain)
- **Portainer**: https://portainer.stack.local
- **Grafana**: https://grafana.stack.local
- **Open WebUI**: https://open-webui.stack.local
- **JupyterHub**: https://jupyter.stack.local
- **Mastodon**: https://mastodon.stack.local

**Default Credentials:**
- Username: `admin` (from STACK_ADMIN_USER)
- Password: Check `~/.config/datamancy/.env.runtime` for `STACK_ADMIN_PASSWORD`

## 📚 Documentation

| Document | Description |
|----------|-------------|
| **[DEPLOYMENT.md](docs/DEPLOYMENT.md)** | Complete deployment guide with troubleshooting |
| **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** | System architecture and design decisions |
| **[STACK_CONTROLLER_GUIDE.md](docs/STACK_CONTROLLER_GUIDE.md)** | Stack controller CLI reference |
| **[SECURITY.md](docs/SECURITY.md)** | Security architecture and hardening |
| **[LDAP_BOOTSTRAP.md](docs/LDAP_BOOTSTRAP.md)** | LDAP and authentication setup |
| **[DEVELOPMENT.md](docs/DEVELOPMENT.md)** | Development and contribution guide |

## 🏗️ Architecture

### Service Profiles

| Profile | Services | Purpose |
|---------|----------|---------|
| **bootstrap** | LDAP, Authelia, Postgres, Redis, Caddy | Core authentication and proxy |
| **databases** | MariaDB, Postgres, ClickHouse, CouchDB, Redis | Data persistence |
| **vector-dbs** | Qdrant | Vector search for AI/RAG |
| **infrastructure** | Portainer, Grafana, Homepage, Dockge, Kopia | Management and monitoring |
| **applications** | 25+ services | Collaboration, AI, communication |

### Network Architecture

```
┌─────────────────────────────────────────────────────┐
│ frontend (172.20.0.0/24)                            │
│ ├─ Caddy (reverse proxy + TLS)                     │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ backend (172.21.0.0/24)                             │
│ ├─ Applications (Open-WebUI, Mastodon, etc.)       │
│ ├─ Mailu (email services)                          │
│ ├─ AI Services (vLLM, LiteLLM, embeddings)         │
│ └─ Databases (MariaDB, Postgres - bridged)         │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ database (172.22.0.0/24)                            │
│ ├─ Postgres, MariaDB (primary interfaces)          │
│ ├─ ClickHouse, CouchDB, Redis                      │
│ └─ Qdrant (vector DB)                              │
└─────────────────────────────────────────────────────┘
```

**Key Design Decisions:**
- MariaDB and Postgres are on **both** `backend` and `database` networks for maximum compatibility
- All secrets generated automatically and stored in `~/.config/datamancy/` (outside git)
- Templates in `configs.templates/` processed to `~/.config/datamancy/configs/`
- Init scripts in templates automatically copied to volumes during deployment

## 🛠️ Stack Controller CLI

The `stack-controller.main.kts` is the primary management tool:

```bash
# Stack Operations
./stack-controller.main.kts up [--profile=<name>]       # Start services
./stack-controller.main.kts down                         # Stop all services
./stack-controller.main.kts recreate [--profile=<name>]  # Full recreate cycle
./stack-controller.main.kts status                       # Show service status
./stack-controller.main.kts health                       # Check all health statuses

# Configuration
./stack-controller.main.kts config generate    # Generate .env.runtime
./stack-controller.main.kts config process     # Process all templates

# Maintenance
./stack-controller.main.kts volumes create     # Create volume directories
./stack-controller.main.kts ldap bootstrap     # Generate LDAP bootstrap
./stack-controller.main.kts logs <service>     # View service logs
./stack-controller.main.kts restart <service>  # Restart a service
```

See [STACK_CONTROLLER_GUIDE.md](docs/STACK_CONTROLLER_GUIDE.md) for complete reference.

## 🔧 Common Operations

### View Service Status
```bash
./stack-controller.main.kts status
```

### View Logs
```bash
# All services
docker compose --env-file ~/.config/datamancy/.env.runtime logs -f

# Specific service
./stack-controller.main.kts logs open-webui
docker logs -f open-webui
```

### Restart a Service
```bash
./stack-controller.main.kts restart open-webui
```

### Update Configuration
```bash
# Edit runtime environment
nano ~/.config/datamancy/.env.runtime

# Regenerate configs
./stack-controller.main.kts config process

# Restart affected services
docker compose --env-file ~/.config/datamancy/.env.runtime up -d
```

### Clean Rebuild
```bash
# Stop everything
./stack-controller.main.kts down

# Clear configs and volumes (⚠️ DELETES ALL DATA)
rm -rf ~/.config/datamancy/configs
rm -rf volumes/*

# Regenerate and redeploy
./stack-controller.main.kts config process
./stack-controller.main.kts volumes create
docker compose --env-file ~/.config/datamancy/.env.runtime \
  --profile bootstrap --profile databases --profile vector-dbs \
  --profile infrastructure --profile applications up -d
```

## 🐛 Troubleshooting

### Check Service Health
```bash
./stack-controller.main.kts health
```

### Common Issues

**Bookstack: Database connection errors**
- **Cause:** MariaDB network configuration or cached credentials
- **Fix:** See [DEPLOYMENT.md#bookstack-database-connection-errors](docs/DEPLOYMENT.md#bookstack-database-connection-errors)

**Mailu: Antispam waiting for admin**
- **Cause:** Incorrect subnet or missing admin alias
- **Fix:** See [DEPLOYMENT.md#mailu-antispam-service-waiting-for-admin](docs/DEPLOYMENT.md#mailu-antispam-service-waiting-for-admin)

**Seafile: MySQL connection timeout**
- **Cause:** Timing issue in entrypoint script
- **Fix:** `docker restart seafile`

**Portainer: Unhealthy after 5 minutes**
- **Cause:** Requires admin setup within 5 minutes (security feature)
- **Fix:** Access UI and create admin, or `docker restart portainer`

For detailed troubleshooting, see [DEPLOYMENT.md#troubleshooting](docs/DEPLOYMENT.md#troubleshooting)

## 📦 Services Included

### Authentication & Identity
- **Authelia** - SSO and 2FA
- **LDAP** - User directory
- **LDAP Account Manager** - LDAP web UI

### Collaboration & Productivity
- **Mastodon** - Social networking
- **Mailu** - Email server (SMTP, IMAP, webmail)
- **BookStack** - Documentation wiki
- **Planka** - Kanban boards
- **OnlyOffice** - Office suite
- **Seafile** - File sync and share
- **JupyterHub** - Notebooks and data science

### AI & Machine Learning
- **vLLM** - GPU-accelerated LLM inference
- **Open WebUI** - ChatGPT-like interface
- **LiteLLM** - LLM proxy and router
- **Embedding Service** - Text embeddings
- **Whisper** - Speech-to-text
- **Piper** - Text-to-speech

### Infrastructure
- **Caddy** - Reverse proxy with automatic HTTPS
- **Portainer** - Container management UI
- **Grafana** - Metrics and dashboards
- **Homepage** - Service dashboard
- **Dockge** - Compose stack manager
- **Kopia** - Backup solution

### Databases
- **PostgreSQL** - Primary relational DB
- **MariaDB** - Secondary relational DB
- **ClickHouse** - Analytics database
- **CouchDB** - Document database
- **Redis/Valkey** - Caching and sessions
- **Qdrant** - Vector database

### Automation
- **Agent Tool Server** - SSH-based automation
- **Probe Orchestrator** - Service monitoring
- **VM Provisioner** - VM management
- **Benthos** - Data pipelines

## 🔐 Security

- **SSO**: All services integrated with Authelia
- **2FA**: TOTP support via Authelia
- **TLS**: Automatic certificates via Caddy
- **Secrets**: Auto-generated, stored outside git
- **LDAP**: Centralized user management
- **Network Isolation**: Three-tier network architecture
- **SSH Hardening**: Forced commands and key-only auth

See [SECURITY.md](docs/SECURITY.md) for detailed security architecture.

## 🤝 Contributing

See [DEVELOPMENT.md](docs/DEVELOPMENT.md) for:
- Development setup
- Coding standards
- Testing procedures
- Contributing guidelines

## 📝 License

[Add license information here]

## 🙏 Acknowledgments

Built with:
- Docker & Docker Compose
- Authelia, LDAP
- vLLM, LiteLLM, Ollama ecosystem
- Caddy, Traefik
- PostgreSQL, MariaDB
- And many other open-source projects

---

**Need Help?** Check the [documentation](docs/) or open an issue.
