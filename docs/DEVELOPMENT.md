# Development Guide

This guide covers building, testing, and extending the Datamancy infrastructure stack.

## 🎯 Kotlin-First Development

**IMPORTANT**: This project has a **strong preference for Kotlin** in all development:

### When to Use Kotlin

✅ **Always use Kotlin for:**
- New microservices (use Ktor framework)
- Automation scripts (`.main.kts` files)
- Build configuration (`.gradle.kts`)
- Utilities and tools
- Integration tests
- Configuration generators

### When Other Languages are Acceptable

⚠️ **Python** - Only when required by ecosystem:
- Playwright browser automation (Python API is native)
- ML/AI tooling where Python libraries are required

❌ **Bash/Shell** - Avoid for new scripts:
- Use Kotlin Script (`.main.kts`) instead
- Existing diagnostic test scripts are legacy
- New automation should be `.main.kts`

### Benefits of Kotlin-First Approach

1. **Single Language**: Services, scripts, and build all in Kotlin
2. **Type Safety**: Compile-time error detection
3. **Null Safety**: No null pointer exceptions
4. **Coroutines**: Modern async/await pattern
5. **JVM Ecosystem**: Access to vast Java libraries
6. **IDE Support**: Superior refactoring and code completion
7. **Consistency**: Easier maintenance and onboarding

### Example: Script Migration

**❌ Don't write new Bash scripts:**
```bash
#!/bin/bash
# cleanup.sh
docker system prune -af
```

**✅ Write Kotlin scripts instead:**
```kotlin
#!/usr/bin/env kotlin
// cleanup.main.kts
import java.lang.ProcessBuilder

ProcessBuilder("docker", "system", "prune", "-af")
    .inheritIO()
    .start()
    .waitFor()
```

See `scripts/cleandocker.main.kts` for a real example.

## Table of Contents

