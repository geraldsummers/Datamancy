#!/usr/bin/env kotlin

/**
 * Quick Template Secrets Fix
 *
 * Removes hardcoded secrets from configs.templates/ and replaces with {{PLACEHOLDERS}}
 * Much simpler than full remediation - just does the find/replace
 *
 * Usage: kotlin scripts/security/fix-template-secrets.main.kts [--dry-run]
 */

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

val GREEN = "\u001B[32m"; val YELLOW = "\u001B[33m"; val RED = "\u001B[31m"; val BLUE = "\u001B[34m"; val RESET = "\u001B[0m"
fun log(msg: String, color: String = RESET) = println("$color$msg$RESET")
fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg, YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)

val dryRun = args.contains("--dry-run")
val projectRoot = File(".").canonicalFile
val templatesDir = File(projectRoot, "configs.templates")

if (!templatesDir.exists()) {
    error("configs.templates/ not found")
    exitProcess(1)
}

log("=".repeat(60), BLUE)
log("FIXING HARDCODED SECRETS IN TEMPLATES", BLUE)
log("=".repeat(60), BLUE)
if (dryRun) warn("DRY RUN - no files will be changed")
println()

// Backup first
val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
val backupDir = File(projectRoot, "configs.templates.backup-$timestamp")
if (!dryRun) {
    templatesDir.copyRecursively(backupDir)
    info("Backup: ${backupDir.name}")
}

// Define all replacements
data class Fix(val file: String, val find: String, val replace: String, val desc: String)

