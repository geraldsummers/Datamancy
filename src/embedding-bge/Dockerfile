# Custom text-embeddings-inference image with BGE base model pre-baked
FROM ghcr.io/huggingface/text-embeddings-inference:1.8.3 AS builder

# Set up model cache directory
ENV HF_HOME=/models
ENV TRANSFORMERS_CACHE=/models
ENV HF_HUB_CACHE=/models

# Pre-download the model at build time
# This requires the build to have internet access
ARG HUGGINGFACE_TOKEN
RUN apt-get update && apt-get install -y python3 python3-pip && \
    pip3 install --no-cache-dir huggingface-hub && \
    python3 -c "from huggingface_hub import snapshot_download; snapshot_download('BAAI/bge-base-en-v1.5', cache_dir='${HF_HOME}', token='${HUGGINGFACE_TOKEN}' if '${HUGGINGFACE_TOKEN}' else None)"

# Final stage - keep the base image but add our models
FROM ghcr.io/huggingface/text-embeddings-inference:1.8.3
COPY --from=builder /models /data

# The entrypoint is inherited from the base image
