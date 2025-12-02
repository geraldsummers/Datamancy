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
    val force: Boolean = false
)

fun parseArgs(argv: Array<String>): Args {
    var dryRun = false
    var verbose = false
    var force = false

    argv.forEach { arg ->
        when (arg) {
            "--dry-run", "-n" -> dryRun = true
            "--verbose", "-v" -> verbose = true
            "--force", "-f" -> force = true
            "--help", "-h" -> {
                println("""
                    Usage: kotlin scripts/process-config-templates.main.kts [OPTIONS]

                    Options:
                      --dry-run, -n    Show what would be processed without writing files
                      --verbose, -v    Show detailed processing information
                      --force, -f      Overwrite existing configs/ directory
                      --help, -h       Show this help message
                """.trimIndent())
                exitProcess(0)
            }
        }
    }

    return Args(dryRun, verbose, force)
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

fun loadEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) {
        error("Environment file not found: ${envFile.absolutePath}")
        error("Please create .env file with required variables")
        exitProcess(1)
    }

    val env = mutableMapOf<String, String>()

    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        // Skip comments and empty lines
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

        // Parse KEY=VALUE
        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val value = parts[1].trim()
            env[key] = value
        }
    }

    return env
}

fun processTemplate(content: String, env: Map<String, String>, filePath: String, verbose: Boolean): String {
    var result = content
    val placeholderRegex = """\{\{([A-Z_][A-Z0-9_]*)\}\}""".toRegex()

    val matches = placeholderRegex.findAll(content).toList()

    if (matches.isEmpty()) {
        debug("No placeholders found in $filePath", verbose)
        return result
    }

    debug("Processing ${matches.size} placeholders in $filePath", verbose)

    val missingVars = mutableListOf<String>()

    matches.forEach { match ->
        val varName = match.groupValues[1]
        val placeholder = match.value

        val value = env[varName]
        if (value != null) {
            result = result.replace(placeholder, value)
            debug("  $varName = $value", verbose)
        } else {
            missingVars.add(varName)
        }
    }

    if (missingVars.isNotEmpty()) {
        warn("Missing variables in $filePath: ${missingVars.joinToString(", ")}")
    }

    return result
}

fun copyFileStructure(
    sourceDir: File,
    targetDir: File,
    env: Map<String, String>,
    args: Args
): Pair<Int, Int> {
    var processedCount = 0
    var copiedCount = 0

    sourceDir.walkTopDown().forEach { sourceFile ->
        if (!sourceFile.isFile) return@forEach

        val relativePath = sourceFile.relativeTo(sourceDir).path
        val targetFile = File(targetDir, relativePath)

        // Determine if file should be processed as template
        val shouldProcess = sourceFile.extension in listOf("yml", "yaml", "conf", "json", "env", "toml", "sql", "xml", "ini")
            || sourceFile.name == "Caddyfile"
            || sourceFile.name.endsWith(".template")

        if (shouldProcess) {
            debug("Processing template: $relativePath", args.verbose)

            val content = sourceFile.readText()
            val processed = processTemplate(content, env, relativePath, args.verbose)

            if (!args.dryRun) {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(processed)
            }

            processedCount++
        } else {
            debug("Copying binary/script: $relativePath", args.verbose)

            if (!args.dryRun) {
                targetFile.parentFile?.mkdirs()
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            copiedCount++
        }
    }

    return Pair(processedCount, copiedCount)
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
    val configsDir = File(projectRoot, "configs")
    val envFile = File(projectRoot, ".env")

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

    val (processedCount, copiedCount) = copyFileStructure(
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

    if (args.dryRun) {
        info("  (Dry run - no files written)")
    } else {
        info("  Output: ${configsDir.absolutePath}")
    }
    log("=".repeat(60), BLUE)
}

main(args)
