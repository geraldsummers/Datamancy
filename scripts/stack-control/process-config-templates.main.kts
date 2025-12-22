#!/usr/bin/env kotlin

/**
 * Configuration Template Processor
 *
 * Processes template files with {{VARIABLE}} placeholders and generates final configs
 * by substituting values from .env file.
 *
 * Usage:
 *   kotlin scripts/process-config-templates.main.kts [--dry-run] [--verbose]
 *
 * Template format:
 *   - Placeholders: {{DOMAIN}}, {{STACK_ADMIN_EMAIL}}, etc.
 *   - Comments preserved as-is
 *   - Supports nested directory structures
 *
 * Directory structure:
 *   configs.templates/  → Source templates with {{PLACEHOLDERS}}
 *   configs/            → Generated configs (gitignored in future)
 */

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.system.exitProcess

// ANSI colors
val RED = "\u001B[31m"
val GREEN = "\u001B[32m"
val YELLOW = "\u001B[33m"
val BLUE = "\u001B[34m"
val RESET = "\u001B[0m"

data class Args(
    val dryRun: Boolean = false,
    val verbose: Boolean = false,
    val force: Boolean = false,
    val outputDir: String? = null,
    val envFile: String? = null
)

fun parseArgs(argv: Array<String>): Args {
    var dryRun = false
    var verbose = false
    var force = false
    var outputDir: String? = null
    var envFile: String? = null

    argv.forEach { arg ->
        when {
            arg == "--dry-run" || arg == "-n" -> dryRun = true
            arg == "--verbose" || arg == "-v" -> verbose = true
            arg == "--force" || arg == "-f" -> force = true
            arg.startsWith("--output=") -> outputDir = arg.substringAfter("=")
            arg.startsWith("--env=") -> envFile = arg.substringAfter("=")
            arg == "--help" || arg == "-h" -> {
                println("""
                    Usage: kotlin scripts/process-config-templates.main.kts [OPTIONS]

                    Options:
                      --dry-run, -n         Show what would be processed without writing files
                      --verbose, -v         Show detailed processing information
                      --force, -f           Overwrite existing configs/ directory
                      --output=<path>       Output directory (default: ~/.datamancy/configs)
                      --env=<path>          Environment file path (default: ~/.datamancy/.env)
                      --help, -h            Show this help message
                """.trimIndent())
                exitProcess(0)
            }
        }
    }

    return Args(dryRun, verbose, force, outputDir, envFile)
}

fun log(message: String, color: String = RESET) {
    println("$color$message$RESET")
}

fun info(msg: String) = log("[INFO] $msg", GREEN)
fun warn(msg: String) = log("[WARN] $msg", YELLOW)
fun error(msg: String) = log("[ERROR] $msg", RED)
fun debug(msg: String, verbose: Boolean) {
    if (verbose) log("[DEBUG] $msg", BLUE)
}

/**
 * Generate SSHA hash from plaintext password (for LDAP)
 */
fun generateSSHA(plaintext: String): String {
    val salt = ByteArray(8)
    SecureRandom().nextBytes(salt)

    val md = MessageDigest.getInstance("SHA-1")
    md.update(plaintext.toByteArray(Charsets.UTF_8))
    md.update(salt)
    val digest = md.digest()

    val hashWithSalt = digest + salt
    val base64Hash = Base64.getEncoder().encodeToString(hashWithSalt)
    return "{SSHA}$base64Hash"
}

