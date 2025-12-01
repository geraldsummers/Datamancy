# Datamancy

**Datamancy** is a comprehensive self-hosted infrastructure stack combining AI/LLM capabilities, autonomous diagnostics, collaboration tools, and enterprise applications into a unified Docker-based platform.

## 🌍 Mission: Digital Sovereignty Through Agent-Assisted Administration

Datamancy enables **digital sovereignty** by providing a complete, self-hosted infrastructure stack that individuals and small teams can operate **without requiring dedicated IT staff**.

### The Sovereignty Cluster Vision

Traditional enterprise infrastructure requires:
- ❌ Large IT teams (1 admin per 50-100 users)
- ❌ 24/7 on-call rotations
- ❌ Specialized expertise (networking, databases, security)
- ❌ Vendor lock-in and data silos

**Datamancy's approach:**
- ✅ **Agent-Assisted Administration**: LLM-powered diagnostics and automated repair
- ✅ **Self-Healing Systems**: Autonomous monitoring detects and fixes issues before users notice
- ✅ **Radical Admin-to-User Ratio**: Target 1 admin per 1,000+ users through automation
- ✅ **Complete Data Sovereignty**: All services self-hosted, no cloud dependencies
- ✅ **Integrated Stack**: 40+ applications with unified authentication and management

### How Agent-Assisted Administration Works

```
Traditional Model:                 Datamancy Model:
User reports issue                 AI agent detects anomaly
  ↓                                  ↓
Admin investigates logs           Agent captures screenshots + logs
  ↓                                  ↓
Admin diagnoses root cause        LLM analyzes evidence
  ↓                                  ↓
Admin applies fix                 Agent proposes fixes
  ↓                                  ↓
Admin verifies resolution         Agent executes & verifies fix
  ↓                                  ↓
30+ minutes                       < 2 minutes (automated)
```

**Key Capabilities:**
- **Visual Probing**: AI captures screenshots, extracts text (OCR), validates UIs
- **Root Cause Analysis**: LLM analyzes logs, metrics, and patterns to identify issues
- **Automated Remediation**: Safe fixes (restarts, config adjustments) executed automatically
- **Continuous Learning**: Diagnostic patterns improve over time

**Result**: Small teams can operate enterprise-scale infrastructure with minimal manual intervention.

## 🎯 Project Overview

Datamancy provides a production-ready infrastructure stack with:

- **Autonomous Diagnostics**: LLM-powered service monitoring with visual probing and automated repair
- **AI/LLM Infrastructure**: Local model serving (vLLM), unified API gateway (LiteLLM), and intelligent routing
- **Plugin Architecture**: Extensible tool server with browser automation, SSH ops, and Docker management
- **Enterprise Applications**: Collaboration, storage, communication, and productivity tools (40+)
- **Identity & Security**: Centralized authentication (Authelia/OIDC) with LDAP directory

### 🎨 Design Philosophy

**Kotlin-First**: This project has a **strong preference for Kotlin/JVM** for all new services and utilities:

- **Microservices**: All new services should be written in Kotlin using Ktor framework
- **Scripts**: Use Kotlin Script (.main.kts) instead of Bash/Python for automation
- **Build Configuration**: Gradle with Kotlin DSL (.gradle.kts) throughout
- **Why Kotlin?**
  - Type safety and null safety
  - Coroutines for async/concurrent operations
  - Excellent JVM ecosystem access
  - Consistent codebase (single language for services, scripts, and build)
  - Superior IDE support and refactoring capabilities

**Exception**: Python is used only when required by ecosystem constraints (e.g., Playwright API server).

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          Caddy (Reverse Proxy)                  │
│                     https://*.your-domain.com                   │
└─────────────────────────────────────────────────────────────────┘
                                 │
         ┌───────────────────────┼───────────────────────┐
         │                       │                       │
┌────────▼──────┐    ┌──────────▼──────────┐   ┌───────▼────────┐
│   Authelia    │    │  Application Layer  │   │   AI Services  │
│  (OIDC SSO)   │    │  - Open WebUI       │   │   - vLLM       │
│               │◄───┤  - Grafana          │   │   - LiteLLM    │
│   OpenLDAP    │    │  - Vaultwarden      │   │   - Embedding  │
│  (Directory)  │    │  - JupyterHub       │   │                │
└───────────────┘    │  - 20+ more apps    │   └────────────────┘
                     └─────────────────────┘
                                 │
         ┌───────────────────────┼───────────────────────┐
         │                       │                       │
┌────────▼──────┐    ┌──────────▼──────────┐   ┌───────▼────────┐
│  Diagnostics  │    │    Data Layer       │   │  Orchestration │
│  - KFuncDB    │    │  - PostgreSQL       │   │  - Portainer   │
│  - Probe Orch │    │  - Valkey           │   │  - Dockge      │
│  - Playwright │    │  - Qdrant           │   │                │
└───────────────┘    │  - ClickHouse       │   └────────────────┘
                     └─────────────────────┘
