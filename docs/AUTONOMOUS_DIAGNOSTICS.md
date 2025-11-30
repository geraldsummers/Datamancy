# Autonomous Stack Diagnostics & Self-Healing

## Overview

Datamancy includes an **autonomous diagnostic system** that uses local AI (vLLM + LiteLLM) to probe services, analyze failures, and propose fixes—minimizing expensive cloud LLM costs by doing heavy analysis locally.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Supervisor Workflow                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  1. diagnose-enhanced  ──────────►  probe-orchestrator           │
│     (local LLM analysis)            - Screenshots + DOM          │
│                                     - Container logs             │
│                                     - Resource metrics           │
│                                     - AI root cause analysis     │
│                                     ↓                            │
│  2. review             ◄────────── Enhanced Diagnostics Report  │
│     (human oversight)               - Issues with evidence       │
│                                     - Proposed fixes             │
│                                     - Confidence ratings         │
│                                     ↓                            │
│  3. approve fixes      ──────────► Approved Actions File        │
│     (interactive CLI)               ↓                            │
│                                                                   │
│  4. execute fixes (Phase 2 - coming soon)                       │
│     (automated + safe guards)                                    │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Components

1. **probe-orchestrator** (Kotlin service)
   - Autonomous agent using Hermes-2-Pro-Mistral-7B
   - Probes services via HTTP, browser automation, DOM inspection
   - Analyzes failures using local LLM
   - Generates structured fix proposals

2. **kfuncdb** (Tool server)
   - Provides capabilities: browser, HTTP, Docker inspect/logs/stats
   - Safe read-only operations by default
   - Extensible plugin architecture

3. **vLLM + LiteLLM** (Local inference stack)
   - Free local compute (no API costs)
   - Function calling support
   - OpenAI-compatible API

4. **Review CLI** (Python)
   - Interactive approval workflow
   - Evidence display (logs, metrics, screenshots)
   - Generates approved actions manifest

---

## Quick Start

### Run Enhanced Diagnostics

```bash
# Trigger full stack analysis with AI-powered fix proposals
./scripts/supervisor-session.sh diagnose-enhanced
```

This will:
- Probe all services in `configs/probe-orchestrator/services_manifest.json`
- Capture screenshots and DOM data
- Fetch container logs and resource stats
- Use local LLM to analyze failures and propose fixes
- Save report to `volumes/proofs/enhanced_diagnostics_<timestamp>.json`

### Review Issues and Approve Fixes

```bash
# Interactive review session
./scripts/supervisor-session.sh review
```

For each issue, you'll see:
- Service name and severity
- Root cause hypothesis (from AI analysis)
- Container logs excerpt
- Resource metrics (CPU, memory)
- Proposed fixes with confidence ratings

You can:
- **[a]** Approve all fixes
- **[s]** Select specific fixes
- **[d]** Defer for later
- **[i]** Ignore the issue
- **[q]** Quit and resume later

Approved actions are saved to `volumes/proofs/approved_fixes_<timestamp>.json`

---

## Diagnostic Reports

### Enhanced Diagnostics Report Schema

```json
{
  "generated_at": 1732800000000,
  "report_id": "enhanced-1732800000",
  "summary": {
    "total": 15,
    "healthy": 12,
    "degraded": 2,
    "failed": 1,
    "issues": 3,
    "safe_actions": 2,
    "needs_review": 1
  },
  "issues": [
    {
      "id": "issue-vllm-router-1732800000",
      "service": "vllm-router",
      "severity": "warning",
      "status": "unhealthy",
      "evidence": [
        "screenshot:/proofs/screenshots/...",
        "wellness_report"
      ],
      "root_cause_hypothesis": "Container is restarting due to connection timeout to upstream vLLM service",
      "log_excerpt": "ERROR: Connection refused to vllm:8000\n...",
      "resource_metrics": {
        "cpu": "0.5%",
        "memory": "128MiB / 2GiB",
        "mem_percent": "6.25%"
      },
      "proposed_fixes": [
        {
          "action": "restart",
          "confidence": "high",
          "reasoning": "Service is in restart loop; clean restart often resolves transient connection issues",
          "parameters": {}
        },
        {
          "action": "check_dependencies",
          "confidence": "medium",
          "reasoning": "Check if vllm service is healthy and reachable",
          "parameters": {"dependency": "vllm"}
        }
      ]
    }
  ],
  "automated_actions_safe": [
    "vllm-router: restart",
    "open-webui: check_logs"
  ],
  "requires_human_review": [
    "postgres: check_config"
  ],
  "base_report_path": "/proofs/stack_diagnostics_1732800000.json"
}
```