fun loadEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) {
        error("Environment file not found: ${envFile.absolutePath}")
        error("Please create .env file with required variables")
        exitProcess(1)
    }

    val env = mutableMapOf<String, String>()
    val lines = envFile.readLines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Skip comments and empty lines
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            i++
            continue
        }

        // Parse KEY=VALUE
        val equalIndex = trimmed.indexOf('=')
        if (equalIndex > 0) {
            val key = trimmed.substring(0, equalIndex).trim()
            var value = if (equalIndex + 1 < trimmed.length) {
                trimmed.substring(equalIndex + 1).trim()
            } else {
                "" // Empty value
            }

            // Remove surrounding quotes if present (only if value is not empty)
            if (value.isNotEmpty()) {
                if (value.startsWith("'") && value.endsWith("'") && value.length > 1) {
                    value = value.substring(1, value.length - 1)
                } else if (value.startsWith("\"") && value.endsWith("\"") && value.length > 1) {
                    value = value.substring(1, value.length - 1)
                }
            }

            // Check for multi-line value (value is just a quote, or ends with \n, or has unmatched quotes)
            val isMultiLine = value == "\"" || value.endsWith("\\n") ||
                             (value.startsWith("\"") && !value.endsWith("\""))

            if (isMultiLine) {
                // Multi-line value - collect subsequent lines until we find the closing quote
                val multiLineValue = StringBuilder()
                if (value != "\"") {
                    // Include the first line's value (minus the opening quote if present)
                    multiLineValue.append(if (value.startsWith("\"")) value.substring(1) else value)
                }

                i++
                while (i < lines.size) {
                    val nextLine = lines[i]
                    // Check if this line ends with a closing quote
                    if (nextLine.trim().endsWith("\"")) {
                        // Add this line without the closing quote and stop
                        val content = nextLine.trimEnd()
                        if (multiLineValue.isNotEmpty()) multiLineValue.append("\n")
                        multiLineValue.append(content.substring(0, content.length - 1))
                        break
                    } else {
                        // Add this line and continue
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

fun processTemplate(content: String, env: Map<String, String>, filePath: String, verbose: Boolean): Pair<String, List<String>> {
    var result = content
    val placeholderRegex = """\{\{([A-Z_][A-Z0-9_]*)\}\}""".toRegex()

    val matches = placeholderRegex.findAll(content).toList()

    if (matches.isEmpty()) {
        debug("No placeholders found in $filePath", verbose)
        return Pair(result, emptyList())
    }

    debug("Processing ${matches.size} placeholders in $filePath", verbose)

    val missingVars = mutableListOf<String>()

    matches.forEach { match ->
        val varName = match.groupValues[1]
        val placeholder = match.value

        val value = env[varName]
        if (value != null) {
            // For multi-line values, preserve indentation from the placeholder line
            if (value.contains("\n")) {
                // Find the line containing the placeholder
                val lines = result.split("\n")
                val lineIndex = lines.indexOfFirst { it.contains(placeholder) }
                if (lineIndex >= 0) {
                    val line = lines[lineIndex]
                    // Calculate indentation (spaces before the placeholder)
                    val indent = line.substringBefore(placeholder).takeWhile { it.isWhitespace() }

                    // Split the multi-line value and add proper indentation to each line except the first
                    val valueLines = value.split("\n")
                    val indentedValue = valueLines.mapIndexed { index, valueLine ->
                        if (index == 0) valueLine else indent + valueLine
                    }.joinToString("\n")

                    result = result.replace(placeholder, indentedValue)
                    debug("  $varName = <multi-line value>", verbose)
                } else {
                    result = result.replace(placeholder, value)
                    debug("  $varName = <multi-line value>", verbose)
                }
            } else {
                result = result.replace(placeholder, value)
                debug("  $varName = $value", verbose)
            }
        } else {
            missingVars.add(varName)
        }
    }

    if (missingVars.isNotEmpty()) {
        warn("Missing variables in $filePath: ${missingVars.joinToString(", ")}")
    }

    return Pair(result, missingVars)
}

fun setExecutable(file: File) {
    try {
        // Set to 755: rwxr-xr-x (readable and executable by all, writable by owner)
        val perms = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        )
        Files.setPosixFilePermissions(file.toPath(), perms)
    } catch (e: UnsupportedOperationException) {
        // Non-POSIX filesystem (Windows), use setExecutable instead
        file.setExecutable(true, false)
    } catch (e: Exception) {
        warn("Failed to set executable permission on ${file.name}: ${e.message}")
    }
}

fun copyFileStructure(
    sourceDir: File,
    targetDir: File,
    env: Map<String, String>,
    args: Args
): Triple<Int, Int, Map<String, List<String>>> {
    var processedCount = 0
    var copiedCount = 0
    val allMissingVars = mutableMapOf<String, List<String>>()

    // Critical variables that MUST be present
    val criticalVars = setOf(
        "DOMAIN",
        "STACK_ADMIN_USER",
        "STACK_ADMIN_PASSWORD",
        "STACK_ADMIN_EMAIL",
        "MAIL_DOMAIN",
        "VOLUMES_ROOT",
        "LITELLM_MASTER_KEY",
        "AUTHELIA_JWT_SECRET",
        "AUTHELIA_SESSION_SECRET",
        "AUTHELIA_STORAGE_ENCRYPTION_KEY"
    )

    sourceDir.walkTopDown().forEach { sourceFile ->
        if (!sourceFile.isFile) return@forEach

        val relativePath = sourceFile.relativeTo(sourceDir).path
        // Strip .template extension from output file name
        val outputPath = if (relativePath.endsWith(".template")) {
            relativePath.removeSuffix(".template")
        } else {
            relativePath
        }

        // Special case: LDAP bootstrap goes to root of ~/.datamancy/ instead of configs/
        val targetFile = if (relativePath.endsWith("bootstrap_ldap.ldif.template")) {
            File(targetDir.parentFile, "bootstrap_ldap.ldif")
        } else {
            File(targetDir, outputPath)
        }

        // Determine if file should be processed as template
        val shouldProcess = sourceFile.extension in listOf("yml", "yaml", "conf", "json", "env", "toml", "sql", "xml", "ini", "py")
            || sourceFile.name == "Caddyfile"
            || sourceFile.name.endsWith(".template")

        if (shouldProcess) {
            debug("Processing template: $relativePath", args.verbose)

            val content = sourceFile.readText()

            // Special handling for LDAP bootstrap files - generate SSHA hashes
            val envWithHashes = if (relativePath.endsWith(".ldif.template") || relativePath.contains("ldap")) {
                val extendedEnv = env.toMutableMap()

                // Generate SSHA hashes for passwords
                env["STACK_ADMIN_PASSWORD"]?.let { pwd ->
                    extendedEnv["ADMIN_SSHA_PASSWORD"] = generateSSHA(pwd)
                    debug("  Generated ADMIN_SSHA_PASSWORD hash", args.verbose)
                }
                env["STACK_USER_PASSWORD"]?.let { pwd ->
                    extendedEnv["USER_SSHA_PASSWORD"] = generateSSHA(pwd)
                    debug("  Generated USER_SSHA_PASSWORD hash", args.verbose)
                } ?: env["STACK_ADMIN_PASSWORD"]?.let { pwd ->
                    // Fallback to admin password if user password not set
                    extendedEnv["USER_SSHA_PASSWORD"] = generateSSHA(pwd)
                    debug("  Generated USER_SSHA_PASSWORD hash (using admin password)", args.verbose)
                }
                env["AGENT_LDAP_OBSERVER_PASSWORD"]?.let { pwd ->
                    extendedEnv["AGENT_OBSERVER_SSHA_PASSWORD"] = generateSSHA(pwd)
                    debug("  Generated AGENT_OBSERVER_SSHA_PASSWORD hash", args.verbose)
                }

                // Add generation timestamp
                extendedEnv["GENERATION_TIMESTAMP"] = java.time.Instant.now().toString()

                extendedEnv
            } else {
                env
            }

            val (processed, missingVars) = processTemplate(content, envWithHashes, relativePath, args.verbose)

            if (missingVars.isNotEmpty()) {
                allMissingVars[relativePath] = missingVars
            }

            if (!args.dryRun) {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(processed)

                // Set executable if source is executable or is a .sh file
                if (sourceFile.canExecute() || sourceFile.extension == "sh") {
                    setExecutable(targetFile)
                }
            }

            processedCount++
        } else {
            debug("Copying binary/script: $relativePath", args.verbose)

            if (!args.dryRun) {
                targetFile.parentFile?.mkdirs()
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                // Preserve executable permission for scripts and binaries
                if (sourceFile.canExecute() || sourceFile.extension == "sh") {
                    setExecutable(targetFile)
                }
            }

            copiedCount++
        }
    }

    // Check for critical missing variables
    val criticalMissing = allMissingVars.values.flatten().toSet().intersect(criticalVars)
    if (criticalMissing.isNotEmpty()) {
        println()
        error("CRITICAL: Missing required environment variables!")
        error("The following variables are required but not found in .env:")
        criticalMissing.forEach { error("  - $it") }
        println()
        error("Files affected:")
        allMissingVars.forEach { (file, vars) ->
            val critical = vars.filter { it in criticalVars }
            if (critical.isNotEmpty()) {
                error("  $file: ${critical.joinToString(", ")}")
            }
        }
        println()
        error("Action required:")
        error("1. Edit ~/.datamancy/.env")
        error("2. Add missing variables (run: ./stack-controller config generate)")
        error("3. Re-run config processing")
        exitProcess(1)
    }

    return Triple(processedCount, copiedCount, allMissingVars)
}

fun main(argv: Array<String>) {
    val args = parseArgs(argv)

    log("=".repeat(60), BLUE)
    log("Configuration Template Processor", BLUE)
    log("=".repeat(60), BLUE)
    println()

    // Paths
    val projectRoot = File(".").canonicalFile
    val templatesDir = File(projectRoot, "configs.templates")
    val configsDir = if (args.outputDir != null) {
        File(args.outputDir)
    } else {
        File(System.getProperty("user.home") + "/.datamancy/configs")
    }

    // Determine env file location
    val envFile = when {
        args.envFile != null -> File(args.envFile)
        File(System.getProperty("user.home") + "/.datamancy/.env").exists() -> File(System.getProperty("user.home") + "/.datamancy/.env")
        else -> File(System.getProperty("user.home") + "/.datamancy/.env")
    }

    // Validate
    if (!templatesDir.exists()) {
        error("Templates directory not found: ${templatesDir.absolutePath}")
        error("Expected: configs.templates/")
        error("Action: Move configs/ to configs.templates/ and add {{PLACEHOLDERS}}")
        exitProcess(1)
    }

    if (configsDir.exists() && !args.force && !args.dryRun) {
        warn("Target directory already exists: ${configsDir.absolutePath}")
        warn("Use --force to overwrite or --dry-run to preview")
        exitProcess(1)
    }

    // Load environment
    info("Loading environment from: ${envFile.name}")
    val env = loadEnvFile(envFile)
    info("Loaded ${env.size} environment variables")
    println()

    // Process templates
    if (args.dryRun) {
        info("DRY RUN MODE - No files will be written")
    } else {
        info("Processing templates: ${templatesDir.name} → ${configsDir.name}")
    }
    println()

    val (processedCount, copiedCount, allMissingVars) = copyFileStructure(
        templatesDir,
        configsDir,
        env,
        args
    )

    println()
    log("=".repeat(60), BLUE)
    info("✓ Template processing complete")
    info("  Processed: $processedCount files")
    info("  Copied: $copiedCount files")

    // Report non-critical missing variables as warnings
    val nonCriticalMissing = allMissingVars.filterValues { vars ->
        vars.any { it !in setOf(
            "DOMAIN", "STACK_ADMIN_USER", "STACK_ADMIN_PASSWORD",
            "STACK_ADMIN_EMAIL", "MAIL_DOMAIN", "VOLUMES_ROOT",
            "LITELLM_MASTER_KEY", "AUTHELIA_JWT_SECRET",
            "AUTHELIA_SESSION_SECRET", "AUTHELIA_STORAGE_ENCRYPTION_KEY"
        )}
    }

    if (nonCriticalMissing.isNotEmpty()) {
        println()
        warn("Optional variables missing (may need configuration):")
        nonCriticalMissing.forEach { (file, vars) ->
            warn("  $file: ${vars.joinToString(", ")}")
        }
    }

    if (args.dryRun) {
        info("  (Dry run - no files written)")
    } else {
        info("  Output: ${configsDir.absolutePath}")
    }
    log("=".repeat(60), BLUE)
}

main(args)
