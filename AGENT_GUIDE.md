# Agent Guide

**Machine-readable guide for AI agents and LLMs interacting with the Datamancy infrastructure stack.**

## Mission Statement for AI Agents

You are part of a system designed to enable **digital sovereignty** through **agent-assisted administration**. Your role is to:

1. **Autonomously monitor** infrastructure health
2. **Diagnose issues** using logs, metrics, and visual evidence
3. **Propose fixes** with confidence ratings
4. **Execute safe remediation** when approved
5. **Enable 1:1,000+ admin-to-user ratios** through automation

### Core Principle: Sovereignty Through Automation

Traditional enterprise infrastructure requires 1 admin per 50-100 users. Datamancy's goal is **1 admin per 1,000+ users** by:

- Replacing manual diagnostics with AI-powered analysis
- Automating routine fixes (restarts, scaling, health checks)
- Providing evidence-based recommendations for complex issues
- Reducing mean-time-to-resolution from hours to minutes

**Your success metric**: Minimize human intervention while maintaining 99.9%+ uptime.

## Purpose

This document provides structured information optimized for automated parsing by AI agents, LLM-based tools, and code assistants to:

- Understand project structure and capabilities
- Perform automated operations
- Diagnose issues
- Generate code and configurations
- Answer questions about the system
- **Enable sovereignty**: Help small teams operate enterprise-scale infrastructure

## Project Metadata

```yaml
project_name: Datamancy
project_type: sovereignty_cluster  # Self-hosted infrastructure for digital sovereignty
repository_structure: monorepo
primary_language: Kotlin
language_preference: KOTLIN_FIRST  # Strong preference - see design philosophy

# Mission & Vision
mission: Enable digital sovereignty through agent-assisted administration
vision: 1 admin per 1,000+ users (vs traditional 1:50-100)
core_value: Complete data ownership without cloud dependencies

# Operational Model
admin_to_user_ratio_target: "1:1000+"
admin_to_user_ratio_traditional: "1:50-100"
automation_goal: Replace 95% of routine admin tasks with AI agents
mttr_target: "< 2 minutes"  # Mean Time To Resolution
mttr_traditional: "30-60 minutes"

# Technical Stack
jvm_version: 21
kotlin_version: 2.0.21
build_tool: Gradle 8.14
framework: Ktor 3.0
deployment: Docker Compose
orchestration_profiles:
  - bootstrap
  - databases
  - applications
  - infrastructure
  - bootstrap_vector_dbs

services_total: 40+
kotlin_services: 6
python_services: 1  # Only when required by ecosystem
has_tests: true
test_framework: JUnit 5
has_api_documentation: true
has_diagnostics: true
has_autonomous_monitoring: true

# Language Policy - IMPORTANT FOR CODE GENERATION
language_policy:
  new_services: kotlin_only
  new_scripts: kotlin_script_preferred  # .main.kts not .sh
  build_files: kotlin_dsl_only  # .gradle.kts not .gradle
  python_allowed: only_when_ecosystem_requires
  bash_allowed: legacy_only_no_new_scripts
  rationale:
    - type_safety
    - null_safety
    - single_language_consistency
    - superior_ide_support
    - coroutines_for_async

# Agent-Assisted Administration Capabilities
agent_capabilities:
  autonomous_monitoring:
    - visual_probing  # Screenshot + OCR
    - dom_analysis  # HTML structure validation
    - log_review  # Pattern matching in container logs
    - metric_collection  # CPU, memory, health status

  diagnosis:
    - llm_powered_root_cause_analysis
    - evidence_based_reasoning  # Logs + metrics + screenshots
    - confidence_scoring  # High/medium/low for proposed fixes

  remediation:
    safe_auto_execute:
      - container_restart
      - health_check_wait
      - service_reload
    requires_approval:
      - configuration_changes
      - data_migrations
      - security_patches

  learning:
    - failure_pattern_recognition
    - fix_success_tracking
    - continuous_improvement
```

## Service Inventory

### Kotlin Microservices

