# Custom Docker Images

This directory contains Dockerfiles for custom images used in the Datamancy stack.

## AI Model Images

These images bake the AI models directly into the image at build time, eliminating the need for runtime downloads from HuggingFace.

### vllm-qwen-7b
- **Base:** vllm/vllm-openai:v0.13.0
- **Model:** Qwen/Qwen2.5-7B-Instruct-AWQ (~4.5GB)
- **Purpose:** Main reasoning model for LLM inference

### embedding-bge
- **Base:** ghcr.io/huggingface/text-embeddings-inference:1.8.3
- **Model:** BAAI/bge-base-en-v1.5 (~400MB)
- **Purpose:** Text embeddings for RAG applications

## Building AI Images

These images are automatically built by `build-datamancy.main.kts`. However, you can build them manually:

```bash
# Build vLLM image with Qwen model
docker build -t datamancy/vllm-qwen-7b:latest docker/vllm-qwen-7b/

# Build embedding service with BGE model
docker build -t datamancy/embedding-bge:latest docker/embedding-bge/
```

**Note:** Building these images requires:
- Internet access to download models from HuggingFace
- ~10GB of free disk space (for intermediate layers and final images)
- 10-30 minutes depending on network speed

## HuggingFace Token (Optional)

For private models or to avoid rate limits, you can provide a HuggingFace token:

```bash
docker build --build-arg HUGGINGFACE_TOKEN=hf_yourtoken \
  -t datamancy/vllm-qwen-7b:latest docker/vllm-qwen-7b/
```

## Forgejo

Custom Forgejo image with the Datamancy repository embedded for easy access to source code.
