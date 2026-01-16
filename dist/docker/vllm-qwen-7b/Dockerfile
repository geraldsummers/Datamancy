# Custom vLLM image with Qwen 2.5 7B Instruct AWQ pre-baked
FROM vllm/vllm-openai:v0.13.0

# Set up model cache directory
ENV HF_HOME=/models
ENV TRANSFORMERS_CACHE=/models
ENV HF_HUB_CACHE=/models

# Pre-download the model at build time
# This requires the build to have internet access and optionally a HF token
ARG HUGGINGFACE_TOKEN
RUN python3 -c "from huggingface_hub import snapshot_download; snapshot_download('Qwen/Qwen2.5-7B-Instruct-AWQ', cache_dir='${HF_HOME}', token='${HUGGINGFACE_TOKEN}' if '${HUGGINGFACE_TOKEN}' else None)"

# The entrypoint is inherited from the base image