```yaml
services:
  - name: kfuncdb
    path: src/agent-tool-server
    port: 8081
    description: Plugin-based tool server for browser, Docker, SSH operations
    api_style: RESTful
    endpoints:
      - GET /tools
      - POST /call-tool
    capabilities:
      - browser_automation
      - docker_management
      - ssh_execution
      - llm_proxy
    dependencies: [playwright]

  - name: probe-orchestrator
    path: src/probe-orchestrator
    port: 8089
    description: Autonomous service health monitoring with LLM-powered diagnostics
    api_style: RESTful
    endpoints:
      - GET /healthz
      - POST /start-probe
      - POST /start-stack-probe
      - POST /analyze-and-propose-fixes
      - POST /execute-fix
    capabilities:
      - visual_probing
      - ocr_analysis
      - autonomous_diagnosis
      - automated_repair
    dependencies: [kfuncdb, litellm, playwright]

  - name: speech-gateway
    path: src/speech-gateway
    port: 8091
    description: Audio processing gateway (Whisper ASR + Piper TTS)
    api_style: RESTful
    endpoints:
      - GET /healthz
      - POST /transcribe
      - POST /synthesize
    capabilities:
      - speech_to_text
      - text_to_speech
    dependencies: [whisper, piper]

  - name: vllm-router
    path: src/vllm-router
    port: 8010
    description: Intelligent GPU memory management with LRU model eviction
    api_style: OpenAI Compatible
    endpoints:
      - GET /health
      - GET /v1/models
      - POST /v1/chat/completions
      - POST /v1/completions
      - POST /v1/embeddings
    capabilities:
      - model_loading
      - lru_eviction
      - gpu_memory_management
    dependencies: [vllm]

  - name: stack-discovery
    path: src/stack-discovery
    port: null
    description: Service discovery and topology mapping
    api_style: Library
    capabilities:
      - service_discovery
      - dependency_mapping
    dependencies: []

  - name: playwright-controller
    path: src/playwright-controller
    port: null
    description: Browser automation controller
    api_style: Library
    capabilities:
      - browser_control
    dependencies: [playwright]
```

### Python Services

```yaml
  - name: playwright
    path: src/playwright-service
    port: 3000
    language: Python
    description: Headless Firefox browser automation HTTP API
    api_style: RESTful
    endpoints:
      - GET /healthz
      - POST /screenshot
      - POST /dom
    capabilities:
      - screenshot_capture
      - dom_extraction
    dependencies: []
```

## File Structure Map

```
Datamancy/
├── Root Configuration
│   ├── build.gradle.kts          # Root Gradle build
│   ├── settings.gradle.kts        # Subproject definitions
│   ├── docker-compose.yml         # All service definitions
│   ├── .env                      # Environment variables (gitignored)
│   └── .gitignore                # Ignore patterns
│
├── Source Code
│   └── src/
│       ├── agent-tool-server/    # KFuncDB
│       │   ├── build.gradle.kts
│       │   ├── Dockerfile
│       │   └── src/main/kotlin/
│       │       ├── Main.kt
│       │       └── org/example/
│       │           ├── api/      # Plugin API interfaces
│       │           ├── host/     # Plugin manager & registry
│       │           ├── http/     # HTTP server & OpenAI proxy
│       │           ├── plugins/  # Built-in plugins
│       │           └── util/
│       │
│       ├── probe-orchestrator/
│       │   └── src/main/kotlin/org/datamancy/probe/
│       │       └── Probe-Orchestrator.kt  # Single-file service
│       │
│       ├── speech-gateway/
│       ├── vllm-router/
│       ├── stack-discovery/
│       └── playwright-controller/
│
├── Configuration
│   └── configs/
│       ├── applications/         # App-specific configs
│       │   ├── authelia/configuration.yml
│       │   ├── grafana/grafana.ini
│       │   └── ...
│       ├── databases/           # DB init scripts
│       │   ├── postgres/init-db.sh
│       │   ├── mariadb/init.sql
│       │   └── vectors/collections.yaml
│       ├── infrastructure/      # Core service configs
│       │   ├── caddy/Caddyfile
│       │   ├── litellm/config.yaml
│       │   └── benthos/benthos.yaml
│       └── probe-orchestrator/
│           └── services_manifest.json  # Service discovery manifest
│
├── Scripts
│   └── scripts/
│       ├── cleandocker.main.kts     # Kotlin script
│       ├── rag_helper.main.kts
│       └── rag_query.main.kts
│
├── Tests
│   └── tests/
│       └── diagnostic/
│           ├── test-01-kfuncdb-tools.sh
│           ├── test-02-single-probe.sh
│           ├── test-03-screenshot-capture.sh
│           ├── test-04-container-diagnostics.sh
│           └── test-05-llm-analysis.sh
│
├── Persistent Data (gitignored)
│   └── volumes/
│       ├── postgres_data/
│       ├── redis_data/
│       ├── proofs/              # Diagnostic artifacts
│       │   └── screenshots/
│       └── ...
│
└── Documentation
    ├── README.md
    ├── ARCHITECTURE.md
    ├── DEVELOPMENT.md
    ├── DEPLOYMENT.md
    ├── API.md
    └── AGENT_GUIDE.md (this file)
```

## Common Operations

### Build Operations

