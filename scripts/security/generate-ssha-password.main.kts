#!/usr/bin/env kotlin

/**
 * LDAP SSHA Password Hash Generator
 *
 * Generates SSHA (Salted SHA-1) password hashes for LDAP userPassword attributes.
 * This is the format required by OpenLDAP for secure password storage.
 *
 * Usage:
 *   kotlin scripts/security/generate-ssha-password.main.kts <password>
 *
 * Example:
 *   kotlin scripts/security/generate-ssha-password.main.kts "MySecurePassword123"
 *
 * Output format: {SSHA}base64encodedHash
 */

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

if (args.isEmpty()) {
    System.err.println("ERROR: Password argument required")
    System.err.println("Usage: kotlin generate-ssha-password.main.kts <password>")
    kotlin.system.exitProcess(1)
}

val password = args[0]

/**
 * Generate SSHA hash from plaintext password
 *
 * SSHA format: {SSHA}base64(sha1(password + salt) + salt)
 * - Uses 8 bytes of random salt
 * - Salted SHA-1 prevents rainbow table attacks
 * - Each generated hash is unique even for same password
 */
fun generateSSHA(plaintext: String): String {
    // Generate 8 random bytes for salt
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)

    // Compute SHA-1 of (password + salt)
    val md = MessageDigest.getInstance("SHA-1")
    md.update(plaintext.toByteArray(Charsets.UTF_8))
    md.update(salt)
    val digest = md.digest()

    // Concatenate digest + salt
    val hashWithSalt = digest + salt

    // Encode as base64 and prefix with {SSHA}
    val base64Hash = Base64.getEncoder().encodeToString(hashWithSalt)
    return "{SSHA}$base64Hash"
}

// Generate and print the hash
val sshaHash = generateSSHA(password)
println(sshaHash)
