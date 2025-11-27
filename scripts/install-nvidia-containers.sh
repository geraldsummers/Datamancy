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

# Then ensure the LocalAI service in docker-compose.yml uses a GPU-enabled image, e.g.:
#   image: localai/localai:v3.7.0-aio-gpu-nvidia-cuda-12
# If you previously used a CPU image, switch to the GPU tag above.

# Restart bootstrap set (local-only)
bash scripts/bootstrap-stack.sh down-bootstrap || true
bash scripts/bootstrap-stack.sh up-bootstrap