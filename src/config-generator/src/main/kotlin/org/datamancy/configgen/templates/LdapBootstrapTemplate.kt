package org.datamancy.configgen.templates

import org.datamancy.configgen.model.LdapConfig
import org.datamancy.configgen.model.StackConfig
import org.datamancy.configgen.secrets.SecretsProvider
import org.datamancy.configgen.util.generateSshaHash

object LdapBootstrapTemplate {
    fun render(stack: StackConfig, secrets: SecretsProvider): String {
        val cfg: LdapConfig = stack.services.ldap
        val sb = StringBuilder()

        // Admin password
        val adminPw = secrets.getRequired(cfg.adminPasswordEnvKey)
        val adminHash = generateSshaHash(adminPw)
        sb.append("dn: ${cfg.adminDn}\n")
            .append("objectClass: simpleSecurityObject\n")
            .append("objectClass: organizationalRole\n")
            .append("cn: admin\n")
            .append("userPassword: ${adminHash}\n\n")

        // People and groups OUs
        sb.append("dn: ou=people,${cfg.baseDn}\n")
            .append("objectClass: organizationalUnit\n")
            .append("ou: people\n\n")
        sb.append("dn: ou=groups,${cfg.baseDn}\n")
            .append("objectClass: organizationalUnit\n")
            .append("ou: groups\n\n")

        val defaultUserPw = secrets.getOptional(cfg.defaultUserPasswordEnvKey) ?: adminPw

        for (u in cfg.bootstrapUsers) {
            val pw = u.passwordEnvKey?.let { secrets.getRequired(it) } ?: defaultUserPw
            val hash = generateSshaHash(pw)
            val userDn = "uid=${u.uid},ou=people,${cfg.baseDn}"
            sb.append("dn: ${userDn}\n")
                .append("objectClass: inetOrgPerson\n")
                .append("objectClass: posixAccount\n")
                .append("cn: ${u.cn}\n")
                .append("sn: ${u.sn}\n")
                .append("mail: ${u.mail}\n")
                .append("uid: ${u.uid}\n")
                .append("uidNumber: 10000\n")
                .append("gidNumber: 10000\n")
                .append("homeDirectory: /home/${u.uid}\n")
                .append("userPassword: ${hash}\n\n")

            // group memberships
            for (g in u.groups) {
                val groupDn = "cn=${g},ou=groups,${cfg.baseDn}"
                sb.append("dn: ${groupDn}\n")
                    .append("objectClass: groupOfNames\n")
                    .append("cn: ${g}\n")
                    .append("member: ${userDn}\n\n")
            }
        }

        return sb.toString()
    }
}
