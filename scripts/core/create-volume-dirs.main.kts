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

    // Read storage paths from .env.runtime or .env
    val home = System.getProperty("user.home")
    val runtimeEnv = Paths.get(home, ".datamancy/.env.runtime").toFile()
    val dotEnv = if (runtimeEnv.isFile) runtimeEnv else projectRoot.resolve(".env").toFile()

    var volumesRootStr = readEnvVarFromDotEnv(dotEnv, "VOLUMES_ROOT")
    if (volumesRootStr.isNullOrBlank()) {
        println("${YELLOW}Warning: VOLUMES_ROOT not found in env, using default${NC}")
        volumesRootStr = "$home/.datamancy/volumes"
    }

    var nonVectorDbsPath = readEnvVarFromDotEnv(dotEnv, "NON_VECTOR_DBS_PATH")
    if (nonVectorDbsPath.isNullOrBlank()) {
        nonVectorDbsPath = "$home/.datamancy/volumes/databases"
    }

    var vectorDbsPath = readEnvVarFromDotEnv(dotEnv, "VECTOR_DBS_PATH")
    if (vectorDbsPath.isNullOrBlank()) {
        vectorDbsPath = "$home/.datamancy/volumes/vector-dbs"
    }

    var applicationDataPath = readEnvVarFromDotEnv(dotEnv, "APPLICATION_DATA_PATH")
    if (applicationDataPath.isNullOrBlank()) {
        applicationDataPath = "$home/.datamancy/volumes/applications"
    }

    val volumesRoot = makeAbsolute(projectRoot, volumesRootStr)
    val nonVectorDbsRoot = makeAbsolute(projectRoot, nonVectorDbsPath)
    val vectorDbsRoot = makeAbsolute(projectRoot, vectorDbsPath)
    val applicationDataRoot = makeAbsolute(projectRoot, applicationDataPath)

    println("${GREEN}=== Creating Volume Directories ===${NC}")
    println("VOLUMES_ROOT: $volumesRoot")
    println("NON_VECTOR_DBS_PATH: $nonVectorDbsRoot")
    println("VECTOR_DBS_PATH: $vectorDbsRoot")
    println("APPLICATION_DATA_PATH: $applicationDataRoot")
    println()

    val composeFile = projectRoot.resolve("docker-compose.yml").toFile()
    if (!composeFile.isFile) {
        System.err.println("${RED}Error: docker-compose.yml not found at $composeFile${NC}")
        exitProcess(1)
    }

    // Collect directories referenced in docker-compose.yml with their base paths
    data class VolumeEntry(val base: Path, val rel: String)
    val volumeDirs = linkedSetOf<VolumeEntry>()
    val lines = composeFile.readLines()

    // Patterns for different volume path variables
    val patterns = listOf(
        "NON_VECTOR_DBS_PATH" to nonVectorDbsRoot,
        "VECTOR_DBS_PATH" to vectorDbsRoot,
        "APPLICATION_DATA_PATH" to applicationDataRoot,
        "VOLUMES_ROOT" to volumesRoot
    )

    for (rawLine in lines) {
        val line = rawLine.trimEnd()
        for ((varName, basePath) in patterns) {
            // Match lines like: device: ${VAR}/path or device: ${VAR:-${NESTED:-default}/fallback}/path
            // Strategy: Find ${VAR... then skip to the last } before a /, then extract path after /

            // For long syntax (device: ...)
            if (line.trim().startsWith("device:") && line.contains("\${$varName")) {
                // Find the position after the variable closes and extract the path
                val varStart = line.indexOf("\${$varName")
                if (varStart >= 0) {
                    val afterVar = line.substring(varStart + 2) // Skip ${
                    // Find the matching closing brace by counting braces
                    var braceCount = 1
                    var endPos = 0
                    for (i in afterVar.indices) {
                        when (afterVar[i]) {
                            '{' -> braceCount++
                            '}' -> {
                                braceCount--
                                if (braceCount == 0) {
                                    endPos = i
                                    break
                                }
                            }
                        }
                    }
                    if (endPos > 0 && endPos < afterVar.length - 1 && afterVar[endPos + 1] == '/') {
                        val rel = afterVar.substring(endPos + 2).trim().removeSurrounding("\"", "\"")
                        if (rel.isNotBlank()) {
                            volumeDirs.add(VolumeEntry(basePath, rel))
                            break
                        }
                    }
                }
            }

            // For short syntax (volume mounts)
            val shortPattern = "\\$\\{$varName"
            if (line.contains(shortPattern)) {
                val varStart = line.indexOf(shortPattern)
                if (varStart >= 0) {
                    val afterVar = line.substring(varStart + 2) // Skip ${
                    var braceCount = 1
                    var endPos = 0
                    for (i in afterVar.indices) {
                        when (afterVar[i]) {
                            '{' -> braceCount++
                            '}' -> {
                                braceCount--
                                if (braceCount == 0) {
                                    endPos = i
                                    break
                                }
                            }
                        }
                    }
                    if (endPos > 0 && endPos < afterVar.length - 1 && afterVar[endPos + 1] == '/') {
                        val remaining = afterVar.substring(endPos + 2)
                        val pathEnd = remaining.indexOfAny(charArrayOf(':', ' ', '\t'))
                        val rel = if (pathEnd > 0) remaining.substring(0, pathEnd) else remaining
                        val cleaned = rel.trim().removeSurrounding("\"", "\"")
                        if (cleaned.isNotBlank()) {
                            volumeDirs.add(VolumeEntry(basePath, cleaned))
                        }
                    }
                }
            }
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

    for ((basePath, rel) in volumeDirs) {
        val fullPath = basePath.resolve(rel)
        try {
            if (Files.isDirectory(fullPath)) {
                println("${GREEN}✓${NC} Already exists: $fullPath")
                existed++
            } else {
                Files.createDirectories(fullPath)
                println("${GREEN}✓${NC} Created: $fullPath")
                created++
            }
        } catch (e: Exception) {
            println("${RED}✗${NC} Failed to create: $fullPath (${e.message})")
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
    val synapseDataDir = applicationDataRoot.resolve("synapse_data")
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
