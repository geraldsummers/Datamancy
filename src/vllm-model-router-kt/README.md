# vLLM Model Router

A Kotlin-based intelligent model router for vLLM that automatically manages GPU memory by loading and unloading models based on usage patterns.

## Overview

The vLLM Model Router sits between client applications (like LiteLLM) and vLLM, providing automatic VRAM management through intelligent model loading/unloading. It uses an LRU (Least Recently Used) eviction strategy to keep the most frequently requested models in memory while ensuring VRAM limits are respected.

## Architecture

```
Client (LiteLLM) → vLLM Router (Port 8010) → vLLM (Port 8000)
```

### Key Features

- **Automatic Model Loading**: Requested models are automatically loaded on-demand
- **LRU Eviction**: Least recently used models are unloaded when VRAM is needed
- **Configurable Capacity**: Maintains up to 2 models in VRAM simultaneously (configurable)
- **OpenAI-Compatible API**: Drop-in replacement for direct vLLM access
- **Streaming Support**: Full support for streaming completions
- **Health Monitoring**: Built-in health check endpoint

## Configuration

### Environment Variables

- `PORT` or `ROUTER_PORT`: Server port (default: 8010)
- `VLLM_BASE_URL`: vLLM server URL (default: http://vllm:8000)

### Docker Compose

```yaml
vllm-router:
  build: ./src/vllm-model-router-kt
  container_name: vllm-router
  restart: unless-stopped
  networks:
    - backend
  depends_on:
    vllm:
      condition: service_healthy
  environment:
    - VLLM_BASE_URL=http://vllm:8000
    - PORT=8010
  healthcheck:
    test: ["CMD", "wget", "--spider", "-q", "http://localhost:8010/health"]
    interval: 30s
    timeout: 5s
    retries: 10
    start_period: 30s
```

## API Endpoints

### Health Check
```
GET /health
```
Returns: `OK`

### List Models
```
GET /v1/models
```
Returns list of currently loaded models (proxied from vLLM)

### Chat Completions
```
POST /v1/chat/completions
```
OpenAI-compatible chat completions with automatic model management

### Completions
```
POST /v1/completions
```
OpenAI-compatible text completions with automatic model management

### Embeddings
```
POST /v1/embeddings
```
OpenAI-compatible embeddings with automatic model management

## Model Management Behavior

1. **Initial Request**: When a model is requested that isn't loaded:
   - Router checks if VRAM capacity would be exceeded
   - If yes, unloads the least recently used model
   - Loads the requested model
   - Waits for model to become available (up to 20 minutes)

2. **Subsequent Requests**: When a model is already loaded:
   - Updates the model's LRU position (marks as most recently used)
   - Immediately forwards the request to vLLM

3. **Capacity Management**:
   - Maximum resident models: 2 (configured in `ModelManager.maxResident`)
   - Tracks actual loaded models from vLLM's `/v1/models` endpoint
   - Self-healing: Synchronizes internal state with vLLM on each request

## Integration with LiteLLM

Configure LiteLLM to use the router instead of vLLM directly:

```yaml
model_list:
  - model_name: hermes-2-pro-mistral-7b
    litellm_params:
      model: openai/hermes-2-pro-mistral-7b
      api_base: http://vllm-router:8010/v1
      api_key: unused
      max_tokens: 4096
```

## Testing

Run the included test script:

```bash
./test-vllm-router.sh
```

Or test manually:

```bash
# Check health
curl http://vllm-router:8010/health

# List models
curl http://vllm-router:8010/v1/models

# Test completion
curl http://vllm-router:8010/v1/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "hermes-2-pro-mistral-7b",
    "prompt": "Hello world",
    "max_tokens": 10
  }'
```

## Building

### Docker Build
```bash
docker compose --profile bootstrap build vllm-router
```

### Local Gradle Build
```bash
cd src/vllm-model-router-kt
gradle clean shadowJar
java -jar build/libs/vllm-model-router-kt.jar
```

## Monitoring

### Router Logs
```bash
docker logs vllm-router
```

Key log messages:
- `Loading model {name} as served name {served_name}` - Model loading started
- `Unloading model {id}` - LRU eviction in progress
- `Failed to load model` - Model loading error
- `Timed out waiting for model to load` - Model didn't become available

### Current State
```bash
# See which models are currently loaded
curl http://vllm-router:8010/v1/models | jq '.data[].id'
```

## Troubleshooting

### Model Loading Timeout
If models take longer than 20 minutes to load, increase the timeout in `Application.kt`:
```kotlin
val deadlineMs = System.currentTimeMillis() + 20 * 60_000 // 20 minutes
```

### VRAM Issues
- Check GPU memory: `nvidia-smi`
- Reduce `maxResident` in `ModelManager` to load fewer models
- Reduce vLLM's `--gpu-memory-utilization` parameter

### Connection Errors
- Verify vLLM is running: `docker ps | grep vllm`
- Check vLLM health: `docker exec vllm curl -f http://localhost:8000/health`
- Verify network connectivity: `docker exec vllm-router wget -O- http://vllm:8000/health`

## Technology Stack

- **Language**: Kotlin 2.0.21
- **Server**: Ktor 3.0.0 (Netty)
- **HTTP Client**: Ktor CIO Client
- **Serialization**: kotlinx.serialization
- **Build**: Gradle 8.5 with Shadow plugin
- **Runtime**: Eclipse Temurin JRE 21

## Performance Considerations

- Model loading can take 30 seconds to several minutes depending on model size
- LRU tracking is in-memory only (resets on router restart)
- Streaming responses are fully supported with chunked transfer
- No caching layer - all requests go through to vLLM

## Future Enhancements

Potential improvements:
- Persistent LRU state across restarts
- Configurable maxResident via environment variable
- Proactive model preloading based on usage patterns
- Multi-vLLM backend support with load balancing
- Prometheus metrics for monitoring
- Model loading queue management
