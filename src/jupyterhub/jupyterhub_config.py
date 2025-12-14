import os
import sys
import ssl

# Force tornado to use SimpleAsyncHTTPClient which respects certifi
from tornado.httpclient import AsyncHTTPClient
AsyncHTTPClient.configure("tornado.simple_httpclient.SimpleAsyncHTTPClient")

# Base configuration
c = get_config()

# Network configuration
c.JupyterHub.bind_url = 'http://0.0.0.0:8000'
c.JupyterHub.hub_bind_url = 'http://0.0.0.0:8081'
c.JupyterHub.hub_connect_url = 'http://jupyterhub:8081'

# Use DockerSpawner to spawn notebook servers in containers
c.JupyterHub.spawner_class = 'dockerspawner.DockerSpawner'

# Docker image for single-user notebook servers
c.DockerSpawner.image = 'jupyter/minimal-notebook:latest'

# Connect containers to the same network as JupyterHub
c.DockerSpawner.network_name = 'datamancy_backend'

# Remove containers when they stop
c.DockerSpawner.remove = True

# Notebook directory inside the container
c.DockerSpawner.notebook_dir = '/home/jovyan/work'

# Default URL to JupyterLab
c.DockerSpawner.default_url = '/lab'

# Hub connection config for containers
c.DockerSpawner.hub_connect_url = 'http://jupyterhub:8081'

# Reasonable timeouts for container startup
c.DockerSpawner.http_timeout = 60
c.DockerSpawner.start_timeout = 120

# Format container names
c.DockerSpawner.name_template = 'jupyter-{username}-{servername}'

# Ensure compatible jupyterhub version in spawned containers
# Install the correct jupyterhub version to match the hub
c.DockerSpawner.cmd = ['sh', '-c', 'pip install --upgrade jupyterhub==5.4.2 && jupyterhub-singleuser']

# Authentication: RemoteUserAuthenticator for Authelia forward_auth
# This allows seamless single-layer SSO - user authenticates once with Authelia,
# and JupyterHub trusts the Remote-User header from the reverse proxy
c.JupyterHub.authenticator_class = 'remote_user_authenticator.RemoteUserAuthenticator'

# Trust the Remote-User header from Authelia (passed through Caddy)
c.RemoteUserAuthenticator.header_name = 'Remote-User'

# Allow all authenticated users (Authelia handles authorization)
c.Authenticator.allow_all = True

# Admin users from Remote-Groups header (Authelia passes 'admins' group)
c.Authenticator.admin_users = set()

# Enable group management from headers
# Note: RemoteUserAuthenticator doesn't natively support group headers,
# but Authelia's forward_auth ensures only authorized users reach JupyterHub

# Logging
c.JupyterHub.log_level = 'INFO'
c.Authenticator.enable_auth_state = False

# Cookie secret
c.JupyterHub.cookie_secret_file = '/srv/jupyterhub/jupyterhub_cookie_secret'

# Database
c.JupyterHub.db_url = 'sqlite:////srv/jupyterhub/jupyterhub.sqlite'

# Allow named servers
c.JupyterHub.allow_named_servers = True
c.JupyterHub.named_server_limit_per_user = 3