```yaml
build_all_kotlin_services:
  command: "./gradlew build"
  duration: "30-120 seconds"
  output: "build/ directories in each subproject"

build_specific_service:
  command: "./gradlew :probe-orchestrator:shadowJar"
  output: "src/probe-orchestrator/build/libs/*.jar"

build_docker_images:
  command: "docker compose build"
  duration: "5-15 minutes"

run_tests:
  command: "./gradlew test"
  report: "build/reports/tests/test/index.html"
```

### Deployment Operations

```yaml
start_minimal_stack:
  command: "docker compose --profile bootstrap up -d"
  services: 12
  includes: [caddy, authelia, ldap, vllm, litellm, kfuncdb, probe-orchestrator, open-webui]

start_full_stack:
  command: "docker compose --profile bootstrap --profile databases --profile applications up -d"
  services: 40+
  duration: "5-10 minutes"

check_status:
  command: "docker compose ps"
  output_format: "table"

view_logs:
  command: "docker logs -f <service-name>"
  alternative: "docker compose logs -f"
```

### Diagnostic Operations

```yaml
health_check_all:
  command: "curl -X POST http://localhost:8089/start-stack-probe | jq"
  returns: "JSON report with service health status"

analyze_failures:
  command: "curl -X POST http://localhost:8089/analyze-and-propose-fixes | jq"
  returns: "JSON with root cause analysis and fix proposals"

execute_fix:
  command: "curl -X POST http://localhost:8089/execute-fix -H 'Content-Type: application/json' -d '{...}'"
  requires: ["issue_id", "service", "container", "fix_action"]

list_available_tools:
  command: "curl http://localhost:8081/tools | jq"
  returns: "OpenAI function calling schema for all tools"
```

## Environment Variables Reference

### Required Variables

```yaml
DOMAIN:
  description: "Base domain for all services"
  example: "stack.local"
  usage: "Service URLs are generated as {service}.{DOMAIN}"

STACK_ADMIN_USER:
  description: "Global admin username"
  example: "admin"
  used_by: [authelia, grafana, litellm, databases]

STACK_ADMIN_PASSWORD:
  description: "Global admin password"
  security: "Change immediately in production"
  generation: "openssl rand -base64 32"
  used_by: [all_admin_interfaces]

LITELLM_MASTER_KEY:
  description: "API key for LiteLLM gateway"
  format: "sk-*"
  generation: "openssl rand -hex 32"
  usage: "Authorization: Bearer ${LITELLM_MASTER_KEY}"

VOLUMES_ROOT:
  description: "Root path for all persistent volumes"
  example: "./volumes"
  purpose: "Centralizes data storage location"
```

### Optional Variables

```yaml
OCR_MODEL:
  description: "Vision model for screenshot OCR"
  default: "none"
  example: "gpt-4-vision-preview"
  usage: "Set to enable OCR in probe orchestrator"

LLM_MODEL:
  description: "Default LLM model name"
  default: "hermes-2-pro-mistral-7b"

MAX_STEPS:
  description: "Max tool calls in probe workflow"
  default: 12
  type: integer

HTTP_TIMEOUT:
  description: "HTTP request timeout in seconds"
  default: 30
  type: integer
```

## Tool Call Patterns

### Pattern 1: Visual Service Probing

```yaml
workflow: visual_probe
description: "Capture screenshot, OCR, analyze, report"
steps:
  1:
    tool: browser_screenshot
    args: {url: "http://service:port"}
    result: {imageBase64: "string", timestamp: number}

  2:
    internal: ocr_extraction
    condition: "if OCR_MODEL is set"
    input: imageBase64
    output: extracted_text

  3:
    tool: finish
    args: {status: "ok|failed", reason: "wellness report"}

example_code: |
  curl -X POST http://kfuncdb:8081/call-tool \
    -H "Content-Type: application/json" \
    -d '{
      "name": "browser_screenshot",
      "args": {"url": "http://grafana:3000"}
    }'
```

### Pattern 2: Container Diagnostics

```yaml
workflow: container_diagnosis
description: "Inspect container state, logs, resources"
steps:
  1:
    tool: host.docker.inspect
    args: {name: "container-name"}
    result: "Full Docker inspect JSON"

  2:
    tool: docker_logs
    args: {container: "container-name", tail: 100}
    result: {logs: "string"}

  3:
    tool: docker_stats
    args: {container: "container-name"}
    result: {cpu_percent, mem_usage, mem_percent}

analysis_pattern: |
  - Check State.Health.Status
  - Look for error patterns in logs
  - Compare resource usage to limits
  - Identify restart loops from State.RestartCount
```

### Pattern 3: LLM-Powered Analysis

