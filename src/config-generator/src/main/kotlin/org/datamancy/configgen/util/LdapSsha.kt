package org.datamancy.configgen.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates an SSHA hash for LDAP: {SSHA}Base64(SHA1(password+salt) + salt)
 */
fun generateSshaHash(plainPassword: String): String {
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(plainPassword.toByteArray(Charsets.UTF_8))
    sha1.update(salt)
    val digest = sha1.digest()
    val combined = digest + salt
    val b64 = Base64.getEncoder().encodeToString(combined)
    return "{SSHA}$b64"
}
