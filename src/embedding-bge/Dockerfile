# Custom text-embeddings-inference image with BGE-M3 model pre-baked
# BGE-M3 advantages over bge-base-en-v1.5:
# - 8192 token context (vs 512) - 16x larger!
# - 1024 dimensions (vs 768) - better embeddings
# - 100+ languages (vs English only)
# - Dense + multi-vector + sparse retrieval

# Stage 1: Download model files (debian has curl pre-installed)
FROM debian:bookworm-slim AS downloader
RUN apt-get update && apt-get install -y curl git-lfs && \
    git lfs install && \
    rm -rf /var/lib/apt/lists/*

# Clone the model repo with git-lfs to download all files
RUN git clone https://huggingface.co/BAAI/bge-m3 /models/bge-m3

# Stage 2: Copy model into CPU TEI image
FROM ghcr.io/huggingface/text-embeddings-inference:cpu-1.8.3
COPY --from=downloader /models/bge-m3 /data/bge-m3

# The entrypoint is inherited from the base image
# Runtime will use: --model-id /data/bge-m3 --dtype float32
