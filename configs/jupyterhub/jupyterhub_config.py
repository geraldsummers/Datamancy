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
c.DockerSpawner.network_name = 'datamancy_stack'

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

# Authentication: GenericOAuthenticator for Authelia OIDC
c.JupyterHub.authenticator_class = 'oauthenticator.generic.GenericOAuthenticator'

# OAuth configuration for Authelia
c.GenericOAuthenticator.client_id = 'jupyterhub'
c.GenericOAuthenticator.client_secret = os.environ.get('JUPYTERHUB_OAUTH_SECRET', '')
c.GenericOAuthenticator.oauth_callback_url = 'https://jupyterhub.stack.local/hub/oauth_callback'

# Authelia OIDC endpoints
c.GenericOAuthenticator.authorize_url = 'https://auth.stack.local/api/oidc/authorization'
c.GenericOAuthenticator.token_url = 'https://auth.stack.local/api/oidc/token'
c.GenericOAuthenticator.userdata_url = 'https://auth.stack.local/api/oidc/userinfo'

# User info mapping
c.GenericOAuthenticator.username_claim = 'preferred_username'

# Allow all authenticated users
c.GenericOAuthenticator.allow_all = True

# Admin users from 'admins' group - using allowed_users list
c.GenericOAuthenticator.admin_users = set()

# OAuth scopes
c.GenericOAuthenticator.scope = ['openid', 'profile', 'email', 'groups']

# Disable TLS verification (running behind Caddy proxy in internal network)
# This is safe as all external TLS is terminated at Caddy
c.GenericOAuthenticator.tls_verify = False
c.GenericOAuthenticator.validate_server_cert = False

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
