package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class LdapConfig(
    val baseDn: String = "dc=stack,dc=local",
    val adminDn: String = "cn=admin,dc=stack,dc=local",
    val adminPasswordEnvKey: String = "LDAP_ADMIN_PASSWORD",
    val defaultUserPasswordEnvKey: String = "LDAP_USER_PASSWORD",
    val bootstrapUsers: List<LdapBootstrapUser> = emptyList()
)

@Serializable
data class LdapBootstrapUser(
    val uid: String,
    val cn: String,
    val sn: String,
    val mail: String,
    val passwordEnvKey: String? = null,
    val isAdmin: Boolean = false,
    val groups: List<String> = emptyList()
)
