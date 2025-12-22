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

    // Read storage paths from .env
    val home = System.getProperty("user.home")
    val dotEnv = Paths.get(home, ".datamancy/.env").toFile()

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

    // Parse docker-compose.yml volumes section to extract all device paths
    val volumeDirs = linkedSetOf<Path>()
    val lines = composeFile.readLines()

    // Find volumes section
    var inVolumesSection = false
    for (line in lines) {
        val trimmed = line.trim()

        // Check if we're entering the volumes section
        if (trimmed == "volumes:") {
            inVolumesSection = true
            continue
        }

        // Check if we've left the volumes section (reached services or another top-level key)
        // Skip comments (lines starting with #)
        if (inVolumesSection && line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#")) {
            break
        }

        // Parse device: lines within volumes section
        if (inVolumesSection && trimmed.startsWith("device:")) {
            val devicePath = trimmed.substring(7).trim() // Remove "device:"

            // Resolve environment variables in the path
            var resolvedPath = devicePath

            // Handle ${HOME}
            if (resolvedPath.contains("\${HOME}")) {
                resolvedPath = resolvedPath.replace("\${HOME}", home)
            }

            // Handle ${APPLICATION_DATA_PATH:-${VOLUMES_ROOT:-./volumes}/applications}
            if (resolvedPath.contains("\${APPLICATION_DATA_PATH")) {
                resolvedPath = applicationDataRoot.toString()
            } else if (resolvedPath.contains("\${NON_VECTOR_DBS_PATH")) {
                resolvedPath = nonVectorDbsRoot.toString()
            } else if (resolvedPath.contains("\${VECTOR_DBS_PATH")) {
                resolvedPath = vectorDbsRoot.toString()
            } else if (resolvedPath.contains("\${VOLUMES_ROOT")) {
                resolvedPath = volumesRoot.toString()
            }

            // Extract the directory name after the last / if the path contains variable references
            if (devicePath.contains("\${") && devicePath.contains("}/")) {
                // Extract everything after the closing brace and /
                val lastPart = devicePath.substringAfterLast("}/")

                // Determine which base path to use based on the variable
                val basePath = when {
                    devicePath.contains("HOME") -> Paths.get(home)
                    devicePath.contains("APPLICATION_DATA_PATH") -> applicationDataRoot
                    devicePath.contains("NON_VECTOR_DBS_PATH") -> nonVectorDbsRoot
                    devicePath.contains("VECTOR_DBS_PATH") -> vectorDbsRoot
                    devicePath.contains("VOLUMES_ROOT") -> volumesRoot
                    else -> null
                }

                if (basePath != null && lastPart.isNotBlank()) {
                    volumeDirs.add(basePath.resolve(lastPart))
                }
            } else {
                // Simple path without complex variable substitution
                val path = Paths.get(resolvedPath)
                if (!path.toString().contains("\${")) {
                    volumeDirs.add(path.normalize())
                }
            }
        }
    }

    // Add init script directories (not in docker-compose volumes but needed for bind mounts)
    volumeDirs.add(volumesRoot.resolve("bookstack_init"))
    volumeDirs.add(volumesRoot.resolve("qbittorrent_init"))
    volumeDirs.add(volumesRoot.resolve("ldap_init"))

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

    for (fullPath in volumeDirs) {
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

    println()
    println("${GREEN}Done!${NC}")
}

main(args)