```

## 🚀 Quick Start

### Prerequisites

- **Docker** & **Docker Compose** (v2.0+)
- **NVIDIA GPU** with CUDA drivers (for AI services)
- **Domain name** or local DNS setup
- **8GB RAM minimum**, 16GB+ recommended
- **50GB disk space** minimum

### 1. Clone and Configure

```bash
git clone <repository-url>
cd Datamancy

# Copy environment template
cp .env.example .env

# Edit configuration
nano .env
```

**Required environment variables:**
```bash
DOMAIN=your-domain.com
STACK_ADMIN_USER=admin
STACK_ADMIN_PASSWORD=<strong-password>
STACK_ADMIN_EMAIL=admin@your-domain.com
LITELLM_MASTER_KEY=<random-key>
VOLUMES_ROOT=./volumes
```

### 2. Initialize Infrastructure

```bash
# Create required directories
mkdir -p volumes/{caddy_data,caddy_config,postgres_data,redis_data}

# Start core infrastructure
docker compose --profile bootstrap up -d
```

**Bootstrap profile includes:**
- Caddy (reverse proxy)
- Authelia (SSO/OIDC)
- OpenLDAP (directory)
- Valkey (cache)
- Portainer (Docker UI)
- vLLM (LLM serving)
- LiteLLM (API gateway)
- KFuncDB (tool server)
- Probe Orchestrator (diagnostics)
- Open WebUI (chat interface)

### 3. Verify Services

```bash
# Check running containers
docker compose ps

# Run diagnostic probe
docker exec probe-orchestrator wget -qO- http://localhost:8089/healthz

# Access Open WebUI
open https://open-webui.your-domain.com
```

### 4. Add Applications (Optional)

```bash
# Start databases
docker compose --profile databases up -d

# Start all applications
docker compose --profile applications up -d
```

## 📦 Service Catalog

### Core Kotlin Services

| Service | Port | Description |
|---------|------|-------------|
| **agent-tool-server** | 8081 | Plugin-based tool server (browser, SSH, Docker tools) |
| **probe-orchestrator** | 8089 | Autonomous health monitoring & diagnostics |
| **speech-gateway** | 8091 | Audio processing (Whisper ASR + Piper TTS) |
| **vllm-router** | 8010 | Intelligent vLLM model memory management |
| **stack-discovery** | - | Service discovery and topology mapping |
| **playwright-controller** | - | Browser automation engine |

### AI/LLM Stack

| Service | Purpose |
|---------|---------|
| **vLLM** | GPU-accelerated LLM inference (Mistral-7B) |
| **LiteLLM** | Unified OpenAI-compatible API gateway |
| **Embedding Service** | Text embeddings (all-MiniLM-L6-v2) |
| **Open WebUI** | ChatGPT-like web interface |

### Databases

- **PostgreSQL** 16 - Relational data (Planka, Synapse, Mailu)
- **MariaDB** 11 - MySQL workloads (Seafile, BookStack)
- **Valkey** - Redis-compatible key-value store for caching and sessions
- **CouchDB** 3 - Document store
- **Qdrant** - Vector database for embeddings
- **ClickHouse** 24 - Analytics and time-series

### Applications

**Productivity & Collaboration:**
- **BookStack** - Wiki and knowledge base (OIDC)
- **Planka** - Kanban project management (OIDC)
- **JupyterHub** - Multi-user notebooks (OIDC)
- **Seafile** - File sync and share

**Communication:**
- **Mailu** - Full email server (SMTP/IMAP/webmail)
- **Synapse** - Matrix homeserver
- **Mastodon** - Social network
- **SOGo** - Webmail client

**Infrastructure:**
- **Grafana** - Monitoring dashboards (OIDC)
- **Vaultwarden** - Password manager (OIDC)
- **Portainer** - Docker management UI
- **Dockge** - Compose stack manager
- **Homepage** - Service dashboard

**Office & Productivity:**
- **OnlyOffice** - Document editor
- **Home Assistant** - Automation platform

## 🔧 Development

### Building Kotlin Services

```bash
# Build all services
./gradlew build

# Build specific service
./gradlew :probe-orchestrator:shadowJar

