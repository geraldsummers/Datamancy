package org.datamancy.vmprov.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.util.io.pem.PemObject
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private val logger = KotlinLogging.logger {}

class SshKeyManager(private val keyDirectory: String) {

    init {
        val keyDir = File(keyDirectory)
        if (!keyDir.exists()) {
            keyDir.mkdirs()
            logger.info { "Created SSH key directory: $keyDirectory" }
        }
    }

    fun generateKeyPair(name: String, comment: String? = null): SshKeyPair {
        logger.info { "Generating SSH key pair: $name" }

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(4096)  // 4096-bit RSA key
        val keyPair = keyGen.generateKeyPair()

        // Save private key in OpenSSH format
        val privateKeyPath = "$keyDirectory/$name"
        val privateKeyPem = keyPairToPem(keyPair.private)
        File(privateKeyPath).writeText(privateKeyPem)

        // Set proper permissions (600)
        setPosixPermissions(privateKeyPath, "600")

        // Generate public key in OpenSSH format
        val publicKeyContent = rsaPublicKeyToOpenSsh(keyPair.public as RSAPublicKey, comment ?: name)
        val publicKeyPath = "$privateKeyPath.pub"
        File(publicKeyPath).writeText(publicKeyContent)

        logger.info { "SSH key pair generated: $name" }

        return SshKeyPair(
            name = name,
            publicKey = publicKeyContent,
            privateKeyPath = privateKeyPath,
            publicKeyPath = publicKeyPath
        )
    }

    fun listKeys(): List<SshKeyInfo> {
        val keyDir = File(keyDirectory)
        return keyDir.listFiles { file ->
            file.isFile && !file.name.endsWith(".pub")
        }?.map { privateKeyFile ->
            val name = privateKeyFile.name
            val publicKeyFile = File("${privateKeyFile.absolutePath}.pub")
            SshKeyInfo(
                name = name,
                publicKeyPath = if (publicKeyFile.exists()) publicKeyFile.absolutePath else null,
                privateKeyPath = privateKeyFile.absolutePath
            )
        } ?: emptyList()
    }

    fun getPublicKey(name: String): String? {
        val publicKeyPath = "$keyDirectory/$name.pub"
        val file = File(publicKeyPath)
        return if (file.exists()) {
            file.readText().trim()
        } else {
            null
        }
    }

    fun getPrivateKey(name: String): String? {
        val privateKeyPath = "$keyDirectory/$name"
        val file = File(privateKeyPath)
        return if (file.exists()) {
            file.readText()
        } else {
            null
        }
    }

    fun deleteKey(name: String): Boolean {
        val privateKeyFile = File("$keyDirectory/$name")
        val publicKeyFile = File("$keyDirectory/$name.pub")

        var deleted = false
        if (privateKeyFile.exists()) {
            privateKeyFile.delete()
            deleted = true
            logger.info { "Deleted private key: $name" }
        }
        if (publicKeyFile.exists()) {
            publicKeyFile.delete()
            logger.info { "Deleted public key: $name.pub" }
        }

        return deleted
    }

    private fun keyPairToPem(privateKey: java.security.PrivateKey): String {
        val stringWriter = StringWriter()
        JcaPEMWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(privateKey)
        }
        return stringWriter.toString()
    }

    private fun rsaPublicKeyToOpenSsh(publicKey: RSAPublicKey, comment: String): String {
        // OpenSSH public key format: "ssh-rsa <base64> <comment>"
        val exponent = publicKey.publicExponent.toByteArray()
        val modulus = publicKey.modulus.toByteArray()

        // Build SSH public key blob
        val keyType = "ssh-rsa"
        val keyTypeBytes = keyType.toByteArray()

        fun Int.toBytesBigEndian(): ByteArray {
            return byteArrayOf(
                (this shr 24).toByte(),
                (this shr 16).toByte(),
                (this shr 8).toByte(),
                this.toByte()
            )
        }

        fun ByteArray.withLength(): ByteArray {
            return this.size.toBytesBigEndian() + this
        }

        val blob = keyTypeBytes.withLength() +
                   exponent.withLength() +
                   modulus.withLength()

        val base64 = Base64.getEncoder().encodeToString(blob)
        return "$keyType $base64 $comment"
    }

    private fun setPosixPermissions(path: String, permissions: String) {
        try {
            val p = Paths.get(path)
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                // Skip on Windows
                return
            }

            // Use Java NIO for setting permissions
            val perms = when (permissions) {
                "600" -> setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                )
                "644" -> setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ
                )
                else -> emptySet()
            }

            if (perms.isNotEmpty()) {
                Files.setPosixFilePermissions(p, perms)
            }
        } catch (e: Exception) {
            logger.warn { "Failed to set POSIX permissions: ${e.message}" }
        }
    }
}

data class SshKeyPair(
    val name: String,
    val publicKey: String,
    val privateKeyPath: String,
    val publicKeyPath: String
)

data class SshKeyInfo(
    val name: String,
    val publicKeyPath: String?,
    val privateKeyPath: String
)
