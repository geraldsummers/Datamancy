"""LDAP auth provider for Home Assistant.

This provider keeps Home Assistant identity aligned with the stack's LDAP
directory so user credentials remain centralized.
"""

from __future__ import annotations

from collections.abc import Mapping
import logging
import os
from typing import Any

import voluptuous as vol

from homeassistant.auth.auth_store import AuthStore
from homeassistant.auth.models import AuthFlowContext, AuthFlowResult, Credentials, UserMeta
from homeassistant.const import CONF_TYPE
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from . import AUTH_PROVIDER_SCHEMA, AUTH_PROVIDERS, AuthProvider, LoginFlow

# Home Assistant installs provider requirements automatically when this
# provider is configured in auth_providers.
REQUIREMENTS = ["ldap3==2.9.1"]

_LOGGER = logging.getLogger(__name__)

CONF_LDAP_HOST = "ldap_host"
CONF_LDAP_PORT = "ldap_port"
CONF_LDAP_BASE_DN = "ldap_base_dn"
CONF_LDAP_BIND_DN = "ldap_bind_dn"
CONF_LDAP_BIND_PASSWORD = "ldap_bind_password"
CONF_LDAP_USER_FILTER = "ldap_user_filter"

CONFIG_SCHEMA = AUTH_PROVIDER_SCHEMA.extend(
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


class InvalidAuthError(HomeAssistantError):
    """Raised when LDAP authentication fails."""


def _import_ldap3():
    """Import ldap3 lazily so requirements can be installed first."""
    try:
        import ldap3  # type: ignore
    except Exception as err:  # pragma: no cover - exercised in runtime containers
        raise HomeAssistantError("ldap3 dependency is unavailable") from err
    return ldap3


@AUTH_PROVIDERS.register("ldap")
class LDAPAuthProvider(AuthProvider):
    """Auth provider validating credentials against LDAP."""

    DEFAULT_TITLE = "LDAP Authentication"

    def __init__(
        self, hass: HomeAssistant, store: AuthStore, config: dict[str, Any]
    ) -> None:
        super().__init__(hass, store, config)
        self.ldap_host = config[CONF_LDAP_HOST]
        self.ldap_port = config[CONF_LDAP_PORT]
        self.ldap_base_dn = config[CONF_LDAP_BASE_DN]
        self.ldap_bind_dn = config[CONF_LDAP_BIND_DN]
        self.ldap_bind_password = config[CONF_LDAP_BIND_PASSWORD]
        self.ldap_user_filter = config[CONF_LDAP_USER_FILTER]
        self._user_meta: dict[str, dict[str, str]] = {}

    async def async_initialize(self) -> None:
        """Validate runtime dependencies early."""
        _import_ldap3()

    async def async_login_flow(
        self, context: AuthFlowContext | None
    ) -> LDAPLoginFlow:
        """Return a login flow for LDAP credentials."""
        return LDAPLoginFlow(self)

    async def async_validate_login(self, username: str, password: str) -> None:
        """Validate username/password against LDAP directory."""
        username = username.strip()
        if not username or not password:
            raise InvalidAuthError("Missing username or password")

        ldap3 = _import_ldap3()
        search_filter = self.ldap_user_filter.format(username=username)
        server = ldap3.Server(
            f"{self.ldap_host}:{self.ldap_port}",
            get_info=ldap3.NONE,
            connect_timeout=10,
        )

        bind_conn = None
        user_conn = None
        try:
            bind_conn = ldap3.Connection(
                server,
                user=self.ldap_bind_dn,
                password=self.ldap_bind_password,
                auto_bind=True,
                receive_timeout=10,
                raise_exceptions=True,
            )
            bind_conn.search(
                search_base=self.ldap_base_dn,
                search_filter=search_filter,
                attributes=["displayName", "cn", "uid", "mail"],
                size_limit=2,
            )

            if not bind_conn.entries:
                raise InvalidAuthError("User not found")

            user_entry = bind_conn.entries[0]
            user_dn = user_entry.entry_dn
            display_name = (
                user_entry.entry_attributes_as_dict.get("displayName", [None])[0]
                or user_entry.entry_attributes_as_dict.get("cn", [None])[0]
                or username
            )

            user_conn = ldap3.Connection(
                server,
                user=user_dn,
                password=password,
                auto_bind=True,
                receive_timeout=10,
                raise_exceptions=True,
            )

            self._user_meta[username] = {"name": str(display_name)}
        except (ValueError, KeyError, HomeAssistantError) as err:
            raise InvalidAuthError(str(err)) from err
        except Exception as err:
            _LOGGER.info("LDAP authentication failed for %s: %s", username, err)
            raise InvalidAuthError("Invalid LDAP credentials") from err
        finally:
            if bind_conn is not None:
                bind_conn.unbind()
            if user_conn is not None:
                user_conn.unbind()

    async def async_get_or_create_credentials(
        self, flow_result: Mapping[str, str]
    ) -> Credentials:
        """Return existing credentials or create them for first login."""
        username = flow_result["username"]
        for credential in await self.async_credentials():
            if credential.data["username"] == username:
                return credential

        return self.async_create_credentials({"username": username})

    async def async_user_meta_for_credentials(
        self, credentials: Credentials
    ) -> UserMeta:
        """Provide display metadata for newly-created users."""
        username = credentials.data["username"]
        name = self._user_meta.get(username, {}).get("name", username)
        return UserMeta(name=name, is_active=True)

    @property
    def type(self) -> str:
        """Provider type registry key."""
        return self.config.get(CONF_TYPE, "ldap")

    @property
    def support_mfa(self) -> bool:
        """LDAP provider does not implement MFA itself."""
        return False


class LDAPLoginFlow(LoginFlow[LDAPAuthProvider]):
    """Login flow for LDAP username/password."""

    async def async_step_init(
        self, user_input: dict[str, str] | None = None
    ) -> AuthFlowResult:
        """Handle the login form."""
        errors = {}

        if user_input is not None:
            username = user_input["username"].strip()
            password = user_input["password"]
            try:
                await self._auth_provider.async_validate_login(username, password)
            except InvalidAuthError:
                errors["base"] = "invalid_auth"
            except HomeAssistantError:
                errors["base"] = "unknown"

            if not errors:
                return await self.async_finish({"username": username})

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
