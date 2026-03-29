package org.datamancy.txgateway.services

import org.slf4j.LoggerFactory
import java.io.File

private data class CredentialStoreSnapshot(
    val lastModifiedMs: Long,
    val values: Map<String, String>
)

class CredentialResolver(
    private val ldapService: LdapService,
    private val envProvider: (String) -> String? = { key -> System.getenv(key) },
    private val credentialStoreFile: File? = resolveDefaultCredentialStoreFile(),
    private val defaultHyperliquidExecutionMode: String = if (
        parseBooleanFlag(
            raw = System.getenv("HYPERLIQUID_MAINNET"),
            defaultValue = false
        )
    ) {
        "mainnet_live"
    } else {
        "testnet_live"
    }
) {
    private val logger = LoggerFactory.getLogger(CredentialResolver::class.java)
    private val credentialStoreLock = Any()
    @Volatile
    private var credentialStoreSnapshot: CredentialStoreSnapshot? = null

    fun resolveHyperliquidCredential(
        username: String,
        providedCredential: String?,
        executionMode: String? = null
    ): String? {
        sanitizeCredential(providedCredential)?.let { return it }

        resolveKeyRef(ldapService.getHyperliquidKeyRef(username))
            ?.let { composeHyperliquidCredential(it, ldapService.getUserInfo(username)?.evmAddress) }
            ?.let { return it }

        val normalizedMode = (executionMode ?: defaultHyperliquidExecutionMode)
            .trim()
            .lowercase()
            .takeIf { it.isNotEmpty() }
            ?: return null

        val fallbackRefs = when (normalizedMode) {
            "testnet_live" -> listOf(
                HyperliquidCredentialRef(
                    keyRefs = listOf("HYPERLIQUID_TESTNET_KEY"),
                    addressRefs = listOf("HYPERLIQUID_TESTNET_ACCOUNT_ADDRESS", "HYPERLIQUID_TESTNET_ADDRESS")
                ),
                HyperliquidCredentialRef(
                    keyRefs = listOf("TRADING_E2E_HYPERLIQUID_KEY"),
                    addressRefs = listOf("TRADING_E2E_HYPERLIQUID_ACCOUNT_ADDRESS", "TRADING_E2E_HYPERLIQUID_ADDRESS")
                )
            )
            "mainnet_live" -> listOf(
                HyperliquidCredentialRef(
                    keyRefs = listOf("HYPERLIQUID_MAINNET_KEY"),
                    addressRefs = listOf("HYPERLIQUID_MAINNET_ACCOUNT_ADDRESS", "HYPERLIQUID_MAINNET_ADDRESS")
                )
            )
            else -> emptyList()
        }
        return fallbackRefs.firstNotNullOfOrNull(::resolveHyperliquidFallback)
    }

    fun resolveEvmCredential(username: String, providedCredential: String?): String? {
        sanitizeCredential(providedCredential)?.let { return it }
        return resolveKeyRef(ldapService.getEvmKeyRef(username))
    }

    fun resolveKeyRef(keyRef: String?): String? {
        val normalizedKeyRef = keyRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        sanitizeCredential(envProvider(normalizedKeyRef))?.let { return it }
        return sanitizeCredential(loadCredentialStore()[normalizedKeyRef])
    }

    private fun loadCredentialStore(): Map<String, String> {
        val file = credentialStoreFile ?: return emptyMap()
        if (!file.exists() || !file.isFile) {
            return emptyMap()
        }

        val lastModifiedMs = runCatching { file.lastModified() }.getOrElse { 0L }
        credentialStoreSnapshot
            ?.takeIf { it.lastModifiedMs == lastModifiedMs }
            ?.let { return it.values }

        synchronized(credentialStoreLock) {
            credentialStoreSnapshot
                ?.takeIf { it.lastModifiedMs == lastModifiedMs }
                ?.let { return it.values }

            val values = runCatching { parseCredentialStore(file) }
                .onFailure { error ->
                    logger.warn("Failed to load credential store {}: {}", file.absolutePath, error.message)
                }
                .getOrElse { emptyMap() }

            credentialStoreSnapshot = CredentialStoreSnapshot(
                lastModifiedMs = lastModifiedMs,
                values = values
            )
            return values
        }
    }

    private fun parseCredentialStore(file: File): Map<String, String> {
        val values = linkedMapOf<String, String>()
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachLine
            }

            val candidate = line.removePrefix("export ").trim()
            val separatorIndex = candidate.indexOf('=')
            if (separatorIndex <= 0) {
                return@forEachLine
            }

            val key = candidate.substring(0, separatorIndex).trim()
            if (key.isEmpty()) {
                return@forEachLine
            }

            val rawValue = candidate.substring(separatorIndex + 1).trim()
            val value = rawValue
                .removeSurrounding("\"")
                .removeSurrounding("'")
            values[key] = value
        }
        return values
    }

    private fun sanitizeCredential(raw: String?): String? {
        return raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolveHyperliquidFallback(ref: HyperliquidCredentialRef): String? {
        val key = ref.keyRefs.firstNotNullOfOrNull(::resolveKeyRef) ?: return null
        val address = ref.addressRefs.firstNotNullOfOrNull(::resolveKeyRef)
        return composeHyperliquidCredential(key, address)
    }

    private fun composeHyperliquidCredential(key: String, address: String?): String {
        if (":" in key) return key
        val normalizedAddress = sanitizeCredential(address) ?: return key
        return "$normalizedAddress:$key"
    }
}

private data class HyperliquidCredentialRef(
    val keyRefs: List<String>,
    val addressRefs: List<String>
)

private fun resolveDefaultCredentialStoreFile(
    envProvider: (String) -> String? = { key -> System.getenv(key) }
): File? {
    val explicitPath = envProvider("DATAMANCY_CREDENTIAL_STORE_FILE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (explicitPath != null) {
        return File(expandHome(explicitPath)).absoluteFile
    }

    val xdgConfigHome = envProvider("XDG_CONFIG_HOME")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val baseDir = if (xdgConfigHome != null) {
        File(expandHome(xdgConfigHome))
    } else {
        File(System.getProperty("user.home")).resolve(".config")
    }
    return baseDir.resolve("datamancy/credentials.env").absoluteFile
}

private fun expandHome(path: String): String {
    return if (path == "~") {
        System.getProperty("user.home")
    } else if (path.startsWith("~/")) {
        System.getProperty("user.home") + path.removePrefix("~")
    } else {
        path
    }
}
