#!/usr/bin/env kotlin

/**
 * Create Shadow Agent Account for User
 *
 * Creates a per-user shadow account ({username}-agent) in LDAP for agent-tool-server access.
 * Shadow accounts:
 * - Are read-only
 * - Inherit base permissions from parent user
 * - Enable full audit traceability
 * - Limit blast radius (compromise affects only one user)
 *
 * Usage:
 *   ./create-shadow-agent-account.main.kts <username> [--dry-run]
 *
 * Example:
 *   ./create-shadow-agent-account.main.kts alice
 *   ./create-shadow-agent-account.main.kts bob --dry-run
 *
 * Requirements:
 * - User must exist in LDAP (ou=users)
 * - User must NOT be in cn=admins group (admins don't get agent accounts)
 * - Environment variables: LDAP_ADMIN_PASSWORD
 */

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.system.exitProcess

// ===== Configuration =====

data class Config(
    val ldapHost: String = System.getenv("LDAP_HOST") ?: "ldap",
    val ldapPort: Int = (System.getenv("LDAP_PORT") ?: "389").toInt(),
    val baseDn: String = "dc=stack,dc=local",
    val adminDn: String = "cn=admin,dc=stack,dc=local",
    val adminPassword: String = System.getenv("LDAP_ADMIN_PASSWORD") ?: "",
    val agentsOu: String = "ou=agents",
    val usersOu: String = "ou=users",
    val groupsOu: String = "ou=groups"
)

// ===== SSHA Password Generation =====

fun generateSSHA(password: String): String {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)

    val md = MessageDigest.getInstance("SHA-1")
    md.update(password.toByteArray())
    md.update(salt)
    val hash = md.digest()

    val combined = hash + salt
    val encoded = Base64.getEncoder().encodeToString(combined)

    return "{SSHA}$encoded"
}

fun generateSecurePassword(length: Int = 32): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
    val random = SecureRandom()
    return (1..length)
        .map { chars[random.nextInt(chars.length)] }
        .joinToString("")
}

// ===== LDAP Operations =====

fun checkLdapAvailable(config: Config): Boolean {
    return try {
        val result = ProcessBuilder(
            "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
            "-D", config.adminDn, "-w", config.adminPassword,
            "-b", config.baseDn, "-s", "base", "(objectClass=*)"
        ).start().waitFor()
        result == 0
    } catch (e: Exception) {
        false
    }
}

fun checkUserExists(config: Config, username: String): Boolean {
    val result = ProcessBuilder(
        "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
        "-D", config.adminDn, "-w", config.adminPassword,
        "-b", "uid=$username,${config.usersOu},${config.baseDn}",
        "-s", "base", "(objectClass=inetOrgPerson)"
    ).start().waitFor()
    return result == 0
}

fun checkUserIsAdmin(config: Config, username: String): Boolean {
    val process = ProcessBuilder(
        "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
        "-D", config.adminDn, "-w", config.adminPassword,
        "-b", "cn=admins,${config.groupsOu},${config.baseDn}",
        "-s", "base", "(member=uid=$username,${config.usersOu},${config.baseDn})"
    ).start()

    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()

    return output.contains("member: uid=$username")
}

fun checkShadowAccountExists(config: Config, username: String): Boolean {
    val result = ProcessBuilder(
        "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
        "-D", config.adminDn, "-w", config.adminPassword,
        "-b", "uid=$username-agent,${config.agentsOu},${config.baseDn}",
        "-s", "base", "(objectClass=inetOrgPerson)"
    ).start().waitFor()
    return result == 0
}

fun getUserEmail(config: Config, username: String): String {
    val process = ProcessBuilder(
        "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
        "-D", config.adminDn, "-w", config.adminPassword,
        "-b", "uid=$username,${config.usersOu},${config.baseDn}",
        "-s", "base", "mail"
    ).start()

    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()

    val mailLine = output.lines().find { it.startsWith("mail:") }
    return mailLine?.substringAfter("mail:")?.trim() ?: "$username@stack.local"
}

fun getNextUidNumber(config: Config): Int {
    val process = ProcessBuilder(
        "ldapsearch", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
        "-D", config.adminDn, "-w", config.adminPassword,
        "-b", "${config.agentsOu},${config.baseDn}",
        "-s", "one", "uidNumber"
    ).start()

    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()

    val uidNumbers = output.lines()
        .filter { it.startsWith("uidNumber:") }
        .mapNotNull { it.substringAfter("uidNumber:").trim().toIntOrNull() }

    return if (uidNumbers.isEmpty()) 20000 else uidNumbers.maxOrNull()!! + 1
}

