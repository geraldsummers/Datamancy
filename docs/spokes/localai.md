# LocalAI

**Service:** LocalAI
**Phase:** 5 - AI Tools
**Profile:** `ai`
**Hostname:** N/A (backend only)
**Internal Port:** 8080

## Purpose

LocalAI provides local AI inference capabilities without external API dependencies. Runs LLM models locally for privacy-first AI applications.

## Configuration

- **Image:** `quay.io/go-skynet/local-ai:v2.21.1-ffmpeg-core`
- **Threads:** 4
- **Context Size:** 512 tokens
- **Models Path:** `/build/models` (persistent volume)
- **Optimizations:** AVX512 support detected (AMD Ryzen 5 9600X)

## Models

Models are stored in the `localai_data` volume at `/build/models/`.

**Pre-installed models:**
- `ggml-gpt4all-j.bin` (3.6GB) - GPT4All-J model

**To add more models:**
```bash
docker exec localai curl -L -o /build/models/model-name.bin <URL>
```

## API Endpoints

LocalAI provides OpenAI-compatible API:

- `GET /v1/models` - List available models
- `POST /v1/completions` - Text completions
- `POST /v1/chat/completions` - Chat completions
- `GET /readyz` - Readiness check

## Usage Example

```bash
# List models
curl http://localai:8080/v1/models

# Generate completion
curl -X POST http://localai:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"ggml-gpt4all-j","prompt":"Hello!","max_tokens":50}'
```

## Integration

- **LibreChat:** Configured to use LocalAI as backend at `http://localai:8080/v1`
- **Network:** Backend only - not exposed via Caddy

## Security

- **User:** root (required by LocalAI image)
- **Network:** Backend network only
- **Telemetry:** Disabled (`DISABLE_TELEMETRY=true`)

## Troubleshooting

**Model not loading:**
- Check `/build/models/` directory has model files
- Review logs: `docker logs localai`
- Verify model file format (GGML format required)

**Slow inference:**
- Increase `THREADS` environment variable
- Check CPU usage and available RAM
- Consider smaller models for faster response

## References

- [LocalAI Documentation](https://localai.io/)
- [Model Gallery](https://localai.io/models/)
- [API Reference](https://localai.io/basics/getting_started/)
