FROM debian:bookworm-slim AS downloader
RUN apt-get update && apt-get install -y curl git-lfs && \
    git lfs install && \
    rm -rf /var/lib/apt/lists/*

RUN git clone https://huggingface.co/BAAI/bge-m3 /models/bge-m3

FROM ghcr.io/huggingface/text-embeddings-inference:cpu-1.8.3
COPY --from=downloader /models/bge-m3 /data/bge-m3