- [Development Environment](#development-environment)
- [Building the Stack](#building-the-stack)
- [Project Structure](#project-structure)
- [Kotlin Services](#kotlin-services)
- [Adding New Services](#adding-new-services)
- [Testing](#testing)
- [Debugging](#debugging)
- [Contributing](#contributing)

## Development Environment

### Prerequisites

**Required:**
- **Java Development Kit (JDK)** 21+ (Eclipse Temurin recommended)
- **Kotlin** 2.0.21+ (bundled with Gradle)
- **Gradle** 8.14+ (or use wrapper: `./gradlew`)
- **Docker** & **Docker Compose** v2.0+
- **Git** 2.30+

**Optional:**
- **IntelliJ IDEA** 2024.1+ (recommended IDE)
- **NVIDIA GPU** with CUDA drivers (for AI services)
- **Python** 3.11+ (for Playwright service development)

### IDE Setup

**IntelliJ IDEA:**

1. **Import Project**
   ```
   File → Open → Select Datamancy directory
   ```

2. **Configure JDK**
   ```
   File → Project Structure → Project SDK → Add JDK → Select JDK 21
   ```

3. **Enable Kotlin Plugin** (should be default)
   ```
   File → Settings → Plugins → Verify "Kotlin" is enabled
   ```

4. **Gradle Import**
   ```
   Gradle panel (right side) → Reload All Gradle Projects
   ```

5. **Code Style**
   ```
   File → Settings → Editor → Code Style → Kotlin
   → Scheme: Kotlin style guide
   ```

### Environment Configuration

Create `.env` file in project root:

```bash
# Domain Configuration
DOMAIN=stack.local
MAIL_DOMAIN=stack.local

# Admin Credentials
STACK_ADMIN_USER=admin
STACK_ADMIN_PASSWORD=change-me-in-production
STACK_ADMIN_EMAIL=admin@stack.local

# Volumes
VOLUMES_ROOT=./volumes

# LLM Configuration
LITELLM_MASTER_KEY=sk-local-dev-key
HUGGINGFACEHUB_API_TOKEN=your-hf-token

# Service Secrets (generate with openssl rand -hex 32)
AUTHELIA_JWT_SECRET=$(openssl rand -hex 32)
AUTHELIA_SESSION_SECRET=$(openssl rand -hex 32)
AUTHELIA_STORAGE_ENCRYPTION_KEY=$(openssl rand -hex 32)
AUTHELIA_IDENTITY_PROVIDERS_OIDC_HMAC_SECRET=$(openssl rand -hex 32)

# Application Secrets
GRAFANA_OAUTH_SECRET=$(openssl rand -hex 16)
VAULTWARDEN_OAUTH_SECRET=$(openssl rand -hex 16)
PLANKA_SECRET_KEY=$(openssl rand -hex 32)

# Database Passwords (use openssl rand -hex 16)
GRAFANA_DB_PASSWORD=grafana_dev_pass
VAULTWARDEN_DB_PASSWORD=vaultwarden_dev_pass
OPENWEBUI_DB_PASSWORD=openwebui_dev_pass
PLANKA_DB_PASSWORD=planka_dev_pass

# Optional Features
VECTOR_EMBED_SIZE=384
OCR_MODEL=none  # Set to vision model name if available
```

## Building the Stack

### Build All Services

```bash
# From project root
./gradlew build

# Or with wrapper
gradle build
```

This compiles all Kotlin subprojects and runs tests.

### Build Specific Service

```bash
# Build agent-tool-server (KFuncDB)
./gradlew :agent-tool-server:build

# Build probe-orchestrator
./gradlew :probe-orchestrator:shadowJar

# Build all with shadow JARs
./gradlew shadowJar
```

### Docker Build

```bash
# Build all images defined in docker-compose.yml
docker compose build

# Build specific service
docker compose build probe-orchestrator

# Build without cache
docker compose build --no-cache vllm-router
```

### Run Services Locally (Outside Docker)

```bash
# KFuncDB
cd src/agent-tool-server
../../gradlew run

# Probe Orchestrator
cd src/probe-orchestrator
../../gradlew run

# With environment variables
TOOLSERVER_PORT=8081 ../../gradlew :agent-tool-server:run
```

## Project Structure

```
Datamancy/
├── build.gradle.kts              # Root Gradle config
├── settings.gradle.kts            # Subproject definitions
├── gradle/wrapper/                # Gradle wrapper files
├── .env                          # Environment config (gitignored)
├── docker-compose.yml            # Service orchestration
│
├── src/                          # Kotlin services
│   ├── agent-tool-server/        # KFuncDB - Tool server
│   │   ├── build.gradle.kts
│   │   ├── Dockerfile
│   │   └── src/main/kotlin/
│   │       ├── Main.kt           # Entry point
│   │       ├── org/example/
│   │       │   ├── api/          # Plugin API
│   │       │   ├── host/         # Plugin manager
│   │       │   ├── http/         # HTTP server
│   │       │   ├── manifest/     # Plugin manifests
│   │       │   ├── plugins/      # Built-in plugins
│   │       │   └── util/         # Utilities
│   │       └── test/             # Unit tests
│   │
│   ├── probe-orchestrator/       # Health monitoring
│   │   ├── build.gradle.kts
│   │   ├── Dockerfile
│   │   └── src/main/kotlin/
│   │       └── org/datamancy/probe/
│   │           └── Probe-Orchestrator.kt  # All-in-one service
│   │
│   ├── speech-gateway/           # Audio processing
│   ├── vllm-router/             # Model memory management
│   ├── stack-discovery/         # Service discovery
│   └── playwright-controller/   # Browser automation
│
├── src/config-generator/        # Configuration generator
│   ├── src/main/kotlin/
│   │   └── org/datamancy/configgen/
│   │       ├── Orchestrator.kt           # Main generator
│   │       ├── model/                    # Config data models
│   │       │   ├── StackConfig.kt
│   │       │   ├── StackConfigs.kt       # Env definitions (lab, prod)
│   │       │   └── ...
│   │       ├── templates/                # Config templates
│   │       │   ├── DockerComposeTemplate.kt
│   │       │   ├── CaddyTemplate.kt
│   │       │   ├── AutheliaTemplate.kt
│   │       │   └── LdapBootstrapTemplate.kt
│   │       ├── secrets/                  # Secrets providers
│   │       │   └── Secrets.kt
│   │       └── validators/               # Config validation
│   │           └── Validation.kt
│   └── build.gradle.kts
│
├── configs/                      # Service configurations (current/manual)
│   ├── applications/             # App-specific configs
│   │   ├── authelia/
│   │   ├── grafana/
│   │   └── ...
│   ├── databases/               # DB init scripts
│   │   ├── postgres/
│   │   ├── mariadb/
│   │   └── vectors/
│   ├── infrastructure/          # Core services
│   │   ├── caddy/
│   │   ├── litellm/
│   │   └── benthos/
│   └── probe-orchestrator/      # Service manifests
│
├── scripts/                     # Utility scripts
│   ├── config-generator/
│   │   └── generate-all.main.kts  # Config generation entry point
│   ├── cleandocker.main.kts    # Kotlin script - Docker cleanup
│   ├── rag_helper.main.kts     # RAG ingestion
│   └── rag_query.main.kts      # RAG queries
│
├── tests/                       # Test suites
│   └── diagnostic/              # Diagnostic tests
│       ├── test-01-agent-tool-server-tools.sh
│       ├── test-02-single-probe.sh
│       └── ...
│
└── volumes/                     # Persistent data (gitignored)
    ├── postgres_data/
    ├── redis_data/
    ├── proofs/                  # Diagnostic artifacts
    └── ...
```

## Kotlin Services

### Service Template

Each Kotlin service follows this structure:

```
src/my-service/
├── build.gradle.kts              # Gradle build config
├── Dockerfile                    # Multi-stage Docker build
└── src/
    ├── main/kotlin/
    │   └── org/datamancy/myservice/
    │       └── Application.kt    # Main entry point
    └── test/kotlin/
        └── org/datamancy/myservice/
            └── ApplicationTest.kt
```

### build.gradle.kts Example

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
    id("application")
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("org.datamancy.myservice.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("my-service.jar")
}
```

### Ktor HTTP Server Pattern

```kotlin
package org.datamancy.myservice

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HealthResponse(val status: String, val version: String)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            })
        }

        routing {
            get("/healthz") {
                call.respond(HealthResponse("ok", "1.0.0"))
            }

            get("/api/v1/resource") {
                // Your logic here
                call.respondText("Hello World")
            }
        }
    }.start(wait = true)
}
```

### Dockerfile Pattern

```dockerfile
# Build stage
FROM gradle:8.5-jdk21 AS builder
WORKDIR /app
COPY ../build.gradle.kts settings.gradle.kts ./
COPY ../src ./src
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Adding New Services

### Step 1: Create Service Directory

```bash
mkdir -p src/my-new-service/src/main/kotlin/org/datamancy/mynewservice
mkdir -p src/my-new-service/src/test/kotlin/org/datamancy/mynewservice
```

### Step 2: Add to settings.gradle.kts

```kotlin
// settings.gradle.kts
include(":my-new-service")
project(":my-new-service").projectDir = file("src/my-new-service")
```

### Step 3: Create build.gradle.kts

```kotlin
// src/my-new-service/build.gradle.kts
plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.datamancy.mynewservice.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}
```

### Step 4: Implement Service

```kotlin
// src/my-new-service/src/main/kotlin/org/datamancy/mynewservice/Application.kt
package org.datamancy.mynewservice

fun main() {
    println("My New Service v1.0.0")
    // Your service logic
}
```

### Step 5: Add to Docker Compose

```yaml
# docker-compose.yml
services:
  my-new-service:
    build: ./src/my-new-service
    container_name: my-new-service
    restart: unless-stopped
    profiles:
      - applications
    networks:
      - backend
    environment:
      - SERVICE_PORT=8090
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8090/healthz"]
      interval: 30s
      timeout: 5s
      retries: 3
```

### Step 6: Create Dockerfile

```dockerfile
# src/my-new-service/Dockerfile
FROM gradle:8.5-jdk21 AS builder
WORKDIR /build
COPY ../.. .
RUN gradle :my-new-service:shadowJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
COPY --from=builder /build/src/my-new-service/build/libs/*.jar /app.jar
EXPOSE 8090
CMD ["java", "-jar", "/app.jar"]
```

### Step 7: Build and Test

```bash
# Build
./gradlew :my-new-service:build

# Run locally
./gradlew :my-new-service:run

# Build Docker image
docker compose build my-new-service

# Run service
docker compose --profile applications up -d my-new-service

# Check logs
docker logs my-new-service

# Test health
curl http://localhost:8090/healthz
```

## Configuration Template System

### Overview

Datamancy uses a **template-based configuration system** with simple `{{PLACEHOLDER}}` substitution to support multiple environments without hardcoding values.

**Key Concept:** The `configs/` directory is **generated from templates** and is **gitignored**. You must generate it before deployment!

### Architecture

```
configs.templates/           # Source templates (committed to git)
├── applications/
│   ├── authelia/
│   │   └── configuration.yml     # Has {{DOMAIN}} placeholders
│   ├── mailu/mailu.env          # Has {{MAIL_DOMAIN}}, {{DOMAIN}}
│   └── ...
├── infrastructure/
│   └── caddy/
│       └── Caddyfile            # Has {{DOMAIN}} placeholders
└── databases/
            ↓
    (process-config-templates.main.kts)
            ↓
configs/                     # Generated (gitignored)
└── (same structure, placeholders replaced with .env values)
```

### Usage

**Scripts:**
- `scripts/process-config-templates.main.kts` - Main processor
- `scripts/migrate-to-templates.main.kts` - One-time migration tool

**Generate configs:**
```bash
# Generate configs/ from configs.templates/ using .env
kotlin scripts/process-config-templates.main.kts

# Preview what would be generated (dry-run)
kotlin scripts/process-config-templates.main.kts --dry-run --verbose

# Regenerate after changing .env (force overwrite)
kotlin scripts/process-config-templates.main.kts --force
```

**What gets processed:**
- 28 template files (.yml, .yaml, .conf, .env, Caddyfile, .json, .toml)
- 24 additional files copied as-is (scripts, binaries)
- 71 placeholder replacements total

### Template Syntax

Templates use simple `{{VARIABLE}}` syntax:

**Example: configs.templates/infrastructure/caddy/Caddyfile**
```
auth.{{DOMAIN}} {
    reverse_proxy authelia:9091
}

grafana.{{DOMAIN}} {
    reverse_proxy grafana:3000
}
```

**After processing with DOMAIN=project-saturn.com:**
```
auth.project-saturn.com {
    reverse_proxy authelia:9091
}

grafana.project-saturn.com {
    reverse_proxy grafana:3000
}
```

### Supported Variables

All variables from `.env` are automatically available:

| Variable | Example | Used In |
|----------|---------|---------|
| `DOMAIN` | `project-saturn.com` | Caddyfile, Authelia, Mailu, Synapse, Homepage |
| `MAIL_DOMAIN` | `project-saturn.com` | Mailu configuration |
| `STACK_ADMIN_EMAIL` | `admin@example.com` | Authelia, various configs |
| `VOLUMES_ROOT` | `./volumes` | Volume mount paths |

### Adding New Templates

1. **Edit template file** in `configs.templates/`:
   ```yaml
   # configs.templates/myapp/config.yml
   server:
     host: {{MY_APP_HOST}}
     domain: {{DOMAIN}}
   ```

2. **Add variable to `.env`**:
   ```bash
   MY_APP_HOST=0.0.0.0
   ```

3. **Regenerate**:
   ```bash
   kotlin scripts/process-config-templates.main.kts --force
   ```

### Migration Tool

If you have existing configs with hardcoded values, use the migration script:

```bash
# Preview migration
kotlin scripts/migrate-to-templates.main.kts --dry-run --verbose

# Perform migration (creates configs.templates/)
kotlin scripts/migrate-to-templates.main.kts

# Backup original
mv configs configs.backup
```

The migration script automatically finds and replaces:
- `project-saturn.com` → `{{DOMAIN}}`
- `auth.project-saturn.com` → `auth.{{DOMAIN}}`
- `admin@localhost` → `{{STACK_ADMIN_EMAIL}}`
- etc.

### Development Workflow

**Changing your domain:**
```bash
# 1. Edit environment
nano .env
# Change: DOMAIN=new-domain.com

# 2. Regenerate configs
kotlin scripts/process-config-templates.main.kts --force

# 3. Restart affected services
docker compose restart caddy authelia grafana
```

**Adding a new service:**
```bash
# 1. Add config template
mkdir -p configs.templates/applications/myapp
cat > configs.templates/applications/myapp/config.yml <<EOF
server:
  domain: {{DOMAIN}}
  email: {{STACK_ADMIN_EMAIL}}
EOF

# 2. Regenerate
kotlin scripts/process-config-templates.main.kts --force

# 3. Update docker-compose.yml to mount generated config
# volumes:
#   - ./configs/applications/myapp/config.yml:/app/config.yml:ro
```

### Benefits

- ✅ **Simple**: Just string replacement, no complex logic
- ✅ **Fast**: Processes 52 files in seconds
- ✅ **Transparent**: Templates are readable config files
- ✅ **Environment-Agnostic**: Same templates for dev/staging/prod
- ✅ **No Duplication**: Single source of truth for each environment

### See Full Documentation

- [TEMPLATE_CONFIG.md](../TEMPLATE_CONFIG.md) - Complete reference
- [QUICKSTART_TEMPLATES.md](../QUICKSTART_TEMPLATES.md) - Step-by-step guide

## Testing

### Unit Tests

```kotlin
// src/my-service/src/test/kotlin/ApplicationTest.kt
package org.datamancy.myservice

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun testBasicFunction() {
        val result = someFunction()
        assertEquals("expected", result)
    }
}
```

**Run tests:**
```bash
# All tests
./gradlew test

# Specific module
./gradlew :probe-orchestrator:test

# With output
./gradlew test --info

# Generate report
./gradlew test
# See build/reports/tests/test/index.html
```

### Integration Tests

Located in `tests/diagnostic/`:

```bash
# Test agent-tool-server tool inventory
./tests/diagnostic/test-01-agent-tool-server-tools.sh

# Test single service probe
./tests/diagnostic/test-02-single-probe.sh http://grafana:3000

# Test screenshot capture
./tests/diagnostic/test-03-screenshot-capture.sh

# Test container diagnostics
./tests/diagnostic/test-04-container-diagnostics.sh agent-tool-server

# Test SSH operations (requires SSH key setup)
curl -X POST http://localhost:8081/call-tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ops_ssh_cmd",
    "args": {
      "cmd": "uptime",
      "host": "host.docker.internal"
    }
  }' | jq

# Run all diagnostic tests
for test in ./tests/diagnostic/test-*.sh; do
    echo "Running $test..."
    "$test" || echo "FAILED: $test"
done
```

### Manual Testing

```bash
# Test KFuncDB tools endpoint
curl http://localhost:8081/tools | jq

# Test browser screenshot
curl -X POST http://localhost:8081/call-tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "browser_screenshot",
    "args": {"url": "http://grafana:3000"}
  }' | jq '.result.imageBase64' -r | base64 -d > test.png

# Test probe orchestrator
curl -X POST http://localhost:8089/start-probe \
  -H "Content-Type: application/json" \
  -d '{
    "services": ["http://grafana:3000", "http://portainer:9000"]
  }' | jq

# Test LLM via LiteLLM
curl http://localhost:4000/v1/chat/completions \
  -H "Authorization: Bearer ${LITELLM_MASTER_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "hermes-2-pro-mistral-7b",
    "messages": [{"role": "user", "content": "Hello!"}]
  }' | jq
```

## Debugging

### Debugging Kotlin Services

**IntelliJ IDEA:**

1. Open Run/Debug Configurations
2. Add new "Gradle" configuration
3. **Gradle project**: Select module (e.g., `:probe-orchestrator`)
4. **Tasks**: `run`
5. **Environment variables**: Add required vars
6. Set breakpoints in code
7. Click Debug button

**Command Line with JVM Debug:**

```bash
# Enable remote debugging
export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# Run service
./gradlew :probe-orchestrator:run

# Attach IntelliJ debugger to localhost:5005
```

### Debugging Docker Containers

```bash
# Exec into container
docker exec -it agent-tool-server sh

# View logs
docker logs -f probe-orchestrator

# View last 100 lines
docker logs --tail 100 litellm

# Follow logs with timestamps
docker logs -f --timestamps grafana

# Inspect container
docker inspect agent-tool-server | jq

# Check health
docker inspect agent-tool-server | jq '.[0].State.Health'

# Network debugging
docker exec agent-tool-server ping postgres
docker exec agent-tool-server wget -O- http://playwright:3000/healthz
```

### Common Issues

**Issue: Gradle build fails with "JAVA_HOME not set"**
```bash
# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

**Issue: Docker build fails with "no space left on device"**
```bash
# Clean Docker system
docker system prune -a --volumes

# Remove unused images
docker image prune -a
```

**Issue: Service can't connect to database**
```bash
# Check network
docker network ls
docker network inspect datamancy_backend

# Verify database is running
docker ps | grep postgres

# Test connection from service container
docker exec myservice ping postgres
docker exec myservice nc -zv postgres 5432

# Verify PostgreSQL databases exist
docker exec postgres psql -U admin -d postgres -c '\l'

# Test specific database connection
docker exec postgres psql -U grafana -d grafana -c 'SELECT 1'
docker exec postgres psql -U vaultwarden -d vaultwarden -c 'SELECT 1'
docker exec postgres psql -U openwebui -d openwebui -c 'SELECT 1'
```

**Issue: SSH host key verification fails**
```bash
# Symptom: agent-tool-server SSH operations fail with "Host key verification failed"

# Solution 1: Refresh known_hosts via API
curl -X POST http://localhost:8081/admin/refresh-ssh-keys

# Solution 2: Manually refresh known_hosts
docker exec ssh-key-bootstrap sh /bootstrap_known_hosts.sh /app/known_hosts
docker compose restart agent-tool-server

# Verify known_hosts file
docker exec agent-tool-server cat /app/known_hosts

# Check SSH key permissions
docker exec agent-tool-server ls -la /app/stackops_ed25519
```

**Issue: vLLM OOM (Out of Memory)**
```yaml
# Reduce GPU memory utilization in docker-compose.yml
command: [
  "--gpu-memory-utilization", "0.70",  # Reduced from 0.80
  ...
]
```

**Issue: Authelia login loop**
```bash
# Check Valkey connection
docker exec authelia nc -zv redis 6379

# Verify LDAP connection
docker exec authelia ldapsearch -x -H ldap://ldap:389 -b "dc=stack,dc=local"

# Check Authelia logs
docker logs authelia | grep -i error
```

## Contributing

### Code Style

- **Kotlin**: Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Line Length**: 120 characters max
- **Indentation**: 4 spaces (no tabs)
- **Naming**: camelCase for functions/variables, PascalCase for classes

### Git Workflow

1. **Create Feature Branch**
   ```bash
   git checkout -b feature/my-awesome-feature
   ```

2. **Make Changes**
   ```bash
   # Edit files
   git add .
   git commit -m "Add awesome feature"
   ```

3. **Run Tests**
   ```bash
   ./gradlew test
   docker compose build
   ```

4. **Push and PR**
   ```bash
   git push origin feature/my-awesome-feature
   # Create Pull Request on GitHub
   ```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: Add screenshot OCR to probe orchestrator
fix: Handle null response in HTTP client
docs: Update API documentation for KFuncDB
refactor: Simplify model loading logic in vLLM router
test: Add unit tests for plugin manager
```

### Pull Request Checklist

- [ ] Code follows project style guidelines
- [ ] All tests pass (`./gradlew test`)
- [ ] Docker build succeeds (`docker compose build`)
- [ ] Documentation updated (if needed)
- [ ] No secrets or credentials in code
- [ ] Commit messages are clear and descriptive

---

**Next**: See [DEPLOYMENT.md](DEPLOYMENT.md) for production deployment guidelines.
