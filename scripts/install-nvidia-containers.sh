# Add NVIDIA repository
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | \
  sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg

curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list

# Install toolkit
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit

# Configure Docker
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker

# Then ensure vLLM is configured to use the NVIDIA runtime and appropriate CUDA drivers.
# Example (in docker-compose.yml):
#   vllm:
#     image: vllm/vllm-openai:latest
#     deploy:
#       resources:
#         reservations:
#           devices:
#             - capabilities: [gpu]
#     environment:
#       - NVIDIA_VISIBLE_DEVICES=all
#       - VLLM_ATTENTION_BACKEND=FLASH_ATTENTION
#     runtime: nvidia
#
# Also ensure LiteLLM routes to vLLM as its backend.

# Restart bootstrap set (local-only)
bash scripts/bootstrap-stack.sh down-bootstrap || true
bash scripts/bootstrap-stack.sh up-bootstrap