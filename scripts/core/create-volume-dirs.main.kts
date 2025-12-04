#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

// ANSI colors
val RED = "\u001B[0;31m"
val GREEN = "\u001B[0;32m"
val YELLOW = "\u001B[1;33m"
val NC = "\u001B[0m" // No Color

fun readEnvVarFromDotEnv(dotEnv: File, key: String): String? {
    if (!dotEnv.isFile) return null
    dotEnv.readLines()
        .map { it.trim() }
        .forEach { line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val k = line.substring(0, idx).trim()
            if (k == key) {
                var v = line.substring(idx + 1).trim()
                if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\''))) {
                    v = v.substring(1, v.length - 1)
                }
                return v
            }
        }
    return null
}

fun makeAbsolute(base: Path, pathStr: String): Path {
    val p = Paths.get(pathStr)
    return if (p.isAbsolute) p.normalize() else base.resolve(p).normalize()
}

fun main(args: Array<String>) {
    // Determine script dir -> project root (scripts/..)
    val scriptPathProp = System.getProperty("kotlin.script.file.path")
    val projectRoot: Path = if (scriptPathProp != null) {
        Paths.get(scriptPathProp).toAbsolutePath().normalize().parent?.parent
            ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        // Fallback to current working directory
        Paths.get("").toAbsolutePath().normalize()
    }

    // Determine VOLUMES_ROOT
    var volumesRootStr: String? = null
    if (args.isNotEmpty()) {
        volumesRootStr = args[0]
    } else {
        // Prefer ~/.datamancy/.env.runtime; fallback to project .env
        val home = System.getProperty("user.home")
        val runtimeEnv = Paths.get(home, ".datamancy/.env.runtime").toFile()
        val dotEnv = if (runtimeEnv.isFile) runtimeEnv else projectRoot.resolve(".env").toFile()
        volumesRootStr = readEnvVarFromDotEnv(dotEnv, "VOLUMES_ROOT")
        if (volumesRootStr.isNullOrBlank()) {
            println("${YELLOW}Warning: VOLUMES_ROOT not found in env, using default${NC}")
            volumesRootStr = "$home/.datamancy/volumes"
        }
    }

    val volumesRoot = makeAbsolute(projectRoot, volumesRootStr!!)

    println("${GREEN}=== Creating Volume Directories ===${NC}")
    println("VOLUMES_ROOT: $volumesRoot")
    println()

    val composeFile = projectRoot.resolve("docker-compose.yml").toFile()
    if (!composeFile.isFile) {
        System.err.println("${RED}Error: docker-compose.yml not found at $composeFile${NC}")
        exitProcess(1)
    }

    // Collect directories referenced as ${VOLUMES_ROOT}/... in docker-compose.yml
    val volumeDirs = linkedSetOf<String>()
    val lines = composeFile.readLines()

    val longSyntaxRegex = Regex("^\\s*device: \\\\?\\$\\{VOLUMES_ROOT(?::-[^}]+)?\\}/(.+)$")
    // Matches short syntax mount entries like: - ${VOLUMES_ROOT}/path:/container or "${VOLUMES_ROOT}/path:/container:ro"
    // Also matches default value syntax: ${VOLUMES_ROOT:-./volumes}/path
    val shortSyntaxRegex = Regex("\\$\\{VOLUMES_ROOT(?::-[^}]+)?\\}/([^\\s:]+)")

    for (rawLine in lines) {
        val line = rawLine.trimEnd()
        val m1 = longSyntaxRegex.find(line)
        if (m1 != null) {
            val rel = m1.groupValues[1].trim().removeSurrounding("\"", "\"")
            if (rel.isNotBlank()) volumeDirs.add(rel)
            continue
        }
        // Short syntax may include multiple matches per line; extract all
        shortSyntaxRegex.findAll(line).forEach { match ->
            val rel = match.groupValues[1].trim().removeSurrounding("\"", "\"")
            if (rel.isNotBlank()) volumeDirs.add(rel)
        }
    }

    if (volumeDirs.isEmpty()) {
        System.err.println("${RED}Error: No volume directories found in docker-compose.yml${NC}")
        exitProcess(1)
    }

    val total = volumeDirs.size
    var created = 0
    var existed = 0
    var failed = 0

    println("Found $total volume directories to check/create")
    println()

    for (rel in volumeDirs) {
        val fullPath = volumesRoot.resolve(rel)
        try {
            if (Files.isDirectory(fullPath)) {
                println("${GREEN}✓${NC} Already exists: $rel")
                existed++
            } else {
                Files.createDirectories(fullPath)
                println("${GREEN}✓${NC} Created: $rel")
                created++
            }
        } catch (e: Exception) {
            println("${RED}✗${NC} Failed to create: $rel (${e.message})")
            failed++
        }
    }

    println()
    println("${GREEN}=== Summary ===${NC}")
    println("Total directories: $total")
    println("${GREEN}Created: $created${NC}")
    println("${YELLOW}Already existed: $existed${NC}")
    if (failed > 0) {
        println("${RED}Failed: $failed${NC}")
        println()
        println("${RED}Some directories could not be created. Please check permissions.${NC}")
        exitProcess(1)
    } else {
        println("${GREEN}All volume directories are ready!${NC}")
    }

    // Additional config directories
    println()
    println("${GREEN}=== Checking Config Directories ===${NC}")
    val configDirs = listOf("secrets", "authelia")
    for (dir in configDirs) {
        val full = volumesRoot.resolve(dir)
        try {
            if (Files.isDirectory(full)) {
                println("${GREEN}✓${NC} Already exists: $dir")
            } else {
                Files.createDirectories(full)
                println("${GREEN}✓${NC} Created: $dir")
            }
        } catch (e: Exception) {
            println("${YELLOW}⚠${NC} Could not create: $dir (may not be needed)")
        }
    }

    // Fix ownership for specific services that need non-standard UIDs
    println()
    println("${GREEN}=== Fixing Service-Specific Permissions ===${NC}")

    // Synapse runs as UID 991
    val synapseDataDir = volumesRoot.resolve("synapse_data")
    if (Files.isDirectory(synapseDataDir)) {
        try {
            val process = ProcessBuilder("chown", "-R", "991:991", synapseDataDir.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                println("${GREEN}✓${NC} Set ownership on synapse_data to UID 991")
            } else {
                println("${YELLOW}⚠${NC} Could not set ownership on synapse_data (may need sudo)")
                println("  Run manually: sudo chown -R 991:991 ${synapseDataDir}")
            }
        } catch (e: Exception) {
            println("${YELLOW}⚠${NC} Could not set ownership on synapse_data: ${e.message}")
            println("  Run manually: sudo chown -R 991:991 ${synapseDataDir}")
        }
    }

    println()
    println("${GREEN}Done!${NC}")
}

main(args)
