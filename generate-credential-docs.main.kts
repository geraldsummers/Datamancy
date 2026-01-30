#!/usr/bin/env kotlin

@file:DependsOn("org.yaml:snakeyaml:2.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.15.2")
@file:DependsOn("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

// Auto-generate credential documentation from schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

data class HashVariant(
    val algorithm: String,
    val variable: String,
    val used_in: List<String>
)

data class CredentialSpec(
    val name: String,
    val type: String,
    val description: String? = null,
    val source: String? = null,
    val default: String? = null,
    val used_by: List<String>? = null,
    val baked_in_configs: List<String>? = null,
    val hash_variants: List<HashVariant>? = null,
    val special_handling: String? = null
)

data class TemplateSubstitution(
    val source: String? = null,
    val transform: String
)

data class TemplateRule(
    val name: String,
    val path_pattern: String,
    val substitutions: Map<String, TemplateSubstitution>,
    val then_convert_remaining: Boolean? = false
)

data class CredentialsSchema(
    val credentials: List<CredentialSpec>,
    val template_rules: List<TemplateRule>
)

fun main(args: Array<String>) {
    val schemaFile = File("credentials.schema.yaml")
    if (!schemaFile.exists()) {
        println("ERROR: credentials.schema.yaml not found")
        return
    }

    val mapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
    val schema = mapper.readValue<CredentialsSchema>(schemaFile)

    val doc = buildString {
        appendLine("# Datamancy Credentials Reference")
        appendLine()
        appendLine("**Auto-generated from credentials.schema.yaml**")
        appendLine()
        appendLine("This document provides a complete reference of all credentials, secrets, and configuration values used in the Datamancy stack.")
        appendLine()
        appendLine("---")
        appendLine()

        // Table of Contents
        appendLine("## Table of Contents")
        appendLine()
        appendLine("- [Credential Types](#credential-types)")
        appendLine("- [Domain & Admin Configuration](#domain--admin-configuration)")
        appendLine("- [Core Infrastructure](#core-infrastructure)")
        appendLine("- [Database Passwords](#database-passwords)")
        appendLine("- [OAuth OIDC Secrets](#oauth-oidc-secrets)")
        appendLine("- [Application Secrets](#application-secrets)")
        appendLine("- [User-Provided Credentials](#user-provided-credentials)")
        appendLine("- [Configuration Values](#configuration-values)")
        appendLine("- [Template Processing Rules](#template-processing-rules)")
        appendLine()
        appendLine("---")
        appendLine()

        // Credential Types
        appendLine("## Credential Types")
        appendLine()
        appendLine("| Type | Description | Generation Method |")
        appendLine("|------|-------------|-------------------|")
        appendLine("| `hex_secret` | 64-character hexadecimal | `openssl rand -hex 32` |")
        appendLine("| `laravel_key` | Laravel APP_KEY format | `base64:...` from `openssl rand -base64 32` |")
        appendLine("| `rsa_key` | RSA 4096 private key | `openssl genrsa 4096` |")
        appendLine("| `oauth_secret` | OAuth client secret + argon2id hash | hex_secret + Authelia hash |")
        appendLine("| `user_provided` | User must provide | Empty by default |")
        appendLine("| `config_value` | Non-secret configuration | From datamancy.config.yaml |")
        appendLine()

        // Group credentials by category
        val categories = mapOf(
            "Domain & Admin Configuration" to schema.credentials.filter {
                it.source == "datamancy.config.yaml" || it.name.startsWith("DOMAIN") || it.name.startsWith("LDAP") || it.name.startsWith("STACK_ADMIN")
            },
            "Core Infrastructure" to schema.credentials.filter {
                it.name.contains("ADMIN_PASSWORD") && !it.name.contains("CLICKHOUSE") || it.name == "LDAP_ADMIN_PASSWORD"
            },
            "Database Passwords" to schema.credentials.filter {
                it.name.contains("DB_PASSWORD") || it.name.contains("DATABASE") || (it.name.contains("CLICKHOUSE") && it.name.contains("PASSWORD"))
            },
            "OAuth OIDC Secrets" to schema.credentials.filter {
                it.type == "oauth_secret"
            },
            "Application Secrets" to schema.credentials.filter {
                it.type == "hex_secret" && !it.name.contains("PASSWORD") && !it.name.contains("ADMIN") && !it.name.contains("DB") && !it.name.contains("OAUTH")
            } + schema.credentials.filter {
                it.type == "laravel_key" || (it.type == "hex_secret" && it.name.contains("SECRET"))
            },
            "User-Provided Credentials" to schema.credentials.filter {
                it.type == "user_provided"
            },
            "Configuration Values" to schema.credentials.filter {
                it.type == "config_value" && it.source == null
            }
        )

        categories.forEach { (category, creds) ->
            if (creds.isEmpty()) return@forEach

            appendLine("## ${category}")
            appendLine()

            creds.forEach { spec ->
                appendLine("### `${spec.name}`")
                appendLine()
                if (spec.description != null) {
                    appendLine("**Description:** ${spec.description}")
                    appendLine()
                }
                appendLine("**Type:** `${spec.type}`")
                appendLine()

                if (spec.source != null) {
                    appendLine("**Source:** `${spec.source}`")
                    appendLine()
                }

                if (spec.default != null) {
                    appendLine("**Default:** `${spec.default}`")
                    appendLine()
                }

                if (spec.used_by != null && spec.used_by.isNotEmpty()) {
                    appendLine("**Used by:**")
                    spec.used_by.forEach { service ->
                        appendLine("- $service")
                    }
                    appendLine()
                }

                if (spec.baked_in_configs != null && spec.baked_in_configs.isNotEmpty()) {
                    appendLine("**Baked into configs:**")
                    spec.baked_in_configs.forEach { config ->
                        appendLine("- `$config`")
                    }
                    appendLine()
                }

                if (spec.hash_variants != null && spec.hash_variants.isNotEmpty()) {
                    appendLine("**Hash Variants:**")
                    appendLine()
                    appendLine("| Variable | Algorithm | Used In |")
                    appendLine("|----------|-----------|---------|")
                    spec.hash_variants.forEach { variant ->
                        val usedIn = variant.used_in.joinToString(", ") { "`$it`" }
                        appendLine("| `${variant.variable}` | ${variant.algorithm} | $usedIn |")
                    }
                    appendLine()
                }

                if (spec.special_handling != null) {
                    appendLine("**Special Handling:** ${spec.special_handling}")
                    appendLine()
                }

                appendLine("---")
                appendLine()
            }
        }

        // Template Processing Rules
        appendLine("## Template Processing Rules")
        appendLine()
        appendLine("These rules define how credentials are transformed and substituted into configuration files.")
        appendLine()

        schema.template_rules.forEach { rule ->
            appendLine("### Rule: `${rule.name}`")
            appendLine()
            appendLine("**Path Pattern:** `${rule.path_pattern}`")
            appendLine()

            if (rule.substitutions.isNotEmpty()) {
                appendLine("**Substitutions:**")
                appendLine()
                appendLine("| Variable | Source | Transform |")
                appendLine("|----------|--------|-----------|")
                rule.substitutions.forEach { (varName, sub) ->
                    val source = sub.source ?: varName
                    appendLine("| `$varName` | `$source` | `${sub.transform}` |")
                }
                appendLine()
            }

            if (rule.then_convert_remaining == true) {
                appendLine("**Then:** Convert remaining `{{VAR}}` → `\${VAR}` for runtime substitution")
                appendLine()
            }

            appendLine("---")
            appendLine()
        }

        // Statistics
        appendLine("## Statistics")
        appendLine()
        appendLine("- **Total Credentials:** ${schema.credentials.size}")
        appendLine("- **Secrets:** ${schema.credentials.count { it.type in listOf("hex_secret", "laravel_key", "rsa_key", "oauth_secret") }}")
        appendLine("- **User-Provided:** ${schema.credentials.count { it.type == "user_provided" }}")
        appendLine("- **Config Values:** ${schema.credentials.count { it.type == "config_value" }}")
        appendLine("- **OAuth Pairs:** ${schema.credentials.count { it.type == "oauth_secret" }}")
        appendLine("- **Template Rules:** ${schema.template_rules.size}")
        appendLine()

        // Generate hash count
        val totalHashes = schema.credentials.sumOf { it.hash_variants?.size ?: 0 }
        appendLine("- **Hash Variants:** $totalHashes")
        appendLine()

        appendLine("---")
        appendLine()
        appendLine("*Generated from `credentials.schema.yaml` by `generate-credential-docs.main.kts`*")
    }

    File("CREDENTIALS.md").writeText(doc)
    println("✓ Generated CREDENTIALS.md (${doc.lines().size} lines)")
}

main(args)
