package org.datamancy.stackdiscovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ServiceTarget(
    val name: String,
    val internal: List<String> = emptyList(),
    val external: List<String> = emptyList(),
)

@Serializable
data class ServicesManifest(val services: List<ServiceTarget>)

fun main(args: Array<String>) {
    // Minimal, best-effort discovery: parse docker-compose.yml service names and a Caddyfile for hostnames
    val repoRoot = System.getenv("REPO_ROOT") ?: "."
    val composePath = args.getOrNull(0) ?: File(repoRoot, "docker-compose.yml").absolutePath
    val caddyPath = args.getOrNull(1) ?: File(repoRoot, "configs/infrastructure/caddy/Caddyfile").absolutePath
    val outPath = args.getOrNull(2) ?: File(repoRoot, "configs/probe-orchestrator/services_manifest.json").absolutePath

    val composeFile = File(composePath)
    val caddyFile = File(caddyPath)

    val services = mutableSetOf<String>()
    if (composeFile.exists()) {
        // Very simple parse to collect service names under "services:" top-level
        var inServices = false
        composeFile.readLines().forEach { line ->
            val l = line.trimEnd()
            if (l.matches(Regex("^services:\s*$"))) {
                inServices = true
            } else if (inServices && l.isNotBlank() && !l.startsWith("#")) {
                // service name likely at column 0 without leading spaces at the project-normalized indentation
                val m = Regex("^([a-zA-Z0-9_.-]+):\s*$").find(l.trim())
                if (m != null) {
                    services.add(m.groupValues[1])
                }
            }
        }
    }

    // Parse Caddyfile for external hostnames mapping to service names
    val externalMap = mutableMapOf<String, String>() // hostname -> service name
    if (caddyFile.exists()) {
        val lines = caddyFile.readLines()
        var currentHost: String? = null
        lines.forEachIndexed { idx, raw ->
            val line = raw.trim()
            // hostname lines like: open-webui.${DOMAIN} or open-webui.project-saturn.com
            if (line.matches(Regex("^[a-zA-Z0-9_.-]+\\.[^\n\s]+\s*\{\s*$"))) {
                currentHost = line.substringBefore(" ").trim()
            }
            if (line.startsWith("reverse_proxy ") && currentHost != null) {
                val target = line.removePrefix("reverse_proxy ").trim()
                val serviceName = target.substringBefore(":").trim()
                externalMap[currentHost!!] = serviceName
                currentHost = null
            }
        }
    }

    val manifestServices = mutableListOf<ServiceTarget>()
    // Internal URLs: assume http://{service}:<commonPortIfKnown>
    // Since ports vary, we only include internal base service URL without port for unknowns.
    services.forEach { svc ->
        val internal = mutableListOf<String>()
        // Heuristics for common ports in this stack (best-effort)
        val commonInternal = when (svc) {
            "open-webui" -> listOf("http://open-webui:8080")
            "kfuncdb" -> listOf("http://kfuncdb:8081")
            "litellm" -> listOf("http://litellm:4000/health")
            else -> emptyList()
        }
        internal.addAll(commonInternal)

        val externals = externalMap.filterValues { it == svc }.keys.map { host ->
            val h = host.replace(".project-saturn.com", ".\${DOMAIN}")
            "https://$h"
        }

        manifestServices.add(ServiceTarget(name = svc, internal = internal.distinct(), external = externals.distinct()))
    }

    val manifest = ServicesManifest(manifestServices.sortedBy { it.name })
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(manifest))
    println("Services manifest written to: ${outFile.absolutePath} (${manifest.services.size} services)")
}
