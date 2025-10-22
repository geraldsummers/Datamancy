# JupyterHub configuration with OIDC (Authentik)
# NOTE: Requires oauthenticator and dockerspawner packages in the image.

import os
c = get_config()  # noqa

# Basic settings
c.JupyterHub.bind_url = 'http://0.0.0.0:8000'
c.JupyterHub.hub_ip = '0.0.0.0'

# Use DockerSpawner to spawn notebooks in containers
c.JupyterHub.spawner_class = 'dockerspawner.DockerSpawner'
c.DockerSpawner.image = 'jupyter/scipy-notebook:latest'  # TODO: abstract
c.DockerSpawner.network_name = 'app_net'
c.DockerSpawner.remove = True

# Use docker-socket-proxy instead of direct socket access
c.DockerSpawner.client_kwargs = {'base_url': 'tcp://docker-socket-proxy:2375'}

# Get base domain once for all configs
base_domain = os.environ.get('BASE_DOMAIN', 'lab.localhost')

# Admin users  # TODO: abstract
c.Authenticator.admin_users = {f'admin@{base_domain}'}
c.Authenticator.allow_all = True

# Allow named servers
c.JupyterHub.allow_named_servers = True

# OIDC via Authentik
c.JupyterHub.authenticator_class = 'oauthenticator.generic.GenericOAuthenticator'

# Generic OAuthenticator settings
c.GenericOAuthenticator.client_id = 'jupyterhub'
c.GenericOAuthenticator.client_secret = os.environ.get('OIDC_JUPYTERHUB_CLIENT_SECRET', 'jupyterhub_oidc_secret_change_me')
c.GenericOAuthenticator.oauth_callback_url = f'https://jupyter.{base_domain}/hub/oauth_callback'

# Browser-facing URL (external)
c.GenericOAuthenticator.authorize_url = f'https://id.{base_domain}/application/o/authorize/'
# Internal Docker network URLs (container-to-container)
c.GenericOAuthenticator.token_url = 'http://authentik-server:9000/application/o/token/'
c.GenericOAuthenticator.userdata_url = 'http://authentik-server:9000/application/o/userinfo/'

c.GenericOAuthenticator.scope = ['openid', 'profile', 'email', 'groups']
c.GenericOAuthenticator.username_key = 'preferred_username'
c.GenericOAuthenticator.basic_auth = True
