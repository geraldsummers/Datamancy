#!/usr/bin/env kotlin

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Credential Utilities - Secure password generation and .env manipulation
 */

data class CredentialSpec(
    val name: String,
    val length: Int = 32,
    val charset: CharsetType = CharsetType.ALPHANUMERIC_SPECIAL
)

enum class CharsetType(val chars: String) {
    ALPHANUMERIC("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
    ALPHANUMERIC_SPECIAL("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+"),
    BASE64_URL_SAFE("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"),
    HEX("0123456789abcdef")
}

fun generateSecurePassword(length: Int = 32, charset: CharsetType = CharsetType.ALPHANUMERIC_SPECIAL): String {
    if (length < 16) {
        throw IllegalArgumentException("Password length must be at least 16 characters")
    }

    val random = SecureRandom()
    val chars = charset.chars
    return (1..length)
        .map { chars[random.nextInt(chars.length)] }
        .joinToString("")
}

fun generateBase64Secret(bytes: Int = 64): String {
    val random = SecureRandom()
    val secretBytes = ByteArray(bytes)
    random.nextBytes(secretBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
}

fun updateEnvVariable(envFile: File, key: String, newValue: String, dryRun: Boolean = false): Boolean {
    if (!envFile.exists()) {
        throw IllegalStateException("Environment file not found: ${envFile.absolutePath}")
    }

    val lines = envFile.readLines().toMutableList()
    var updated = false
    var lineIndex = -1

    for (i in lines.indices) {
        val line = lines[i].trim()
        if (line.startsWith("$key=") || line.startsWith("$key ")) {
            lineIndex = i
            break
        }
    }

    if (lineIndex != -1) {
        lines[lineIndex] = "$key=$newValue"
        updated = true
    } else {
        // Key not found, append to end
        lines.add("$key=$newValue")
        updated = true
    }

    if (updated && !dryRun) {
        envFile.writeText(lines.joinToString("\n") + "\n")
    }

    return updated
}

fun readEnvVariable(envFile: File, key: String): String? {
    if (!envFile.exists()) {
        return null
    }

    return envFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") || it.startsWith("$key ") }
        ?.substringAfter("=")
        ?.trim()
}

fun validatePasswordStrength(password: String, minLength: Int = 32): Boolean {
    if (password.length < minLength) {
        return false
    }

    // Check entropy (at least 3 character types)
    val hasUpper = password.any { it.isUpperCase() }
    val hasLower = password.any { it.isLowerCase() }
    val hasDigit = password.any { it.isDigit() }
    val hasSpecial = password.any { !it.isLetterOrDigit() }

    val characterTypes = listOf(hasUpper, hasLower, hasDigit, hasSpecial).count { it }
    return characterTypes >= 3
}

// Generate credentials based on type
fun generateCredential(spec: CredentialSpec): String {
    return when (spec.charset) {
        CharsetType.BASE64_URL_SAFE -> generateBase64Secret(spec.length)
        else -> generateSecurePassword(spec.length, spec.charset)
    }
}

// Execute if run directly
if (args.contains("--execute")) {
    when {
        args.contains("--generate") -> {
            val length = args.getOrNull(args.indexOf("--length") + 1)?.toIntOrNull() ?: 32
            val type = args.getOrNull(args.indexOf("--type") + 1) ?: "alphanumeric_special"

            val charset = when (type.lowercase()) {
                "alphanumeric" -> CharsetType.ALPHANUMERIC
                "base64" -> CharsetType.BASE64_URL_SAFE
                "hex" -> CharsetType.HEX
                else -> CharsetType.ALPHANUMERIC_SPECIAL
            }

            val password = generateSecurePassword(length, charset)
            println(password)
        }

        args.contains("--update") -> {
            val envPath = args.getOrNull(args.indexOf("--env") + 1) ?: ".env"
            val key = args.getOrNull(args.indexOf("--key") + 1)
            val value = args.getOrNull(args.indexOf("--value") + 1)
            val dryRun = args.contains("--dry-run")

            if (key == null || value == null) {
                System.err.println("❌ Usage: --update --env <file> --key <KEY> --value <VALUE>")
                kotlin.system.exitProcess(1)
            }

            try {
                val updated = updateEnvVariable(File(envPath), key, value, dryRun)
                if (updated) {
                    println("✅ Updated $key in $envPath")
                } else {
                    println("⚠️  No changes made")
                }
            } catch (e: Exception) {
                System.err.println("❌ Update failed: ${e.message}")
                kotlin.system.exitProcess(1)
            }
        }

        else -> {
            println("Usage:")
            println("  --generate --length <n> --type <alphanumeric|alphanumeric_special|base64|hex>")
            println("  --update --env <file> --key <KEY> --value <VALUE> [--dry-run]")
        }
    }
}
