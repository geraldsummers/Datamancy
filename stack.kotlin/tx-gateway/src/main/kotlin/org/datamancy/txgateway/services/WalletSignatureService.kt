package org.datamancy.txgateway.services

import org.slf4j.LoggerFactory
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.nio.charset.StandardCharsets

class WalletSignatureService {
    private val logger = LoggerFactory.getLogger(WalletSignatureService::class.java)

    fun verifyPersonalSignature(
        message: String,
        signatureHex: String,
        expectedAddress: String
    ): Boolean {
        val signatureData = parseSignature(signatureHex) ?: return false
        val recoveredAddress = runCatching {
            val publicKey = Sign.signedPrefixedMessageToKey(message.toByteArray(StandardCharsets.UTF_8), signatureData)
            "0x${Keys.getAddress(publicKey)}"
        }.onFailure {
            logger.warn("Wallet signature recovery failed: {}", it.message)
        }.getOrNull() ?: return false

        return normalizeAddress(recoveredAddress) == normalizeAddress(expectedAddress)
    }

    private fun parseSignature(signatureHex: String): Sign.SignatureData? {
        val normalized = signatureHex.trim().removePrefix("0x")
        val raw = runCatching { Numeric.hexStringToByteArray(normalized) }.getOrNull() ?: return null
        if (raw.size != 65) return null

        val r = raw.copyOfRange(0, 32)
        val s = raw.copyOfRange(32, 64)
        var v = raw[64].toInt()
        if (v < 27) {
            v += 27
        }
        if (v != 27 && v != 28) return null
        return Sign.SignatureData(v.toByte(), r, s)
    }

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.lowercase()
        } else {
            "0x${trimmed.lowercase()}"
        }
    }
}
