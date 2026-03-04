FROM vllm/vllm-openai:v0.13.0

ENV HF_HOME=/models
ENV TRANSFORMERS_CACHE=/models
ENV HF_HUB_CACHE=/models

ARG HUGGINGFACE_TOKEN
RUN python3 -c "from huggingface_hub import snapshot_download; snapshot_download('Qwen/Qwen2.5-7B-Instruct-AWQ', cache_dir='/models', token='${HUGGINGFACE_TOKEN}' if '${HUGGINGFACE_TOKEN}' else None)"
