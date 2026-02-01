"""LDAP authentication provider for Home Assistant.

This provider allows authentication against an LDAP server,
enabling command-line tools and apps to use LDAP credentials.
"""
import os
import logging
from typing import Any, Dict, Optional, cast

import voluptuous as vol

from homeassistant.auth import AUTH_PROVIDERS
from homeassistant.auth.models import Credentials, UserMeta
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError

try:
    import ldap3
    from ldap3 import Server, Connection, ALL
    LDAP_AVAILABLE = True
except ImportError:
    LDAP_AVAILABLE = False

_LOGGER = logging.getLogger(__name__)

CONF_TYPE = "type"
CONF_LDAP_HOST = "ldap_host"
CONF_LDAP_PORT = "ldap_port"
CONF_LDAP_BASE_DN = "ldap_base_dn"
CONF_LDAP_BIND_DN = "ldap_bind_dn"
CONF_LDAP_BIND_PASSWORD = "ldap_bind_password"
CONF_LDAP_USER_FILTER = "ldap_user_filter"

CONFIG_SCHEMA = vol.Schema(
    {
        vol.Optional(CONF_LDAP_HOST, default=os.getenv("LDAP_HOST", "ldap")): str,
        vol.Optional(CONF_LDAP_PORT, default=int(os.getenv("LDAP_PORT", "389"))): int,
        vol.Optional(CONF_LDAP_BASE_DN, default=os.getenv("LDAP_BASE_DN", "{{LDAP_BASE_DN}}")): str,
        vol.Optional(CONF_LDAP_BIND_DN, default=os.getenv("LDAP_BIND_DN", "cn=admin,{{LDAP_BASE_DN}}")): str,
        vol.Optional(CONF_LDAP_BIND_PASSWORD, default=os.getenv("LDAP_BIND_PASSWORD", "")): str,
        vol.Optional(CONF_LDAP_USER_FILTER, default=os.getenv("LDAP_USER_FILTER", "(uid={username})")): str,
    },
    extra=vol.PREVENT_EXTRA,
)


@AUTH_PROVIDERS.register("ldap")
class LDAPAuthProvider:
    """LDAP authentication provider."""

    DEFAULT_TITLE = "LDAP Authentication"

    def __init__(
        self,
        hass,
        store,
        config: Dict[str, Any],
    ) -> None:
        """Initialize the LDAP auth provider."""
        self.hass = hass
        self.store = store
        self.config = config
        
        if not LDAP_AVAILABLE:
            _LOGGER.error("ldap3 library not available. Install with: pip install ldap3")
            raise HomeAssistantError("ldap3 library not available")

        self.ldap_host = config.get(CONF_LDAP_HOST)
        self.ldap_port = config.get(CONF_LDAP_PORT)
        self.ldap_base_dn = config.get(CONF_LDAP_BASE_DN)
        self.ldap_bind_dn = config.get(CONF_LDAP_BIND_DN)
        self.ldap_bind_password = config.get(CONF_LDAP_BIND_PASSWORD)
        self.ldap_user_filter = config.get(CONF_LDAP_USER_FILTER)

        _LOGGER.info(
            "LDAP auth provider initialized: host=%s, port=%s, base_dn=%s",
            self.ldap_host,
            self.ldap_port,
            self.ldap_base_dn,
        )

    async def async_login_flow(self, context: Optional[Dict[str, Any]]) -> Any:
        """Return a flow to login."""
        from homeassistant.auth.login_flow import LoginFlow

        return LDAPLoginFlow(self)

    async def async_get_or_create_credentials(
        self, flow_result: Dict[str, str]
    ) -> Credentials:
        """Get credentials based on the flow result."""
        username = flow_result["username"]

        for credential in await self.async_credentials():
            if credential.data["username"] == username:
                return credential

        # Create new credentials
        return self.async_create_credentials({"username": username})

    @callback
    def async_create_credentials(self, data: Dict[str, str]) -> Credentials:
        """Create credentials."""
        return Credentials(
            id=data["username"],
            auth_provider_type=self.type,
            auth_provider_id=None,
            data=data,
            is_new=False,
        )

    async def async_user_meta_for_credentials(
        self, credentials: Credentials
    ) -> UserMeta:
        """Return extra user metadata for credentials."""
        username = credentials.data["username"]
        return UserMeta(name=username, is_active=True)

    async def async_validate_login(self, username: str, password: str) -> bool:
        """Validate a username and password."""
        try:
            # Create LDAP server connection
            server = Server(
                f"{self.ldap_host}:{self.ldap_port}",
                get_info=ALL,
                connect_timeout=10,
            )

            # First, bind as admin to search for user
            conn = Connection(
                server,
                user=self.ldap_bind_dn,
                password=self.ldap_bind_password,
                auto_bind=True,
            )

            # Search for user
            search_filter = self.ldap_user_filter.format(username=username)
            conn.search(
                search_base=self.ldap_base_dn,
                search_filter=search_filter,
                attributes=["cn", "uid", "mail"],
            )

            if not conn.entries:
                _LOGGER.warning("User not found in LDAP: %s", username)
                return False

            user_dn = conn.entries[0].entry_dn
            conn.unbind()

            # Now try to bind as the user to verify password
            user_conn = Connection(
                server,
                user=user_dn,
                password=password,
                auto_bind=True,
            )

            if user_conn.bound:
                _LOGGER.info("LDAP authentication successful for user: %s", username)
                user_conn.unbind()
                return True
            else:
                _LOGGER.warning("LDAP bind failed for user: %s", username)
                return False

        except Exception as err:
            _LOGGER.error("LDAP authentication error for user %s: %s", username, err)
            return False

    @property
    def type(self) -> str:
        """Return the type of the auth provider."""
        return "ldap"

    @property
    def support_mfa(self) -> bool:
        """Return whether MFA is supported."""
        return False


class LDAPLoginFlow:
    """Handle LDAP login flow."""

    def __init__(self, auth_provider: LDAPAuthProvider) -> None:
        """Initialize the login flow."""
        self.auth_provider = auth_provider

    async def async_step_init(
        self, user_input: Optional[Dict[str, str]] = None
    ) -> Dict[str, Any]:
        """Handle the step of the form."""
        errors = {}

        if user_input is not None:
            username = user_input["username"]
            password = user_input["password"]

            if await self.auth_provider.async_validate_login(username, password):
                return await self.async_finish(username)

            errors["base"] = "invalid_auth"

        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Required("username"): str,
                    vol.Required("password"): str,
                }
            ),
            errors=errors,
        )

    async def async_finish(self, username: str) -> Dict[str, Any]:
        """Finish the login flow."""
        return {
            "result": "success",
            "username": username,
        }