```yaml
workflow: llm_root_cause_analysis
description: "Use LLM to analyze diagnostic data"
input_data:
  - service_logs
  - resource_metrics
  - http_responses
  - screenshot_ocr

prompt_template: |
  DIAGNOSTIC ANALYSIS TASK

  Service: {service_name}
  Status: {status}

  Logs:
  {logs}

  Metrics:
  {metrics}

  Generate JSON:
  {
    "root_cause": "hypothesis",
    "severity": "critical|warning|info",
    "fixes": [
      {"action": "restart|scale|config", "confidence": "high|medium|low", "reasoning": "..."}
    ]
  }

llm_endpoint: "http://litellm:4000/v1/chat/completions"
auth: "Authorization: Bearer ${LITELLM_MASTER_KEY}"
```

## Decision Trees

### When to Use Which Service

```yaml
task_routing:
  capture_screenshot:
    service: kfuncdb
    endpoint: POST /call-tool
    tool_name: browser_screenshot

  extract_dom:
    service: kfuncdb
    endpoint: POST /call-tool
    tool_name: browser_dom

  check_service_health:
    service: probe-orchestrator
    endpoint: POST /start-probe

  diagnose_failures:
    service: probe-orchestrator
    endpoint: POST /analyze-and-propose-fixes

  restart_container:
    service: kfuncdb
    endpoint: POST /call-tool
    tool_name: docker_restart

  get_container_logs:
    service: kfuncdb
    endpoint: POST /call-tool
    tool_name: docker_logs

  llm_completion:
    service: litellm
    endpoint: POST /v1/chat/completions
    auth_required: true

  text_embedding:
    service: litellm
    endpoint: POST /v1/embeddings
    model: embed-small
```

### Troubleshooting Decision Tree

```yaml
issue_diagnosis:
  service_not_starting:
    steps:
      1. Check logs: "docker logs <service>"
      2. Inspect health: "docker inspect <service> | jq '.[].State.Health'"
      3. Check dependencies: "docker compose config --services"
      4. Verify network: "docker network inspect datamancy_backend"

  service_unhealthy:
    steps:
      1. Run probe: "curl -X POST http://localhost:8089/start-probe -d '{\"services\":[\"http://service:port\"]}'"
      2. Analyze: "curl -X POST http://localhost:8089/analyze-and-propose-fixes"
      3. Review proposed fixes
      4. Execute safe fixes: "curl -X POST http://localhost:8089/execute-fix -d '{...}'"

  gpu_not_available:
    steps:
      1. Check nvidia-smi on host
      2. Verify nvidia-container-toolkit installed
      3. Restart Docker daemon
      4. Test: "docker run --rm --gpus all nvidia/cuda:12.0.0-base-ubuntu22.04 nvidia-smi"

  oidc_login_fails:
    steps:
      1. Check Authelia logs: "docker logs authelia"
      2. Test LDAP: "docker exec authelia ldapsearch -x -H ldap://ldap:389 -b 'dc=stack,dc=local'"
      3. Verify Redis: "docker exec redis redis-cli KEYS 'authelia:*'"
      4. Clear sessions: "docker exec redis redis-cli FLUSHALL"
```

## Code Generation Templates

### Adding a New Kotlin Service

```kotlin
// Step 1: settings.gradle.kts
include(":my-service")
project(":my-service").projectDir = file("src/my-service")

// Step 2: src/my-service/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
    application
}

dependencies {
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.datamancy.myservice.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// Step 3: src/my-service/src/main/kotlin/Application.kt
package org.datamancy.myservice

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8090) {
        routing {
            get("/healthz") { call.respondText("OK") }
        }
    }.start(wait = true)
}
```

### Adding a Docker Compose Service

```yaml
# docker-compose.yml
services:
  my-service:
    build: ./src/my-service
    container_name: my-service
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
      start_period: 10s
```

### Adding a KFuncDB Plugin

```kotlin
package org.example.plugins

import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.api.LlmTool
import org.example.host.ToolRegistry
import org.example.manifest.Manifest

class MyCustomPlugin : Plugin {
    override fun manifest() = Manifest(
        id = "my-custom-plugin",
        version = "1.0.0",
        capabilities = listOf("custom.capability")
    )

    override fun init(ctx: PluginContext) {
        println("My custom plugin initialized")
    }

    override fun registerTools(registry: ToolRegistry) {
        registry.register(object : LlmTool {
            override val name = "my_custom_tool"
            override val description = "Does something custom"
            override val parameters = mapOf(
                "param1" to mapOf("type" to "string")
            )

            override fun call(args: Map<String, Any?>): Map<String, Any?> {
                val param1 = args["param1"] as? String ?: ""
                return mapOf("result" to "Processed: $param1")
            }
        })
    }

    override fun shutdown() {
        println("My custom plugin shutdown")
    }
}
```