### Approved Actions Manifest

After review, approved fixes are saved in this format:

```json
{
  "generated_at": "2025-11-30T12:00:00",
  "source_report": "/proofs/enhanced_diagnostics_1732800000.json",
  "approved_actions": [
    {
      "issue_id": "issue-vllm-router-1732800000",
      "service": "vllm-router",
      "action": "restart",
      "confidence": "high",
      "reasoning": "Service is in restart loop...",
      "parameters": {}
    }
  ]
}
```

---

## Available Tools (via kfuncdb)

The probe orchestrator uses these tools to gather diagnostic data:

### Browser Tools
- `browser_screenshot(url)` - Capture visual proof
- `browser_dom(url)` - Extract DOM structure

### HTTP Tools
- `http_get(url, headers)` - Check API endpoints

### Docker Tools
- `docker_list_containers()` - List all containers
- `docker_logs(container, tail=200)` - Fetch container logs
- `docker_stats(container)` - Get CPU/memory/network stats
- `docker_inspect(container)` - Get full container metadata

### Host Tools
- `host_exec_readonly(cmd, cwd)` - Safe read-only commands

All tools are read-only by default to prevent accidental mutations during diagnostics.

---

## Configuration

### Services Manifest

Edit `configs/probe-orchestrator/services_manifest.json` to control which services are monitored:

```json
{
  "services": [
    {
      "name": "open-webui",
      "internal": ["http://open-webui:8080/health"],
      "external": ["https://open-webui.${DOMAIN}"]
    }
  ]
}
```

- **name**: Container/service name (for Docker logs/stats)
- **internal**: URLs probed from inside Docker network
- **external**: Public URLs probed (if reachable)

Variables like `${DOMAIN}` are expanded at runtime.

### Environment Variables

Set in `.env` or docker-compose environment:

```bash
# LLM configuration
LLM_BASE_URL=http://litellm:4000/v1
LLM_MODEL=hermes-2-pro-mistral-7b
LLM_API_KEY=<your-litellm-master-key>

# Optional: Vision model for OCR (disabled by default)
OCR_MODEL=none

# Probe behavior
MAX_STEPS=12
HTTP_TIMEOUT=30

# Paths
PROOFS_DIR=/proofs
SERVICES_MANIFEST_PATH=/app/configs/probe-orchestrator/services_manifest.json
```

---

## Cost Efficiency Model

### Local LLM Does Heavy Lifting (Free)
- Service probing and screenshot capture
- Log analysis (100-500 lines per service)
- Resource metrics analysis
- Root cause hypothesis generation
- Fix proposal generation
- Runs on your GPU/CPU - no API costs

### You (Expensive Claude) Do Minimal Work
- Review summarized issues (5-10 per session)
- Approve/reject fixes (interactive CLI, ~5-10 minutes)
- Override AI recommendations when needed

### Cost Comparison
- **Traditional approach**: Claude analyzes all logs, configs, screenshots ($$$)
- **This approach**: Local LLM does analysis, you review summary ($)
- **Estimated savings**: 90-95% reduction in expensive LLM costs

---

## Workflow Examples

### Example 1: vllm-router is unhealthy

```bash
$ ./scripts/supervisor-session.sh diagnose-enhanced
[supervisor] Triggering ENHANCED diagnostics...
Analyzing vllm-router (degraded)...
Enhanced diagnostic report saved: /proofs/enhanced_diagnostics_1732800000.json

$ ./scripts/supervisor-session.sh review
Issue #1: vllm-router
Severity: WARNING
Status: degraded

Root Cause Analysis:
  Container repeatedly restarting; connection to vllm:8000 timing out

Log Excerpt (last 20 lines):
  ERROR: Connection refused to vllm:8000
  INFO: Retrying in 5s...
  ERROR: Connection refused to vllm:8000

Resource Metrics:
  cpu: 0.5%
  memory: 128MiB / 2GiB

Proposed Fixes:
  1. Action: restart
     Confidence: high
     Reasoning: Clean restart often resolves transient connection issues

  2. Action: check_dependencies
     Confidence: medium
     Reasoning: Verify vllm service is healthy

Review (1/1):
  [a] Approve all
  [s] Select specific
  [d] Defer
  [i] Ignore
  [q] Quit

Your choice: a
✓ Approved 2 fix(es) for vllm-router
✅ Approved actions saved to: /proofs/approved_fixes_20251130_120000.json
```

