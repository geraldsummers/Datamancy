package org.datamancy.configgen.secrets

interface SecretsProvider {
    fun getRequired(key: String): String
    fun getOptional(key: String, default: String? = null): String?
}

class EnvSecretsProvider : SecretsProvider {
    override fun getRequired(key: String): String =
        System.getenv(key) ?: error("Missing required secret: $key")

    override fun getOptional(key: String, default: String?): String? =
        System.getenv(key) ?: default
}