## API Integration Examples

### Python

```python
import requests
import json

# Health check
response = requests.post("http://probe-orchestrator:8089/start-stack-probe")
report = response.json()

print(f"Total services: {report['summary']['total']}")
print(f"Healthy: {report['summary']['healthy']}")
print(f"Failed: {report['summary']['failed']}")

# Analyze failures
if report['summary']['failed'] > 0:
    analysis = requests.post("http://probe-orchestrator:8089/analyze-and-propose-fixes")
    issues = analysis.json()['issues']

    for issue in issues:
        print(f"Issue: {issue['service']}")
        print(f"Root cause: {issue['root_cause_hypothesis']}")
        for fix in issue['proposed_fixes']:
            if fix['confidence'] == 'high':
                print(f"Recommended: {fix['action']} - {fix['reasoning']}")
```

### JavaScript/TypeScript

```typescript
// LiteLLM chat completion
const response = await fetch('http://litellm:4000/v1/chat/completions', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${process.env.LITELLM_MASTER_KEY}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    model: 'hermes-2-pro-mistral-7b',
    messages: [
      { role: 'user', content: 'What is 2+2?' }
    ]
  })
});

const completion = await response.json();
console.log(completion.choices[0].message.content);
```

### Bash

```bash
#!/bin/bash
# Automated health check and repair

# Run stack probe
REPORT=$(curl -s -X POST http://probe-orchestrator:8089/start-stack-probe)
FAILED=$(echo "$REPORT" | jq '.summary.failed')

if [ "$FAILED" -gt 0 ]; then
  echo "Found $FAILED failed services"

  # Analyze and get fixes
  ANALYSIS=$(curl -s -X POST http://probe-orchestrator:8089/analyze-and-propose-fixes)

  # Extract high-confidence fixes
  FIXES=$(echo "$ANALYSIS" | jq -r '.issues[] | select(.proposed_fixes[0].confidence == "high") | @json')

  # Execute each fix
  echo "$FIXES" | while read -r issue; do
    SERVICE=$(echo "$issue" | jq -r '.service')
    ACTION=$(echo "$issue" | jq -r '.proposed_fixes[0].action')

    echo "Executing $ACTION on $SERVICE..."

    curl -X POST http://probe-orchestrator:8089/execute-fix \
      -H "Content-Type: application/json" \
      -d "{
        \"issue_id\": \"$(echo "$issue" | jq -r '.id')\",
        \"service\": \"$SERVICE\",
        \"service_url\": \"http://$SERVICE:3000\",
        \"container\": \"$SERVICE\",
        \"fix_action\": \"$ACTION\"
      }"
  done
fi
```

## Key Concepts for Agents

### Plugin Architecture
- KFuncDB uses plugin-based design
- Plugins are registered via factory functions (no reflection)
- Capabilities are declared in manifests
- Tools are registered to ToolRegistry
- OpenAI function calling compatible

### Autonomous Probing
- Probe Orchestrator uses LLM agents
- Agents have access to tools (screenshot, DOM, HTTP)
- Workflow: observe → tool call → analyze → finish
- Evidence-based diagnosis (screenshots, logs, metrics)

### LRU Model Management
- vLLM Router manages GPU VRAM
- Keeps maxResident=2 models loaded
- Evicts least recently used when capacity reached
- Transparent to clients (OpenAI compatible)

### Service Discovery
- Services defined in `configs/probe-orchestrator/services_manifest.json`
- Each service has internal and external URLs
- Domain substitution via ${DOMAIN} variable
- Automatic probe target generation

### OIDC Integration
- Authelia is central OIDC provider
- All applications integrate via OAuth2
- Sessions stored in Redis
- Users managed in OpenLDAP

---

## Quick Reference

**Build:** `./gradlew build`
**Test:** `./gradlew test`
**Deploy:** `docker compose --profile bootstrap up -d`
**Logs:** `docker compose logs -f`
**Health:** `curl http://localhost:8089/start-stack-probe`
**Analyze:** `curl http://localhost:8089/analyze-and-propose-fixes`
**Tools:** `curl http://localhost:8081/tools`

**Documentation:**
- README.md - Project overview
- ARCHITECTURE.md - System design
- DEVELOPMENT.md - Developer guide
- DEPLOYMENT.md - Deployment procedures
- API.md - API endpoints
- AGENT_GUIDE.md - This file

---

**End of Agent Guide** - For human-readable docs, see README.md
