# jupyterhub/jupyterhub_config.py
#
# Multi-user JupyterHub with:
# - Generic OIDC to Authelia
# - DockerSpawner (per-user single-user server containers)
#
# Required environment variables:
# - BASE_DOMAIN (e.g. "localhost")
# - JUPYTERHUB_OIDC_CLIENT_ID
# - JUPYTERHUB_OIDC_CLIENT_SECRET
# - Optional: JUPYTERHUB_SINGLEUSER_IMAGE (default set in compose)
# - Optional: JUPYTERHUB_ADMIN_USERS (comma-separated list)
# - Optional: JUPYTERHUB_ALLOWED_USERS (comma-separated list)

import os

from oauthenticator.generic import GenericOAuthenticator
from dockerspawner import DockerSpawner
from traitlets.config import get_config

c = get_config()

BASE_DOMAIN = os.getenv("BASE_DOMAIN", "localhost")
CLIENT_ID = os.getenv("JUPYTERHUB_OIDC_CLIENT_ID")
CLIENT_SECRET = os.getenv("JUPYTERHUB_OIDC_CLIENT_SECRET")
CALLBACK_URL = f"https://jupyter.{BASE_DOMAIN}/hub/oauth_callback"

# Core hub settings
c.JupyterHub.bind_url = "http://:8000"
c.JupyterHub.cookie_secret_file = "/srv/jupyterhub/jupyterhub_cookie_secret"
c.JupyterHub.db_url = "sqlite:///jupyterhub.sqlite"
c.JupyterHub.base_url = "/"

# Authenticator: Generic OIDC (Authelia)
c.JupyterHub.authenticator_class = GenericOAuthenticator
c.GenericOAuthenticator.client_id = CLIENT_ID
c.GenericOAuthenticator.client_secret = CLIENT_SECRET
c.GenericOAuthenticator.oauth_callback_url = CALLBACK_URL

# Authelia endpoints
issuer_base = f"https://id.{BASE_DOMAIN}"
c.GenericOAuthenticator.authorize_url = f"{issuer_base}/api/oidc/authorization"
c.GenericOAuthenticator.token_url = f"{issuer_base}/api/oidc/token"
c.GenericOAuthenticator.userdata_url = f"{issuer_base}/api/oidc/userinfo"
c.GenericOAuthenticator.scope = ["openid", "profile", "email", "groups"]

# Map username from userinfo (preferred_username works well with Authelia)
c.GenericOAuthenticator.username_key = "preferred_username"
# Optionally pull groups from userinfo for admin mapping
c.GenericOAuthenticator.claims_username = "preferred_username"
c.GenericOAuthenticator.claims_groups = "groups"

# Users and admins (optional)
admin_users_env = os.getenv("JUPYTERHUB_ADMIN_USERS", "")
allowed_users_env = os.getenv("JUPYTERHUB_ALLOWED_USERS", "")
if admin_users_env:
    c.Authenticator.admin_users = {u.strip() for u in admin_users_env.split(",") if u.strip()}
if allowed_users_env:
    c.Authenticator.allowed_users = {u.strip() for u in allowed_users_env.split(",") if u.strip()}

# Spawner: per-user Docker containers
c.JupyterHub.spawner_class = DockerSpawner

# The single-user image to spawn (default from env in compose)
singleuser_image = os.getenv("JUPYTERHUB_SINGLEUSER_IMAGE", "jupyter/minimal-notebook:python-3.11")
c.DockerSpawner.image = singleuser_image

# Place user servers on the same Docker network as the stack
c.DockerSpawner.network_name = "app_net"

# Give each user a persistent home under a per-user volume
# (Docker will create volumes automatically if they don't exist.)
c.DockerSpawner.volumes = {
    "jupyterhub-user-{username}": "/home/jovyan"
}

# Optional resource limits
# c.DockerSpawner.mem_limit = "2G"
# c.DockerSpawner.cpu_limit = 1.0

# Environment inside single-user servers (add what you need)
c.Spawner.environment = {
    "JUPYTER_ENABLE_LAB": "yes"
}

# Ensure the proxy preserves client IP/host headers through Caddy
c.JupyterHub.trusted_alt_names = [f"jupyter.{BASE_DOMAIN}"]

# Optional: automatically create system users (not needed with DockerSpawner)
c.Authenticator.auto_login = False  # Users are redirected to Authelia when they click "Sign in"