# Run tests
./gradlew test
```

### Project Structure

```
Datamancy/
├── src/
│   ├── agent-tool-server/      # KFuncDB - plugin tool server
│   ├── probe-orchestrator/     # Health monitoring & diagnostics
│   ├── speech-gateway/         # Audio processing gateway
│   ├── vllm-router/           # Model memory management
│   ├── stack-discovery/       # Service discovery
│   └── playwright-controller/ # Browser automation
├── configs/
│   ├── applications/          # App-specific configs
│   ├── databases/            # Database init scripts
│   ├── infrastructure/       # Core service configs
│   └── probe-orchestrator/   # Diagnostic manifests
├── scripts/                  # Kotlin utility scripts
├── tests/                    # Test suites
├── volumes/                  # Persistent data (gitignored)
├── docker-compose.yml        # Service definitions
└── build.gradle.kts         # Root Gradle config
```

### Adding a New Service

1. Create service directory: `src/your-service/`
2. Add to `settings.gradle.kts`: `include(":your-service")`
3. Create `build.gradle.kts` with dependencies
4. Add to `docker-compose.yml` with appropriate profile
5. Update service manifest in `configs/probe-orchestrator/services_manifest.json`

## 🔍 Autonomous Diagnostics

Datamancy includes a self-healing diagnostic system:

```bash
# Run full stack health probe
curl http://probe-orchestrator:8089/start-stack-probe

# Analyze issues and get fix proposals
curl http://probe-orchestrator:8089/analyze-and-propose-fixes

# Execute approved fix
curl -X POST http://probe-orchestrator:8089/execute-fix \
  -H "Content-Type: application/json" \
  -d '{
    "issue_id": "issue-xyz",
    "service": "grafana",
    "service_url": "http://grafana:3000",
    "container": "grafana",
    "fix_action": "restart"
  }'
```

**Diagnostic Features:**
- Visual probing with screenshot capture & OCR
- DOM analysis for web services
- Container log inspection
- Resource usage monitoring
- LLM-powered root cause analysis
- Automated fix proposals with confidence scores

## 📚 Documentation

- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Detailed system architecture
- **[DEVELOPMENT.md](DEVELOPMENT.md)** - Developer guide
- **[DEPLOYMENT.md](DEPLOYMENT.md)** - Production deployment
- **[API.md](API.md)** - API reference for all services
- **[AGENT_GUIDE.md](AGENT_GUIDE.md)** - AI agent integration guide

## 🧪 Testing

```bash
# Run diagnostic test suite
./tests/diagnostic/test-01-agent-tool-server-tools.sh
./tests/diagnostic/test-02-single-probe.sh
./tests/diagnostic/test-03-screenshot-capture.sh

# Run all tests
for test in ./tests/diagnostic/test-*.sh; do
  "$test" || echo "FAILED: $test"
done
```

## 🔐 Security

- **OIDC/SSO**: All apps integrate with Authelia
- **LDAP Directory**: Centralized user management
- **Network Isolation**: Separate frontend/backend/database networks
- **TLS**: Automatic HTTPS via Caddy (self-signed or Let's Encrypt)
- **Secrets Management**: Environment-based configuration

**Default Admin Credentials:**
```
Username: ${STACK_ADMIN_USER}
Password: ${STACK_ADMIN_PASSWORD}
```

**Change these immediately in production!**

## 🛠️ Troubleshooting

### Service Won't Start

```bash
# Check logs
docker compose logs <service-name>

# Check health
docker inspect <container> | jq '.[].State.Health'

# Run diagnostics
curl http://probe-orchestrator:8089/start-stack-probe | jq
```

### GPU/vLLM Issues

```bash
# Check GPU availability
nvidia-smi

# Check vLLM logs
docker logs vllm

# Test model serving
curl http://vllm:8000/v1/models
```

### Database Connection Errors

```bash
# Verify database health
docker exec postgres pg_isready
docker exec mariadb mariadb-admin ping

# Check network connectivity
docker exec <app-container> ping postgres
```

## 📊 Monitoring

**Access Grafana:**
```
https://grafana.your-domain.com
```

**Key Metrics:**
- Container resource usage
- Service response times
- Database connections
- GPU utilization (vLLM)
- Diagnostic probe results

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'Add amazing feature'`
4. Push to branch: `git push origin feature/amazing-feature`
5. Open a Pull Request

**Contribution Guidelines:**
- Follow Kotlin coding conventions
- Add tests for new features
- Update documentation
- Ensure Docker builds succeed

## 📝 License

[Your License Here]

## 🙏 Acknowledgments

Built with:
- **Kotlin** & **Ktor** - Modern JVM development
- **Docker** & **Compose** - Containerization
- **vLLM** - Fast LLM inference
- **Playwright** - Browser automation
- **Authelia** - Authentication
- And many other open-source projects

## 🔗 Links

- **Documentation**: See docs/ directory
- **Issue Tracker**: [GitHub Issues]
- **Discussions**: [GitHub Discussions]

---

**Agent-Friendly Metadata:**
```json
{
  "project": "Datamancy",
  "type": "infrastructure-stack",
  "language": "Kotlin",
  "runtime": "JVM-21",
  "framework": "Ktor-3.0",
  "deployment": "Docker Compose",
  "services_count": 40,
  "kotlin_services": 6,
  "has_tests": true,
  "has_diagnostics": true,
  "has_api": true
}
```
