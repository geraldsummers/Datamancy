#!/usr/bin/env kotlin

/**
 * Shared Utilities for Datamancy Stack Scripts
 *
 * Common functions used across all stack management scripts.
 * Import this file in other scripts with: @file:Import("shared-utils.kts")
 */

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

// ============================================================================
// ANSI Color Codes
// ============================================================================
val ANSI_RESET = "\u001B[0m"
val ANSI_RED = "\u001B[31m"
val ANSI_GREEN = "\u001B[32m"
val ANSI_YELLOW = "\u001B[33m"
val ANSI_CYAN = "\u001B[36m"
val ANSI_BLUE = "\u001B[34m"

// ============================================================================
// Logging Functions
// ============================================================================
fun info(msg: String) = println("${ANSI_GREEN}[INFO]${ANSI_RESET} $msg")
fun warn(msg: String) = println("${ANSI_YELLOW}[WARN]${ANSI_RESET} $msg")
fun success(msg: String) = println("${ANSI_GREEN}✓${ANSI_RESET} $msg")

fun error(msg: String): Nothing {
    System.err.println("${ANSI_RED}[ERROR]${ANSI_RESET} $msg")
    kotlin.system.exitProcess(1)
}

fun debug(msg: String, verbose: Boolean = false) {
    if (verbose) println("${ANSI_BLUE}[DEBUG]${ANSI_RESET} $msg")
}

// ============================================================================
// Path Resolution
// ============================================================================
fun resolveHomeDir(): String = System.getProperty("user.home")

fun makeAbsolute(base: Path, pathStr: String): Path {
    var resolved = pathStr
        .replace("\${HOME}", resolveHomeDir())
        .replace("~", resolveHomeDir())

    val p = Paths.get(resolved)
    return if (p.isAbsolute) p.normalize() else base.resolve(p).normalize()
}

// ============================================================================
// Process Execution
// ============================================================================
fun run(
    vararg cmd: String,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    allowFail: Boolean = false,
    showOutput: Boolean = true
): String {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    if (env.isNotEmpty()) pb.environment().putAll(env)
    pb.redirectErrorStream(true)

    val p = pb.start()
    p.outputStream.close()

    val outputBuilder = StringBuilder()
    p.inputStream.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            if (showOutput) println(line)
            outputBuilder.appendLine(line)
        }
    }

    val out = outputBuilder.toString()
    val code = p.waitFor()

    if (code != 0 && !allowFail) {
        if (!showOutput) System.err.println(out)
        error("Command failed ($code): ${cmd.joinToString(" ")}")
    }

    return out
}

// ============================================================================
// File Permissions
// ============================================================================
fun setPermissions(path: Path, executable: Boolean = false) {
    try {
        val perms = if (executable) {
            // 755: rwxr-xr-x
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )
        } else {
            // 644: rw-r--r--
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX filesystem (Windows)
    }
}

fun setPerm600(path: Path) {
    try {
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        )
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS
    }
}

fun setPerm700(path: Path) {
    try {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS
    }
}

// ============================================================================
// Environment File Parsing
// ============================================================================
fun parseEnvFile(envFile: Path): Map<String, String> {
    if (!Files.exists(envFile)) return emptyMap()

    val env = mutableMapOf<String, String>()
    val lines = Files.readAllLines(envFile)
    var i = 0

    while (i < lines.size) {
        val line = lines[i].trim()

        // Skip comments and empty lines
        if (line.isEmpty() || line.startsWith("#")) {
            i++
            continue
        }

        // Parse KEY=VALUE
        val equalIndex = line.indexOf('=')
        if (equalIndex > 0) {
            val key = line.substring(0, equalIndex).trim()
            var value = if (equalIndex + 1 < line.length) {
                line.substring(equalIndex + 1).trim()
            } else {
                ""
            }

            // Remove surrounding quotes if present
            if (value.isNotEmpty()) {
                if (value.startsWith("'") && value.endsWith("'") && value.length > 1) {
                    value = value.substring(1, value.length - 1)
                } else if (value.startsWith("\"") && value.endsWith("\"") && value.length > 1) {
                    value = value.substring(1, value.length - 1)
                }
            }

            // Check for multi-line value
            val isMultiLine = value == "\"" || value.endsWith("\\n") ||
                    (value.startsWith("\"") && !value.endsWith("\""))

            if (isMultiLine) {
                // Multi-line value - collect subsequent lines until closing quote
                val multiLineValue = StringBuilder()
                if (value != "\"") {
                    multiLineValue.append(if (value.startsWith("\"")) value.substring(1) else value)
                }

                i++
                while (i < lines.size) {
                    val nextLine = lines[i]
                    if (nextLine.trim().endsWith("\"")) {
                        val content = nextLine.trimEnd()
                        if (multiLineValue.isNotEmpty()) multiLineValue.append("\n")
                        multiLineValue.append(content.substring(0, content.length - 1))
                        break
                    } else {
                        if (multiLineValue.isNotEmpty()) multiLineValue.append("\n")
                        multiLineValue.append(nextLine)
                    }
                    i++
                }

                value = multiLineValue.toString()
            }

            env[key] = value
        }
        i++
    }

    return env
}

// ============================================================================
// Root Detection
// ============================================================================
fun isRoot(): Boolean = try {
    val pb = ProcessBuilder("id", "-u").redirectErrorStream(true)
    val out = pb.start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
    out == "0"
} catch (_: Exception) {
    false
}