fun createShadowAccount(config: Config, username: String, dryRun: Boolean): Boolean {
    val shadowUsername = "$username-agent"
    val password = generateSecurePassword()
    val passwordHash = generateSSHA(password)
    val email = getUserEmail(config, username)
    val uidNumber = getNextUidNumber(config)
    val gidNumber = uidNumber // Each shadow account gets its own group

    val ldifContent = """
dn: uid=$shadowUsername,${config.agentsOu},${config.baseDn}
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: $shadowUsername
cn: Shadow Agent for $username
sn: Agent
givenName: $username
mail: $email
displayName: $shadowUsername (read-only agent)
uidNumber: $uidNumber
gidNumber: $gidNumber
homeDirectory: /home/$shadowUsername
loginShell: /usr/sbin/nologin
userPassword: $passwordHash
description: Shadow agent account for user $username (read-only database access)
""".trim()

    if (dryRun) {
        println("\n[DRY RUN] Would create LDIF:")
        println(ldifContent)
        println("\n[DRY RUN] Password (not committed): $password")
        return true
    }

    // Write LDIF to temp file
    val ldifFile = File.createTempFile("shadow-agent-", ".ldif")
    ldifFile.writeText(ldifContent)

    try {
        val result = ProcessBuilder(
            "ldapadd", "-x", "-H", "ldap://${config.ldapHost}:${config.ldapPort}",
            "-D", config.adminDn, "-w", config.adminPassword,
            "-f", ldifFile.absolutePath
        ).redirectErrorStream(true).start()

        val output = result.inputStream.bufferedReader().readText()
        val exitCode = result.waitFor()

        if (exitCode == 0) {
            println("\n✅ Shadow account created successfully!")
            println("   Username: $shadowUsername")
            println("   UID: $uidNumber")
            println("   Email: $email")
            println("\n🔐 Password (save securely, shown only once):")
            println("   $password")

            // Store password in secure location for agent-tool-server
            val secretsDir = File("/run/secrets/datamancy")
            if (secretsDir.exists() || secretsDir.mkdirs()) {
                val secretFile = File(secretsDir, "shadow-agent-$username.pwd")
                secretFile.writeText(password)
                secretFile.setReadable(false, false)
                secretFile.setReadable(true, true) // Owner only
                println("\n📁 Password stored: ${secretFile.absolutePath}")
            }

            return true
        } else {
            println("\n❌ Failed to create shadow account:")
            println(output)
            return false
        }
    } finally {
        ldifFile.delete()
    }
}

// ===== Main =====

fun main(args: Array<String>) {
    println("🔧 Datamancy Shadow Agent Account Creator")
    println("=========================================\n")

    if (args.isEmpty() || args[0] in listOf("-h", "--help")) {
        println("""
Usage: ./create-shadow-agent-account.main.kts <username> [--dry-run]

Creates a per-user shadow account for agent-tool-server access.

Arguments:
  username    Username to create shadow account for
  --dry-run   Preview changes without applying

Environment Variables:
  LDAP_ADMIN_PASSWORD    Admin password for LDAP (required)
  LDAP_HOST             LDAP host (default: ldap)
  LDAP_PORT             LDAP port (default: 389)

Examples:
  ./create-shadow-agent-account.main.kts alice
  ./create-shadow-agent-account.main.kts bob --dry-run

Security Notes:
  - Admin accounts (cn=admins group) are excluded
  - Shadow accounts are read-only
  - Passwords are 32-character random strings
  - Each shadow account has unique UID/GID (20000+)
        """.trim())
        exitProcess(0)
    }

    val username = args[0]
    val dryRun = args.contains("--dry-run")

    println("👤 User: $username")
    println("🏃 Mode: ${if (dryRun) "DRY RUN" else "LIVE"}")
    println()

    val config = Config()

    // Validation
    if (config.adminPassword.isEmpty()) {
        println("❌ ERROR: LDAP_ADMIN_PASSWORD environment variable not set")
        exitProcess(1)
    }

    print("🔍 Checking LDAP connectivity... ")
    if (!checkLdapAvailable(config)) {
        println("❌ FAILED")
        println("   LDAP server not available at ${config.ldapHost}:${config.ldapPort}")
        exitProcess(1)
    }
    println("✅ OK")

    print("🔍 Checking user exists... ")
    if (!checkUserExists(config, username)) {
        println("❌ FAILED")
        println("   User '$username' not found in LDAP")
        exitProcess(1)
    }
    println("✅ OK")

    print("🔍 Checking user is not admin... ")
    if (checkUserIsAdmin(config, username)) {
        println("❌ BLOCKED")
        println("   User '$username' is in cn=admins group")
        println("   Admin accounts cannot have shadow agent accounts")
        exitProcess(1)
    }
    println("✅ OK")

    print("🔍 Checking shadow account doesn't exist... ")
    if (checkShadowAccountExists(config, username)) {
        println("⚠️  ALREADY EXISTS")
        println("   Shadow account for '$username' already exists")
        println("   Use delete-shadow-agent-account.main.kts to remove it first")
        exitProcess(1)
    }
    println("✅ OK")

    println("\n📝 Creating shadow account...")
    val success = createShadowAccount(config, username, dryRun)

    if (!dryRun && success) {
        println("\n✅ SUCCESS!")
        println("\nNext steps:")
        println("  1. Create database roles (PostgreSQL/MariaDB)")
        println("  2. Grant read-only access to agent_observer views")
        println("  3. Test agent-tool-server access")
    }

    exitProcess(if (success) 0 else 1)
}

main(args)
