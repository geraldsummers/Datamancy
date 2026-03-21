#!/usr/bin/env kotlin

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun usage(): Nothing {
    System.err.println("Usage: generate-ldap-bootstrap.main.kts [output-file]")
    kotlin.system.exitProcess(1)
}

fun detectRoot(): File {
    var current = File(".").canonicalFile
    while (true) {
        if (current.resolve("stack.config").isDirectory) return current
        current = current.parentFile ?: return File(".").canonicalFile
    }
}

fun parseEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }
        .toMap()
}

fun envValue(name: String, fileEnv: Map<String, String>): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileEnv[name]?.takeIf { it.isNotBlank() }

fun requireEnv(name: String, fileEnv: Map<String, String>): String = envValue(name, fileEnv)
    ?: error("Missing required setting: $name")

fun configValue(name: String, fileEnv: Map<String, String>, defaultValue: String): String =
    envValue(name, fileEnv)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultValue

fun csvToLdifValues(attributeName: String, raw: String): String =
    raw.split(',', ';', '|', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n") { "$attributeName: $it" }

fun ssha(password: String): String {
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(password.toByteArray(StandardCharsets.UTF_8))
    digest.update(salt)
    val combined = digest.digest() + salt
    return "{SSHA}" + Base64.getEncoder().encodeToString(combined)
}

val root = detectRoot()
val fileEnv = parseEnvFile(root.resolve(".env"))
val template = root.resolve("stack.config/ldap/bootstrap_ldap.ldif.template")
require(template.exists()) { "Missing template: ${template.path}" }
val output = when (args.size) {
    0 -> root.resolve("stack.config/ldap/bootstrap_ldap.ldif")
    1 -> File(args[0]).let { if (it.isAbsolute) it else root.resolve(args[0]) }
    else -> usage()
}

val ldapBaseDn = requireEnv("LDAP_BASE_DN", fileEnv)
val stackAdminUser = requireEnv("STACK_ADMIN_USER", fileEnv)
val stackAdminEmail = envValue("STACK_ADMIN_EMAIL", fileEnv) ?: "$stackAdminUser@datamancy.net"
val stackAdminPassword = requireEnv("STACK_ADMIN_PASSWORD", fileEnv)
val ldapDefaultAllowedChains = configValue("LDAP_DEFAULT_ALLOWED_CHAINS", fileEnv, "base,arbitrum,optimism")
val ldapDefaultAllowedExchanges = configValue(
    "LDAP_DEFAULT_ALLOWED_EXCHANGES",
    fileEnv,
    "swyftx,binance,bybit,coinbase,dydx,hyperliquid,aster"
)
val ldapDefaultAllowedTradingModes = configValue(
    "LDAP_DEFAULT_ALLOWED_TRADING_MODES",
    fileEnv,
    "backtest,forward_paper,testnet_live"
)
val ldapDefaultMaxTxPerHour = configValue("LDAP_DEFAULT_MAX_TX_PER_HOUR", fileEnv, "240")
val ldapDefaultMaxTxValueUsd = configValue("LDAP_DEFAULT_MAX_TX_VALUE_USD", fileEnv, "25000")
val rendered = template.readText()
    .replace("{{GENERATION_TIMESTAMP}}", java.time.Instant.now().toString())
    .replace("{{LDAP_BASE_DN}}", ldapBaseDn)
    .replace("{{STACK_ADMIN_USER}}", stackAdminUser)
    .replace("{{STACK_ADMIN_EMAIL}}", stackAdminEmail)
    .replace("{{ADMIN_SSHA_PASSWORD}}", ssha(stackAdminPassword))
    .replace("{{LDAP_DEFAULT_ALLOWED_CHAINS_LDIF}}", csvToLdifValues("allowedChains", ldapDefaultAllowedChains))
    .replace("{{LDAP_DEFAULT_ALLOWED_EXCHANGES_LDIF}}", csvToLdifValues("allowedExchanges", ldapDefaultAllowedExchanges))
    .replace("{{LDAP_DEFAULT_ALLOWED_TRADING_MODES_LDIF}}", csvToLdifValues("allowedTradingModes", ldapDefaultAllowedTradingModes))
    .replace("{{LDAP_DEFAULT_MAX_TX_PER_HOUR_VALUE}}", ldapDefaultMaxTxPerHour)
    .replace("{{LDAP_DEFAULT_MAX_TX_VALUE_USD_VALUE}}", ldapDefaultMaxTxValueUsd)

output.parentFile.mkdirs()
output.writeText(rendered)
println("Wrote LDAP bootstrap to ${output.path}")
