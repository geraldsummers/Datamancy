#!/usr/bin/env kotlin

import java.io.File

/**
 * Adds standard logging configuration to services in docker-compose.yml that don't have it
 */

val composeFile = File("docker-compose.yml")
if (!composeFile.exists()) {
    println("ERROR: docker-compose.yml not found in current directory")
    kotlin.system.exitProcess(1)
}

val loggingBlock = """    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3""""

val content = composeFile.readText()
val lines = content.lines()

// Services that already have logging
val servicesWithLogging = setOf("grafana", "open-webui", "vaultwarden", "planka", "caddy")

// Services that should get logging (critical infrastructure + applications)
val criticalServices = listOf(
    "ldap", "redis", "authelia", "postgres", "mariadb", "litellm", "vllm",
    "agent-tool-server", "probe-orchestrator", "portainer", "dockge",
    "mailu-admin", "mailu-smtp", "mailu-imap", "mastodon-web", "mastodon-streaming",
    "synapse", "bookstack", "seafile", "jupyterhub", "homeassistant"
)

val result = StringBuilder()
var i = 0
var currentService: String? = null
var inService = false
var addedLoggingCount = 0

while (i < lines.size) {
    val line = lines[i]

    // Detect service definition
    if (line.matches(Regex("""^  [a-z][a-z0-9_-]+:$"""))) {
        val serviceName = line.trim().removeSuffix(":")
        currentService = serviceName
        inService = true
        result.appendLine(line)
    }
    // Inside a service, look for volumes: or healthcheck: or command: to insert logging before
    else if (inService && currentService != null &&
             currentService in criticalServices &&
             currentService !in servicesWithLogging &&
             (line.trim().startsWith("healthcheck:") ||
              line.trim().startsWith("command:") ||
              (line.matches(Regex("""^  [a-z][a-z0-9_-]+:$"""))))) {
        // Insert logging block before this line
        result.appendLine(loggingBlock)
        result.appendLine(line)
        addedLoggingCount++
        currentService = null
        inService = false
    }
    else {
        result.appendLine(line)
        // Reset when we reach next top-level key
        if (line.matches(Regex("""^[a-z]+:$"""))) {
            inService = false
            currentService = null
        }
    }

    i++
}

if (addedLoggingCount > 0) {
    composeFile.writeText(result.toString())
    println("✓ Added logging configuration to $addedLoggingCount services")
} else {
    println("✓ All critical services already have logging configured")
}
