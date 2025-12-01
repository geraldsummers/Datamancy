# API Reference

Complete API documentation for all Datamancy services.

## Table of Contents

- [KFuncDB (Agent Tool Server)](#kfuncdb-agent-tool-server)
- [Probe Orchestrator](#probe-orchestrator)
- [Speech Gateway](#speech-gateway)
- [vLLM Router](#vllm-router)
- [LiteLLM](#litellm)
- [Embedding Service](#embedding-service)

## KFuncDB (Agent Tool Server)

Base URL: `http://kfuncdb:8081`

### GET /tools

List all available tools and their schemas.

**Response:**
```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "browser_screenshot",
        "description": "Capture a screenshot of a URL",
        "parameters": {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "URL to capture"
            }
          },
          "required": ["url"]
        }
      }
    }
  ]
}
```

### POST /call-tool

Execute a tool by name with arguments.

**Request:**
```json
{
  "name": "browser_screenshot",
  "args": {
    "url": "https://grafana.stack.local"
  }
}
```

**Response:**
```json
{
  "result": {
    "success": true,
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
    "url": "https://grafana.stack.local",
    "timestamp": 1704067200000
  },
  "elapsedMs": 1234
}
```

### Available Tools

#### browser_screenshot
```json
{
  "name": "browser_screenshot",
  "args": {"url": "string"}
}
```
Returns: `{"imageBase64": "string", "url": "string", "timestamp": number}`

#### browser_dom
```json
{
  "name": "browser_dom",
  "args": {"url": "string"}
}
```
Returns: `{"html": "string", "title": "string", "url": "string"}`

#### http_get
```json
{
  "name": "http_get",
  "args": {"url": "string"}
}
```
Returns: `{"status": number, "headers": {}, "body": "string"}`

#### docker_inspect
```json
{
  "name": "host.docker.inspect",
  "args": {"name": "container-name"}
}
```
Returns: Docker inspect JSON

#### docker_logs
```json
{
  "name": "docker_logs",
  "args": {"container": "string", "tail": number}
}
```
Returns: `{"logs": "string"}`

#### docker_restart
```json
{
  "name": "docker_restart",
  "args": {"container": "string"}
}
```
Returns: `{"success": boolean, "output": "string"}`

#### docker_stats
```json
{
  "name": "docker_stats",
  "args": {"container": "string"}
}
```
Returns: `{"cpu_percent": "string", "mem_usage": "string", "mem_percent": "string"}`

#### ssh_exec
```json
{
  "name": "ssh.exec",
  "args": {
    "host": "string",
    "user": "string",
    "command": "string"
  }
}
```
Returns: `{"stdout": "string", "stderr": "string", "exitCode": number}`

#### llm_complete
```json
{
  "name": "llm.complete",
  "args": {
    "prompt": "string",
    "model": "string",
    "max_tokens": number
  }
}
```
Returns: `{"text": "string", "usage": {}}`

---

## Probe Orchestrator

Base URL: `http://probe-orchestrator:8089`

### GET /healthz

Health check endpoint.

**Response:**
```json
{"ok": true}
```

### POST /start-probe

Probe specific service URLs.

**Request:**
```json
{
  "services": [
    "http://grafana:3000",
    "http://portainer:9000"
  ]
}
```

**Response:**
```json
{
  "summary": [
    {
      "service": "http://grafana:3000",
      "status": "ok",
      "reason": "Screenshot captured with OCR analysis - Grafana login page detected",
      "screenshot_path": "/proofs/screenshots/grafana_1704067200.png"
    }
  ],
  "details": [
    {
      "service": "http://grafana:3000",
      "status": "ok",
      "reason": "Screenshot captured with OCR analysis...",
      "screenshot_path": "/proofs/screenshots/grafana_1704067200.png",
      "dom_excerpt": "<html>...</html>",
      "ocr_text": "Grafana\nSign in\nEmail or username...",
      "wellness_report": "{\"status\":\"operational\",...}",
      "steps": [
        {
          "step": 1,
          "tool": "browser_screenshot",
          "args": {"url": "http://grafana:3000"},
          "result": {"imageBase64": "[base64]"}
        },
        {
          "step": 2,
          "tool": "finish",
          "args": {"status": "ok", "reason": "..."}
        }
      ]
    }
  ]
}
```

### POST /start-stack-probe

Probe all services from manifest.

**Response:**
```json
{
  "summary": {
    "total": 10,
    "healthy": 8,
    "degraded": 1,
    "failed": 1
  },
  "report_path": "/proofs/stack_diagnostics_1704067200.json",
  "services": [
    {
      "name": "grafana",
      "results": [...],
      "overall_status": "ok",
      "best_reason": "Service responding normally",
      "best_screenshot": "/proofs/screenshots/grafana_1704067200.png",
      "container_info": {...}
    }
  ]
}
```

### POST /analyze-and-propose-fixes

Analyze failures and propose remediation.

**Response:**
```json
{
  "generated_at": 1704067200000,
  "report_id": "enhanced-1704067200",
  "summary": {
    "total": 10,
    "healthy": 8,
    "degraded": 1,
    "failed": 1,
    "issues": 2,
    "safe_actions": 1,
    "needs_review": 1
  },
  "issues": [
    {
      "id": "issue-grafana-1704067200",
      "service": "grafana",
      "severity": "warning",
      "status": "degraded",
      "evidence": ["screenshot:/proofs/...", "wellness_report"],
      "root_cause_hypothesis": "Service returning 503 errors",
      "log_excerpt": "level=error msg=\"database connection failed\"...",
      "resource_metrics": {
        "cpu": "45%",
        "memory": "512MB / 2GB",
        "mem_percent": "25%"
      },
      "proposed_fixes": [
        {
          "action": "restart",
          "confidence": "high",
          "reasoning": "Database connection pool exhausted, restart will reset connections",
          "parameters": {}
        }
      ]
    }
  ],
  "automated_actions_safe": ["grafana: restart"],
  "requires_human_review": ["outline: check_config"],
  "base_report_path": "/proofs/stack_diagnostics_1704067200.json"
}
```

### POST /execute-fix

Execute an approved fix action.

**Request:**
```json
{
  "issue_id": "issue-grafana-1704067200",
  "service": "grafana",
  "service_url": "http://grafana:3000",
  "container": "grafana",
  "fix_action": "restart",
  "fix_parameters": {}
}
```

**Response:**
```json
{
  "success": true,
  "issue_id": "issue-grafana-1704067200",
  "service": "grafana",
  "action_taken": "restart",
  "before_status": "HTTP 503",
  "after_status": "HTTP 200",
  "verification": "Service grafana was restarted. HTTP status changed from 503 to 200. Service is now responding with healthy status. Health check: healthy.",
  "steps": [
    {"step": 1, "tool": "http_get", "args": {...}, "result": {"status": 503}},
    {"step": 2, "tool": "restart", "args": {...}, "result": {"success": true}},
    {"step": 3, "tool": "docker_health_wait", "args": {...}, "result": {"status": "healthy"}},
    {"step": 4, "tool": "http_get", "args": {...}, "result": {"status": 200}}
  ],
  "elapsed_ms": 45123
}
```

---

## Speech Gateway

Base URL: `http://ktspeechgateway:8091`

### GET /healthz

Health check.

**Response:**
```json
{"ok": true}
```

### POST /transcribe

Transcribe audio to text (Whisper).

**Request:**
```bash
curl -X POST http://ktspeechgateway:8091/transcribe \
  -F "file=@audio.wav" \
  -F "language=en"
```

**Response:**
```json
{
  "text": "Hello, this is a test transcription.",
  "language": "en",
  "duration": 3.5
}
```

### POST /synthesize

Synthesize text to speech (Piper).

**Request:**
```json
{
  "text": "Hello, this is a test.",
  "voice": "en_US-lessac-medium"
}
```

**Response:**
Binary audio data (WAV format)

---

## vLLM Router

Base URL: `http://vllm-router:8010`

### GET /health

Health check.

**Response:**
```
OK
```

### GET /v1/models

List currently loaded models.

**Response:**
```json
{
  "object": "list",
  "data": [
    {
      "id": "hermes-2-pro-mistral-7b",
      "object": "model",
      "created": 1704067200,
      "owned_by": "vllm"
    }
  ]
}
```

### POST /v1/chat/completions

Chat completion with automatic model loading.

**Request:**
```json
{
  "model": "hermes-2-pro-mistral-7b",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ],
  "temperature": 0.7,
  "max_tokens": 100
}
```

**Response:**
```json
{
  "id": "chatcmpl-123",
  "object": "chat.completion",
  "created": 1704067200,
  "model": "hermes-2-pro-mistral-7b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 15,
    "total_tokens": 25
  }
}
```

### POST /v1/completions

Text completion.

**Request:**
```json
{
  "model": "hermes-2-pro-mistral-7b",
  "prompt": "Once upon a time",
  "max_tokens": 50,
  "temperature": 0.8
}
```

**Response:**
```json
{
  "id": "cmpl-123",
  "object": "text_completion",
  "created": 1704067200,
  "model": "hermes-2-pro-mistral-7b",
  "choices": [
    {
      "text": " in a land far away...",
      "index": 0,
      "finish_reason": "length"
    }
  ]
}
```

---

## LiteLLM

Base URL: `http://litellm:4000`

### Authentication

All requests require Bearer token:
```bash
Authorization: Bearer ${LITELLM_MASTER_KEY}
```

### GET /health

Health check.

**Response:**
```json
{"status": "healthy"}
```

### GET /v1/models

List available models.

**Response:**
```json
{
  "data": [
    {
      "id": "hermes-2-pro-mistral-7b",
      "object": "model",
      "created": 1704067200,
      "owned_by": "vllm"
    },
    {
      "id": "embed-small",
      "object": "model",
      "created": 1704067200,
      "owned_by": "huggingface"
    }
  ]
}
```

### POST /v1/chat/completions

Unified chat completion endpoint.

**Request:**
```bash
curl http://litellm:4000/v1/chat/completions \
  -H "Authorization: Bearer ${LITELLM_MASTER_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "hermes-2-pro-mistral-7b",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is 2+2?"}
    ],
    "temperature": 0.1
  }'
```

**Response:**
```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1704067200,
  "model": "hermes-2-pro-mistral-7b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "2 + 2 = 4"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 8,
    "total_tokens": 28
  }
}
```

### POST /v1/embeddings

Generate text embeddings.

**Request:**
```json
{
  "model": "embed-small",
  "input": "The quick brown fox jumps over the lazy dog."
}
```

**Response:**
```json
{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "embedding": [0.123, -0.456, 0.789, ...],
      "index": 0
    }
  ],
  "model": "embed-small",
  "usage": {
    "prompt_tokens": 12,
    "total_tokens": 12
  }
}
```

### Streaming Completions

**Request:**
```bash
curl http://litellm:4000/v1/chat/completions \
  -H "Authorization: Bearer ${LITELLM_MASTER_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "hermes-2-pro-mistral-7b",
    "messages": [{"role": "user", "content": "Tell me a story"}],
    "stream": true
  }'
```

**Response:** (Server-Sent Events)
```
data: {"id":"chatcmpl-123","choices":[{"delta":{"role":"assistant"},"index":0}]}

data: {"id":"chatcmpl-123","choices":[{"delta":{"content":"Once"},"index":0}]}

data: {"id":"chatcmpl-123","choices":[{"delta":{"content":" upon"},"index":0}]}

...

data: [DONE]
```

---

## Embedding Service

Base URL: `http://embedding-service:8080`

### GET /health

Health check.

**Response:**
```
OK
```

### POST /embed

Generate embeddings (Hugging Face TEI format).

**Request:**
```json
{
  "inputs": "The quick brown fox"
}
```

**Response:**
```json
[
  [0.123, -0.456, 0.789, ...]
]
```

### POST /embed (Batch)

**Request:**
```json
{
  "inputs": [
    "First sentence to embed",
    "Second sentence to embed"
  ]
}
```

**Response:**
```json
[
  [0.123, -0.456, 0.789, ...],
  [0.321, -0.654, 0.987, ...]
]
```

---

## Error Responses

All services use consistent error format:

```json
{
  "error": {
    "message": "Description of error",
    "type": "invalid_request_error",
    "code": "invalid_parameter"
  }
}
```

**Common HTTP Status Codes:**

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad Request (invalid parameters) |
| 401 | Unauthorized (missing/invalid auth token) |
| 404 | Not Found (endpoint or resource) |
| 500 | Internal Server Error |
| 503 | Service Unavailable (service not ready) |

---

## Rate Limiting

Currently no rate limiting is enforced. For production:

- Configure Caddy rate limiting in `configs/infrastructure/caddy/Caddyfile`
- Use LiteLLM's built-in rate limiting features
- Add API gateway layer (e.g., Kong, Traefik)

---

## Authentication Summary

| Service | Auth Method | Header/Token |
|---------|-------------|--------------|
| KFuncDB | None (internal) | - |
| Probe Orchestrator | None (internal) | - |
| Speech Gateway | None (internal) | - |
| vLLM Router | None (proxied) | - |
| LiteLLM | Bearer Token | `Authorization: Bearer ${LITELLM_MASTER_KEY}` |
| Embedding Service | None (internal) | - |
| Open WebUI | OIDC + Session | Cookie-based |
| Grafana | OIDC | Cookie-based |
| Portainer | Local Auth | Cookie-based |

**Note:** All external-facing services go through Caddy + Authelia for authentication.

---

**Next**: See [AGENT_GUIDE.md](AGENT_GUIDE.md) for AI agent integration patterns.
