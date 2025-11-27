#!/usr/bin/env bash
# setup-host.sh — Post-install host preparation for Datamancy
#
# This script keeps the ISO generic. After a fresh Debian/Ubuntu install:
#   1) git clone this repo
#   2) cd into the repo
#   3) run:  sudo ./scripts/setup-host.sh
#   4) log out and back in (or reboot) to pick up docker group membership
#   5) run:  ./scripts/bootstrap-stack.sh up-bootstrap
#
# What it does:
# - Installs Docker Engine + compose plugin from Docker's official repo
# - Enables and starts Docker
# - Adds the invoking user to the docker group
# - Creates a workspace directory (optional)
set -euo pipefail

require_root() {
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    echo "Please run this script with sudo." >&2
    exit 1
  fi
}

is_debian_like() {
  . /etc/os-release 2>/dev/null || return 1
  case "${ID_LIKE:-$ID}" in
    *debian*|*ubuntu*|debian|ubuntu) return 0 ;;
    *) return 1 ;;
  esac
}

install_docker_debian() {
  apt-get update
  apt-get install -y ca-certificates curl gnupg lsb-release

  install -m 0755 -d /etc/apt/keyrings
  if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
    curl -fsSL https://download.docker.com/linux/$(. /etc/os-release && echo "$ID")/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi

  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list

  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

  systemctl enable --now docker
}

add_user_to_docker() {
  local target_user
  target_user="${SUDO_USER:-$USER}"
  usermod -aG docker "$target_user" || true
}

main() {
  require_root
  if ! is_debian_like; then
    echo "This script currently supports Debian/Ubuntu only." >&2
    exit 1
  fi

  install_docker_debian
  add_user_to_docker

  echo
  echo "Docker installed and started. The user '${SUDO_USER:-$USER}' was added to the 'docker' group."
  echo "You must log out and back in (or reboot) before running docker commands without sudo."
  echo
  echo "Next steps:"
  echo "  1) Re-login"
  echo "  2) cd to this repo"
  echo "  3) ./scripts/bootstrap-stack.sh up-bootstrap"
  echo "  4) Open http://<host>:8080 in your browser"
}

main "$@"
