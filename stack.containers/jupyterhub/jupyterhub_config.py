import os
import sys
import ssl
import re

from jupyterhub.handlers import BaseHandler
from remote_user_authenticator import RemoteUserAuthenticator
from tornado import web
from traitlets import Set, Unicode

from tornado.httpclient import AsyncHTTPClient
AsyncHTTPClient.configure("tornado.simple_httpclient.SimpleAsyncHTTPClient")

c = get_config()


class DatamancyRemoteUserLoginHandler(BaseHandler):
    async def get(self):
        auth_model = await self.authenticator.get_authenticated_user(self, None)
        if auth_model is None:
            raise web.HTTPError(401)

        user = await self.auth_to_user(auth_model)
        self.set_login_cookie(user)
        self.redirect(self.get_next_url(user))


class DatamancyRemoteUserAuthenticator(RemoteUserAuthenticator):
    groups_header_name = Unicode(
        default_value='Remote-Groups',
        config=True,
        help="HTTP header that carries LDAP group membership."
    )
    allowed_remote_groups = Set(
        config=True,
        help="Remote groups allowed to access JupyterHub."
    )

    def get_handlers(self, app):
        return [
            (r'/login', DatamancyRemoteUserLoginHandler),
        ]

    async def authenticate(self, handler, data=None):
        remote_user = handler.request.headers.get(self.header_name, '').strip()
        if not remote_user:
            return None

        raw_groups = handler.request.headers.get(self.groups_header_name, '')
        groups = sorted({
            group.strip().lower()
            for group in re.split(r'[,;|\\s]+', raw_groups)
            if group.strip()
        })

        return {
            'name': remote_user,
            'groups': groups,
        }

    def check_allowed(self, username, authentication=None):
        if username in self.allowed_users:
            return True

        configured_groups = {group.strip().lower() for group in self.allowed_remote_groups if group.strip()}
        if not configured_groups:
            return True

        auth_groups = {
            group.strip().lower()
            for group in (authentication or {}).get('groups', [])
            if isinstance(group, str) and group.strip()
        }
        return not auth_groups.isdisjoint(configured_groups)

c.JupyterHub.bind_url = 'http://0.0.0.0:8000'
c.JupyterHub.hub_bind_url = 'http://0.0.0.0:8081'
c.JupyterHub.hub_connect_url = 'http://jupyterhub:8081'

c.JupyterHub.spawner_class = 'dockerspawner.DockerSpawner'

c.DockerSpawner.image = 'datamancy-jupyter-notebook:latest'

c.DockerSpawner.network_name = os.environ.get('DOCKER_NETWORK_NAME', 'datamancy_litellm')

c.DockerSpawner.cmd = 'start-singleuser.py'
c.DockerSpawner.extra_create_kwargs = {
    'user': 'root',
}

c.DockerSpawner.notebook_dir = '/home/jovyan/work'

c.Spawner.default_url = '/lab'

c.DockerSpawner.remove = True

c.Spawner.start_timeout = 300
c.Spawner.http_timeout = 300
c.DockerSpawner.pull_policy = 'ifnotpresent'

c.DockerSpawner.volumes = {
    'jupyterhub-user-{username}': '/home/jovyan/work'
}

c.Spawner.environment = {
    'LITELLM_API_KEY': os.environ.get('LITELLM_API_KEY', 'unused'),
    'OPENAI_API_BASE': 'http://litellm:4000/v1',
    'OPENAI_API_KEY': os.environ.get('LITELLM_API_KEY', 'unused'),
    # Ensure mounted home/work volume is writable for jovyan
    'CHOWN_HOME': 'yes',
    'CHOWN_HOME_OPTS': '-R',
    'CHOWN_EXTRA': '/home/jovyan/work',
    'CHOWN_EXTRA_OPTS': '-R',
}

c.JupyterHub.authenticator_class = DatamancyRemoteUserAuthenticator

c.DatamancyRemoteUserAuthenticator.header_name = 'Remote-User'
c.DatamancyRemoteUserAuthenticator.groups_header_name = 'Remote-Groups'
c.DatamancyRemoteUserAuthenticator.allowed_remote_groups = {
    group.strip()
    for group in re.split(r'[,;|\\s]+', os.environ.get('JUPYTERHUB_ALLOWED_REMOTE_GROUPS', 'users,admins'))
    if group.strip()
}

c.Authenticator.manage_groups = True
c.Authenticator.allow_all = False
c.Authenticator.any_allow_config = True

c.Authenticator.admin_users = set()

c.JupyterHub.log_level = 'INFO'
c.Authenticator.enable_auth_state = False

c.JupyterHub.cookie_secret_file = '/srv/jupyterhub/jupyterhub_cookie_secret'

c.JupyterHub.db_url = 'sqlite:////srv/jupyterhub/jupyterhub.sqlite'

c.JupyterHub.allow_named_servers = True
c.JupyterHub.named_server_limit_per_user = 3