### Example 2: No issues found

```bash
$ ./scripts/supervisor-session.sh diagnose-enhanced
[supervisor] Triggering ENHANCED diagnostics...
Enhanced diagnostic report saved: /proofs/enhanced_diagnostics_1732800000.json

$ ./scripts/supervisor-session.sh review
✅ No issues found! All services healthy.
```

---

## Troubleshooting

### Diagnostics fail to run

```bash
# Check probe-orchestrator is running
docker ps --filter name=probe-orchestrator

# Check logs
docker logs probe-orchestrator --tail 50

# Verify kfuncdb is accessible
curl http://localhost:8081/healthz
```

### LLM analysis produces poor quality

1. Check vLLM is healthy: `docker logs vllm --tail 50`
2. Verify LiteLLM config: `cat configs/infrastructure/litellm/config.yaml`
3. Increase context in prompts: Edit `MAX_STEPS` in `.env`
4. Try different temperature: Edit `Application.kt` analysis temperature

### No issues detected when there are problems

1. Update services manifest with correct health check URLs
2. Check if services are in the manifest: `cat configs/probe-orchestrator/services_manifest.json`
3. Run basic diagnostics first: `./scripts/supervisor-session.sh diagnose`

---

## Roadmap

### Phase 1: Enhanced Diagnostics ✅ (Current)
- AI-powered root cause analysis
- Fix proposal generation
- Interactive review CLI
- Approved actions manifest

### Phase 2: Execution Engine (Next)
- Safe execution of approved fixes
- Rollback support
- Verification loop (re-diagnose after fix)
- Idempotency guarantees

### Phase 3: Gradual Autonomy
- Whitelist of "always safe" actions
- Auto-approve high-confidence restarts
- Learning from historical fixes
- Pattern library in ClickHouse

### Phase 4: Self-Healing Stack
- Automatic detection → diagnosis → fix → verify
- Human-in-the-loop only for novel issues
- Continuous monitoring with scheduled probes
- Alerting integration (email, Slack, etc.)

---

## API Reference

### probe-orchestrator Endpoints

#### `POST /analyze-and-propose-fixes`

Run enhanced diagnostics with AI analysis.

**Response:**
```json
{
  "generated_at": 1732800000000,
  "report_id": "enhanced-1732800000",
  "summary": {...},
  "issues": [...],
  "automated_actions_safe": [...],
  "requires_human_review": [...]
}
```

#### `POST /start-stack-probe`

Run basic stack probe (no AI analysis).

**Response:**
```json
{
  "summary": {
    "total": 15,
    "healthy": 13,
    "degraded": 1,
    "failed": 1
  },
  "report_path": "/proofs/stack_diagnostics_1732800000.json",
  "services": [...]
}
```

#### `GET /healthz`

Health check endpoint.

**Response:**
```json
{"ok": true}
```

---

## Contributing

To extend the diagnostic system:

1. **Add new diagnostic tools**: Edit `src/kfuncdb/src/main/kotlin/org/example/plugins/HostToolsPlugin.kt`
2. **Improve AI prompts**: Edit analysis prompts in `src/probe-orchestrator-kt/src/main/kotlin/org/datamancy/probe/Application.kt`
3. **Add new fix actions**: Extend the fix action enum and implement executors
4. **Improve review UX**: Use `scripts/supervisor-session.sh review` (wrapper around the review workflow)

See `ARCHITECTURE.md` for more details on the plugin system.

---

## Security Considerations

- All diagnostic tools are **read-only** by default
- Docker socket access is proxied via `docker-proxy` with limited permissions
- SSH access (if configured) uses forced-command wrappers
- No credentials or secrets are logged
- Screenshots and reports stored in isolated volume
- LLM prompts don't include sensitive data

---

## License

Same as parent project (see repository root).