val fixes = listOf(
    // 1. Authelia OIDC key -> file reference
    Fix(
        "applications/authelia/configuration.yml",
        Regex("""        key: \|
          -----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----""").pattern,
        "        key_file: /secrets/authelia-oidc-key.pem  # Mount as volume",
        "OIDC private key → file reference"
    ),

    // 2-12. Authelia OIDC client secrets (11 total)
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$sdc15.QdoAQO5WaI34.SIA\$9/Rzr6h4VD1dfMgwcJ3oguW5CPXpieD8bpgoNTuL980Cwl1qRXmpXVpwNVU7T1e4IfebEO/bjG.FDTYy2evieA'",
        "client_secret: '{{GRAFANA_OAUTH_SECRET_HASH}}'",
        "Grafana OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$hGULPsGTpdhUaJArnFQw1w\$DTGHhG8IH1MKr/CBtF4SyfYz0ShKwYLHhmaplEkRoQjGlUS8w41j.8haBv4YFEnMkTMm9ssWRyHk.VqwN1hZ.A'",
        "client_secret: '{{PGADMIN_OAUTH_SECRET_HASH}}'",
        "PgAdmin OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$xjrtm1exLKsKtYqhd87hmw\$k6Ovf7uY0UrNmqb2C2UdOuET9jQBgrSL3MSAxJmn.NrejoZC0fxEZBOTJaIvRkAO.QkVwAX09m1005DccL2DMA'",
        "client_secret: '{{DOCKGE_OAUTH_SECRET_HASH}}'",
        "Dockge OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$l1hLi5YZi6aUcZrwt33IjA\$hetxpKIsg4VcjN/yx7QgwMf5aAHu76WhirCGOTMJW4p4ePOx5OiTtqyM.39snwLcHE6cR4JAExVsnAkJR.n4Ng'",
        "client_secret: '{{OPENWEBUI_OAUTH_SECRET_HASH}}'",
        "OpenWebUI OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$rxb1/DG60ybksyZZBllrkQ\$L/G5aC852AnxXBTGD3rVtqXT3QWYFvfeVKyWNLwmIFXb/Xkt.09CRbsjGdooX9qc7alqlrpqVweZqoanlVHfSw'",
        "client_secret: '{{NEXTCLOUD_OAUTH_SECRET_HASH}}'",
        "Nextcloud OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$mtRWWM6rKQX2xKHXjXZ7jA\$3gIN6Y2Zlg5W9U1H50bvRrh6335PtoPKgepQkSVQ5vp7bnXbONPtycolpHtgGeXiH5UfhHHFQda0N3FkTI2eWw'",
        "client_secret: '{{DIM_OAUTH_SECRET_HASH}}'",
        "Dim OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$TGYuFojCMUJkeHtll1uIbA\$6vo1/Tf4KclfPPHUMogBprbrV.9Wydb8i3yJwzovTYsuWPIPux083f1.SdJOLbTbwJMWUGPEEndUs3BN8Z04ow'",
        "client_secret: '{{PLANKA_OAUTH_SECRET_HASH}}'",
        "Planka OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$nFwKGC1LCgUVzGjhK.769g\$BQ83aKaFDJ5ljHjiWyVV2du/TS6yRanKPf0jYNpczttbZdpCg/56pLx1FtR.7Gqswti.SWHFnRi/cyR1pI5qBA'",
        "client_secret: '{{HOMEASSISTANT_OAUTH_SECRET_HASH}}'",
        "HomeAssistant OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$TfK96.U2ZxeoDXe5txMcTw\$MqxrLDShlt3/MXo5W.VNX4jVNv5GXVYOT5DTP/ZnRx6iocUeAeJFR5OV8Z8DsNTdWUqEnkAJIHg2XZLhJ54XCg'",
        "client_secret: '{{JUPYTERHUB_OAUTH_SECRET_HASH}}'",
        "JupyterHub OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$3Qj3G8rM7vV3qJg8LQ0T3A\$dVJzAbg9y3gE0q0mQx0lJ4h7wQq9Vv8K6t7t1g9y1h2Yx4M8J9J4H8cY0f2Z6G7j5r1D2s3E5f6G7h8i9j0k' # TODO: replace with real hash",
        "client_secret: '{{VAULTWARDEN_OAUTH_SECRET_HASH}}'",
        "Vaultwarden OIDC secret"
    ),
    Fix(
        "applications/authelia/configuration.yml",
        "client_secret: '\$pbkdf2-sha512\$310000\$Mastodon2024CHANGEME\$dVJzAbg9y3gE0q0mQx0lJ4h7wQq9Vv8K6t7t1g9y1h2Yx4M8J9J4H8cY0f2Z6G7j5r1D2s3E5f6G7h8i9j0k' # TODO: Generate proper hash",
        "client_secret: '{{MASTODON_OAUTH_SECRET_HASH}}'",
        "Mastodon OIDC secret"
    ),

    // 13-14. Mailu secrets
    Fix(
        "applications/mailu/mailu.env",
        "SECRET_KEY=LiZ0Vk8ZmGEL33zJvVVqJA==",
        "SECRET_KEY={{MAILU_SECRET_KEY}}",
        "Mailu SECRET_KEY"
    ),
    Fix(
        "applications/mailu/mailu.env",
        "SECRET=LiZ0Vk8ZmGEL33zJvVVqJA==",
        "SECRET={{MAILU_SECRET_KEY}}",
        "Mailu SECRET"
    ),

    // 15-17. Synapse secrets
    Fix(
        "applications/synapse/homeserver.yaml",
        """registration_shared_secret: "changeme_synapse_registration"""",
        """registration_shared_secret: "{{SYNAPSE_REGISTRATION_SECRET}}"""",
        "Synapse registration secret"
    ),
    Fix(
        "applications/synapse/homeserver.yaml",
        """macaroon_secret_key: "changeme_synapse_macaroon"""",
        """macaroon_secret_key: "{{SYNAPSE_MACAROON_SECRET}}"""",
        "Synapse macaroon secret"
    ),
    Fix(
        "applications/synapse/homeserver.yaml",
        """form_secret: "changeme_synapse_form"""",
        """form_secret: "{{SYNAPSE_FORM_SECRET}}"""",
        "Synapse form secret"
    ),

    // 18. Jellyfin
    Fix(
        "applications/jellyfin/SSO-Auth.xml",
        "<OidSecret>changeme_jellyfin_oauth</OidSecret>",
        "<OidSecret>{{JELLYFIN_OIDC_SECRET}}</OidSecret>",
        "Jellyfin OIDC secret"
    ),

    // 19. Kopia - fail if not set
    Fix(
        "applications/kopia/init-kopia.sh",
        """KOPIA_PASSWORD="${'$'}{KOPIA_PASSWORD:-changeme}"""",
        """KOPIA_PASSWORD="${'$'}{KOPIA_PASSWORD:?ERROR: KOPIA_PASSWORD must be set}"""",
        "Kopia password (fail-secure)"
    ),

    // 20-26. Database passwords - fail if not set
    Fix(
        "databases/postgres/init-db.sh",
        """PLANKA_DB_PASSWORD="${'$'}{PLANKA_DB_PASSWORD:-changeme_planka_db}"""",
        """PLANKA_DB_PASSWORD="${'$'}{PLANKA_DB_PASSWORD:?ERROR: PLANKA_DB_PASSWORD not set}"""",
        "Planka DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """SYNAPSE_DB_PASSWORD="${'$'}{SYNAPSE_DB_PASSWORD:-changeme_synapse_db}"""",
        """SYNAPSE_DB_PASSWORD="${'$'}{SYNAPSE_DB_PASSWORD:?ERROR: SYNAPSE_DB_PASSWORD not set}"""",
        "Synapse DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """MAILU_DB_PASSWORD="${'$'}{MAILU_DB_PASSWORD:-changeme_mailu_db}"""",
        """MAILU_DB_PASSWORD="${'$'}{MAILU_DB_PASSWORD:?ERROR: MAILU_DB_PASSWORD not set}"""",
        "Mailu DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """AUTHELIA_DB_PASSWORD="${'$'}{AUTHELIA_DB_PASSWORD:-changeme_authelia_db}"""",
        """AUTHELIA_DB_PASSWORD="${'$'}{AUTHELIA_DB_PASSWORD:?ERROR: AUTHELIA_DB_PASSWORD not set}"""",
        "Authelia DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """GRAFANA_DB_PASSWORD="${'$'}{GRAFANA_DB_PASSWORD:-changeme_grafana_db}"""",
        """GRAFANA_DB_PASSWORD="${'$'}{GRAFANA_DB_PASSWORD:?ERROR: GRAFANA_DB_PASSWORD not set}"""",
        "Grafana DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """VAULTWARDEN_DB_PASSWORD="${'$'}{VAULTWARDEN_DB_PASSWORD:-changeme_vaultwarden_db}"""",
        """VAULTWARDEN_DB_PASSWORD="${'$'}{VAULTWARDEN_DB_PASSWORD:?ERROR: VAULTWARDEN_DB_PASSWORD not set}"""",
        "Vaultwarden DB password (fail-secure)"
    ),
    Fix(
        "databases/postgres/init-db.sh",
        """OPENWEBUI_DB_PASSWORD="${'$'}{OPENWEBUI_DB_PASSWORD:-changeme_openwebui_db}"""",
        """OPENWEBUI_DB_PASSWORD="${'$'}{OPENWEBUI_DB_PASSWORD:?ERROR: OPENWEBUI_DB_PASSWORD not set}"""",
        "OpenWebUI DB password (fail-secure)"
    )
)

// Apply fixes
var fixedCount = 0
fixes.forEach { fix ->
    val file = File(templatesDir, fix.file)
    if (!file.exists()) {
        warn("File not found: ${fix.file}")
        return@forEach
    }

    val content = file.readText()
    val newContent = if (fix.find.contains("BEGIN PRIVATE KEY")) {
        // Regex replacement for OIDC key block
        content.replace(Regex(fix.find), fix.replace)
    } else {
        // Literal replacement
        content.replace(fix.find, fix.replace)
    }

    if (content != newContent) {
        if (!dryRun) {
            file.writeText(newContent)
        }
        info("✓ ${fix.desc}")
        fixedCount++
    }
}

println()
log("=".repeat(60), BLUE)
log("✓ Fixed $fixedCount secrets in templates", GREEN)
log("=".repeat(60), BLUE)
println()

if (!dryRun) {
    info("Next steps:")
    println("  1. Verify: diff -r ${backupDir.name} configs.templates")
    println("  2. Update .env with new secret values (see .env.example)")
    println("  3. Generate OIDC key: openssl genpkey -algorithm RSA -out volumes/secrets/authelia-oidc-key.pem -pkeyopt rsa_keygen_bits:4096")
    println("  4. chmod 600 volumes/secrets/authelia-oidc-key.pem")
    println("  5. Regenerate configs: kotlin scripts/core/process-config-templates.main.kts --force")
    println("  6. Test: docker compose --profile bootstrap config")
} else {
    warn("DRY RUN complete. Run without --dry-run to apply fixes.")
}
