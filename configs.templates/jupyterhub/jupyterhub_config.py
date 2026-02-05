import os
import sys
import ssl

from tornado.httpclient import AsyncHTTPClient
AsyncHTTPClient.configure("tornado.simple_httpclient.SimpleAsyncHTTPClient")

c = get_config()

c.JupyterHub.bind_url = 'http://0.0.0.0:8000'
c.JupyterHub.hub_bind_url = 'http://0.0.0.0:8081'
c.JupyterHub.hub_connect_url = 'http://jupyterhub:8081'

c.JupyterHub.spawner_class = 'dockerspawner.DockerSpawner'

c.DockerSpawner.image = 'datamancy-jupyter-notebook:latest'

c.DockerSpawner.network_name = os.environ.get('DOCKER_NETWORK_NAME', 'datamancy-stack_litellm')

c.DockerSpawner.notebook_dir = '/home/jovyan/notebooks'

c.Spawner.default_url = '/lab'

c.DockerSpawner.remove = True

c.DockerSpawner.volumes = {
    'jupyterhub-user-{username}': '/home/jovyan'
}

c.Spawner.environment = {
    'LITELLM_API_KEY': os.environ.get('LITELLM_API_KEY', 'unused'),
    'OPENAI_API_BASE': 'http://litellm:4000/v1',
    'OPENAI_API_KEY': os.environ.get('LITELLM_API_KEY', 'unused'),
}

c.JupyterHub.authenticator_class = 'remote_user_authenticator.RemoteUserAuthenticator'

c.RemoteUserAuthenticator.header_name = 'Remote-User'

c.Authenticator.allow_all = True

c.Authenticator.admin_users = set()

c.JupyterHub.log_level = 'INFO'
c.Authenticator.enable_auth_state = False

c.JupyterHub.cookie_secret_file = '/srv/jupyterhub/jupyterhub_cookie_secret'

c.JupyterHub.db_url = 'sqlite:////srv/jupyterhub/jupyterhub.sqlite'

c.JupyterHub.allow_named_servers = True
c.JupyterHub.named_server_limit_per_user = 3
