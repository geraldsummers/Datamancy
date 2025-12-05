#!/usr/bin/env kotlin

/**
 * Datamancy Stack Controller
 *
 * Central management script for the sovereign compute cluster.
 * Handles deployment, configuration, secrets, and operations.
 *
 * Usage:
 *   ./stack-controller <command> [options]
 *
 * Command Categories:
 *   secrets    - Secrets/encryption management
 *   up/down    - Stack lifecycle
 *   config     - Configuration generation
 *   deploy     - Server deployment (SSH-based)
 *   volumes    - Volume management
 *   ldap       - LDAP sync operations
 *   clean      - Cleanup operations
 *
 * Examples:
 *   ./stack-controller secrets encrypt
 *   ./stack-controller up
 *   ./stack-controller config process
 *   ./stack-controller deploy create-user
 */

@file:Suppress("SameParameterValue", "unused")

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.system.exitProcess

// ============================================================================
// Utilities
// ============================================================================

// ANSI color codes
private val ANSI_RESET = "\u001B[0m"
private val ANSI_RED = "\u001B[31m"
private val ANSI_GREEN = "\u001B[32m"
private val ANSI_YELLOW = "\u001B[33m"
private val ANSI_CYAN = "\u001B[36m"

private fun info(msg: String) = println("${ANSI_GREEN}[INFO]${ANSI_RESET} $msg")
private fun warn(msg: String) = println("${ANSI_YELLOW}[WARN]${ANSI_RESET} $msg")
private fun success(msg: String) = println("${ANSI_GREEN}✓${ANSI_RESET} $msg")
private fun err(msg: String): Nothing {
    System.err.println("${ANSI_RED}[ERROR]${ANSI_RESET} $msg")
    exitProcess(1)
}

private fun isRoot(): Boolean = try {
    val pb = ProcessBuilder("id", "-u").redirectErrorStream(true)
    val out = pb.start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
    out == "0"
} catch (_: Exception) { false }

private fun run(
    vararg cmd: String,
    cwd: Path? = null,
    env: Map<String, String> = emptyMap(),
    input: String? = null,
    allowFail: Boolean = false
): String {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    if (env.isNotEmpty()) pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val p = pb.start()
    if (input != null) {
        p.outputStream.use { it.write(input.toByteArray()) }
    } else {
        p.outputStream.close()
    }
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = p.waitFor()
    if (code != 0 && !allowFail) {
        err("Command failed ($code): ${cmd.joinToString(" ")}\n$out")
    }
    return out
}

private fun projectRoot(): Path {
    // stack-controller.main.kts is located AT project root (not in scripts/)
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        // Get the directory containing the script
        val scriptPath = Paths.get(prop).toAbsolutePath().normalize()
        scriptPath.parent ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        Paths.get("").toAbsolutePath().normalize()
    }
}

/**
 * Returns the path to the Datamancy data directory.
 * All runtime configs, secrets, and volumes are stored here.
 *
 * @return Path to ~/.datamancy directory
 */
private fun datamancyDataDir(): Path {
    val userHome = Paths.get(System.getProperty("user.home"))
    return userHome.resolve(".datamancy")
}

/**
 * Ensures the Datamancy data directory exists and returns its path.
 * Creates the directory if it doesn't exist.
 *
 * CRITICAL: This must be called before Docker Compose starts to prevent
 * Docker from creating directories as root when mounting volumes.
 *
 * Security: Exits if running as root to prevent permission issues.
 *
 * @return Path to ~/.datamancy directory
 * @throws SecurityException if running as root
 */
private fun ensureDatamancyDataDir(): Path {
    // Security: Never run config/ldap/volumes commands as root
    if (isRoot()) {
        err("Operations must not be run as root. Run without sudo.")
    }

    val dir = datamancyDataDir()
    Files.createDirectories(dir)

    // Create critical subdirectories to prevent Docker from creating them as root
    // when mounting volumes from docker-compose.yml
    val configsDir = dir.resolve("configs")
    val volumesDir = dir.resolve("volumes")

    if (!Files.exists(configsDir)) {
        Files.createDirectories(configsDir)
    }
    if (!Files.exists(volumesDir)) {
        Files.createDirectories(volumesDir)
    }

    return dir
}

/**
 * Returns the path to the unified runtime configuration directory used by the stack.
 * This now points to ~/.datamancy per the data directory consolidation requirements.
 */
private fun runtimeConfigDir(): Path = datamancyDataDir()

/**
 * Ensures the unified runtime configuration directory exists and returns its path.
 * This now points to ~/.datamancy per the data directory consolidation requirements.
 */
private fun ensureRuntimeConfigDir(): Path = ensureDatamancyDataDir()

private fun ensurePerm(path: Path, executable: Boolean = false) {
    try {
        val perms = if (executable) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS; ignore
    }
}

private fun which(cmd: String): Boolean = try {
    run("which", cmd, allowFail = true)
    true
} catch (_: Exception) {
    false
}

private fun validateEnvFile(envFile: Path) {
    if (!Files.exists(envFile)) {
        err(".env file not found at: $envFile")
    }

    val content = Files.readString(envFile)

    // Check for placeholder values
    if (content.contains("<CHANGE_ME>")) {
        err(".env contains <CHANGE_ME> placeholders.\n" +
            "Generate secrets first:\n" +
            "  ./stack-controller.main.kts config generate")
    }

    // Validate required variables
    val requiredVars = listOf(
        "STACK_ADMIN_USER",
        "STACK_ADMIN_PASSWORD",
        "STACK_ADMIN_EMAIL",
        "DOMAIN",
        "MAIL_DOMAIN",
        "VOLUMES_ROOT"
    )

    val missing = mutableListOf<String>()
    requiredVars.forEach { varName ->
        if (!content.contains("$varName=") || content.contains("$varName=\\s*$".toRegex())) {
            missing.add(varName)
        }
    }

    if (missing.isNotEmpty()) {
        err("Missing required variables in .env: ${missing.joinToString(", ")}")
    }
}

private fun validateDomain(domain: String) {
    // Validate domain format (basic DNS name validation)
    val domainRegex = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$".toRegex()

    if (domain.isBlank()) {
        err("DOMAIN cannot be empty")
    }

    if (!domainRegex.matches(domain)) {
        err("Invalid DOMAIN format: $domain\n" +
            "Must be a valid DNS name (e.g., example.com, stack.local)")
    }

    if (domain.length > 253) {
        err("DOMAIN too long: ${domain.length} chars (max 253)")
    }
}

private fun checkDiskSpace(path: Path, requiredGB: Long = 50) {
    try {
        val store = Files.getFileStore(path)
        val availableGB = store.usableSpace / (1024 * 1024 * 1024)

        if (availableGB < requiredGB) {
            warn("Low disk space: ${availableGB}GB available (${requiredGB}GB recommended)")
            warn("Continuing in 3 seconds... (Ctrl+C to abort)")
            Thread.sleep(3000)
        }
    } catch (e: Exception) {
        warn("Could not check disk space: ${e.message}")
    }
}

// ============================================================================
// Stack Operations
// ============================================================================

/**
 * Starts the Datamancy stack with full automated setup.
 *
 * What it does:
 * 1. Generates environment configuration (.env.runtime) if missing
 * 2. Generates LDAP bootstrap file if missing
 * 3. Processes configuration templates into ~/.datamancy/configs if missing
 * 4. Creates required volume directories under ~/.datamancy/volumes if missing
 * 5. Starts all Docker Compose services using the generated env file
 *
 * Side effects:
 * - Creates and/or modifies files under ~/.datamancy
 * - Launches Docker containers defined by docker-compose.yml
 *
 * Error conditions:
 * - Missing critical environment variables during template processing
 * - Docker not installed or not running
 * - Insufficient permissions to create files or directories under the home directory
 */
private fun bringUpStack() {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val ldapBootstrap = dataDir.resolve("bootstrap_ldap.ldif")
    val configsDir = dataDir.resolve("configs")
    val volumesDir = dataDir.resolve("volumes")

    info("Starting Datamancy stack")
    info("Data directory: $dataDir")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(runtimeEnv)) {
        info("Step 1/5: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Step 1/5: Environment config exists, validating")
        validateEnvFile(runtimeEnv)
    }

    // Validate DOMAIN
    val envContent = Files.readString(runtimeEnv)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Generate LDAP bootstrap if needed
    if (!Files.exists(ldapBootstrap)) {
        info("Step 2/5: Generating LDAP bootstrap data")
        generateLdapBootstrap(dryRun = false, force = false)
    } else {
        info("Step 2/5: LDAP bootstrap exists")
    }

    // Step 3: Process configuration templates if needed
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L) {
        info("Step 3/5: Processing configuration templates")
        processConfigurationTemplates()
    } else {
        info("Step 3/5: Configuration files exist")
    }

    // Step 4: Create volume directories if needed
    // Always try to create volumes (createVolumeDirectories is idempotent and will skip existing ones)
    info("Step 4/5: Volume directories exist")
    createVolumeDirectories()

    // Step 5: Disk space check
    checkDiskSpace(volumesDir, requiredGB = 50)

    // Step 6: Start services
    info("Step 5/5: Starting Docker Compose services")
    val args = mutableListOf("docker", "compose", "--env-file", runtimeEnv.toString(), "up", "-d")
    run(*args.toTypedArray(), cwd = root)

    success("Stack started successfully")
    println()
    println("${ANSI_GREEN}Next steps:${ANSI_RESET}")
    println("1. Wait 2-3 minutes for services to initialize")
    println("2. Check service health: ./stack-controller status")
    println("3. View service URLs: ./stack-controller urls")
}

/**
 * Starts the Datamancy stack with specific profiles.
 *
 * Parameters:
 * - profiles: List of profile names to activate
 *
 * Side effects:
 * - Same as bringUpStack() but with profile-specific services
 */
private fun bringUpStackWithProfiles(profiles: List<String>) {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val ldapBootstrap = dataDir.resolve("bootstrap_ldap.ldif")
    val configsDir = dataDir.resolve("configs")
    val volumesDir = dataDir.resolve("volumes")

    info("Starting Datamancy stack with profiles: ${profiles.joinToString(", ")}")
    info("Data directory: $dataDir")
    println()

    // Step 1: Generate environment configuration if needed
    if (!Files.exists(runtimeEnv)) {
        info("Step 1/5: Generating environment configuration")
        generateEnvironmentConfig()
    } else {
        info("Step 1/5: Environment config exists, validating")
        validateEnvFile(runtimeEnv)
    }

    // Validate DOMAIN
    val envContent = Files.readString(runtimeEnv)
    val domainMatch = "DOMAIN=(.+)".toRegex().find(envContent)
    if (domainMatch != null) {
        val domain = domainMatch.groupValues[1].trim().removeSurrounding("\"", "'")
        validateDomain(domain)
    }

    // Step 2: Generate LDAP bootstrap if needed
    if (!Files.exists(ldapBootstrap)) {
        info("Step 2/5: Generating LDAP bootstrap data")
        generateLdapBootstrap(dryRun = false, force = false)
    } else {
        info("Step 2/5: LDAP bootstrap exists")
    }

    // Step 3: Process configuration templates if needed
    if (!Files.exists(configsDir) || Files.list(configsDir).count() == 0L) {
        info("Step 3/5: Processing configuration templates")
        processConfigurationTemplates()
    } else {
        info("Step 3/5: Configuration files exist")
    }

    // Step 4: Create volume directories if needed
    info("Step 4/5: Volume directories exist")
    createVolumeDirectories()

    // Step 5: Disk space check
    checkDiskSpace(volumesDir, requiredGB = 50)

    // Step 6: Start services with profiles
    info("Step 5/5: Starting Docker Compose services with profiles: ${profiles.joinToString(", ")}")
    val args = mutableListOf("docker", "compose", "--env-file", runtimeEnv.toString())
    profiles.forEach { profile ->
        args.add("--profile")
        args.add(profile)
    }
    args.addAll(listOf("up", "-d"))
    run(*args.toTypedArray(), cwd = root)

    success("Stack started successfully with profiles: ${profiles.joinToString(", ")}")
    println()
    println("${ANSI_GREEN}Next steps:${ANSI_RESET}")
    println("1. Wait 2-3 minutes for services to initialize")
    println("2. Check service health: ./stack-controller status")
    println("3. View service URLs: ./stack-controller urls")
}

/**
 * Stops all Datamancy stack services.
 *
 * What it does:
 * - Runs `docker compose down` using the runtime env file if available
 *
 * Side effects:
 * - Stops running containers but preserves volumes and data
 *
 * Error conditions:
 * - Docker not installed or user lacks permission to run docker commands
 */
private fun stopStack() {
    val root = projectRoot()
    val dataDir = datamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")

    info("Stopping stack")
    if (Files.exists(runtimeEnv)) {
        run("docker", "compose", "--env-file", runtimeEnv.toString(), "down", cwd = root)
    } else {
        run("docker", "compose", "down", cwd = root)
    }
    success("Stack stopped")
}

private fun cmdRecreate(cleanVolumes: Boolean = false, skipConfigs: Boolean = false) {
    val root = projectRoot()

    println("""
        |$ANSI_CYAN========================================
        |  Stack Recreate Workflow
        |========================================$ANSI_RESET
        |Clean volumes: $cleanVolumes
        |Regenerate configs: ${!skipConfigs}
        |
    """.trimMargin())

    // Step 1: Down
    info("Step 1/5: Stopping all services")
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    if (Files.exists(runtimeEnv)) {
        run("docker", "compose", "--env-file", runtimeEnv.toString(), "down", cwd = root)
    } else {
        run("docker", "compose", "down", cwd = root)
    }
    success("Services stopped")

    // Step 2: Clean volumes (optional)
    if (cleanVolumes) {
        warn("Step 2/5: Cleaning volumes (THIS WILL DELETE ALL DATA)")
        print("Are you sure? Type 'yes' to continue: ")
        val confirmation = readLine()?.trim()?.lowercase()
        if (confirmation != "yes") {
            err("Volume cleaning cancelled")
        }

        val volumesToClean = listOf("postgres_data", "ldap_data", "redis_data")

        if (volumesToClean.isNotEmpty()) {
            for (vol in volumesToClean) {
                val volPath = root.resolve("volumes/$vol")
                if (Files.exists(volPath)) {
                    info("Cleaning: $vol")
                    run("docker", "run", "--rm",
                        "-v", "$volPath:/data",
                        "alpine", "sh", "-c", "rm -rf /data/*")
                }
            }
            success("Volumes cleaned")
        } else {
            info("No volumes to clean")
        }
    } else {
        info("Step 2/5: Skipping volume cleaning (use --clean-volumes to enable)")
    }

    // Step 3: Regenerate configs
    if (!skipConfigs) {
        info("Step 3/5: Regenerating configuration files")
        cmdConfigProcess()
        success("Configs regenerated")
    } else {
        info("Step 3/5: Skipping config regeneration (use --no-skip-configs to enable)")
    }

    // Step 4: Bootstrap SSH known_hosts (optional, safe to ignore failures)
    info("Step 4/5: Bootstrapping SSH known_hosts")
    try {
        cmdSshBootstrap()
    } catch (e: Exception) {
        warn("SSH bootstrap failed (may be OK if SSH not needed): ${e.message}")
    }

    // Step 5: Up
    info("Step 5/5: Starting services")
    bringUpStack()

    println("""
        |
        |$ANSI_GREEN========================================
        |  Recreate Complete!
        |========================================$ANSI_RESET
        |
        |Check status: docker compose ps
        |View logs: docker compose logs -f
        |
    """.trimMargin())
}

/**
 * Restart a specific service by name using Docker Compose.
 *
 * Parameters:
 * - service: the Docker Compose service name to restart
 *
 * Side effects:
 * - Issues a restart to the target container(s)
 *
 * Error conditions:
 * - Service name not found in the compose file
 * - Docker command fails or daemon is unavailable
 */
private fun restartService(service: String) {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    info("Restarting service: $service")
    if (Files.exists(runtimeEnv)) {
        run("docker", "compose", "--env-file", runtimeEnv.toString(), "restart", service, cwd = root)
    } else {
        run("docker", "compose", "restart", service, cwd = root)
    }
    success("Service restarted: $service")
}

/**
 * Show logs for a specific service.
 *
 * Parameters:
 * - service: the Docker Compose service name
 * - follow: if true, follow the log output
 *
 * Side effects:
 * - Streams logs to stdout; with follow=true the command will not return until interrupted
 *
 * Error conditions:
 * - Service name invalid
 * - Docker command fails
 */
private fun showServiceLogs(service: String, follow: Boolean = false) {
    val root = projectRoot()
    val args = mutableListOf("docker", "compose", "logs")
    if (follow) args.add("-f")
    args.add(service)
    run(*args.toTypedArray(), cwd = root)
}

/**
 * Display the current status of all services in the stack.
 *
 * What it does:
 * - Prints `docker compose ps` table to stdout
 *
 * Error conditions:
 * - Docker not installed or not running
 */
private fun showStackStatus() {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    info("Stack status:")
    if (Files.exists(runtimeEnv)) {
        println(run("docker", "compose", "--env-file", runtimeEnv.toString(), "ps", cwd = root))
    } else {
        println(run("docker", "compose", "ps", cwd = root))
    }
}

private fun cmdHealth() {
    val root = projectRoot()
    info("Checking stack health...")

    // Get container status in JSON format
    val result = run("docker", "compose", "ps", "--format", "json", cwd = root, allowFail = true)

    if (result.trim().isEmpty()) {
        warn("No services running")
        exitProcess(0)
    }

    val lines = result.trim().lines().filter { it.isNotBlank() }
    var healthyCount = 0
    var unhealthyCount = 0
    val unhealthyServices = mutableListOf<String>()

    lines.forEach { line ->
        try {
            // Parse JSON line (basic parsing without JSON library)
            val service = line.substringAfter("\"Service\":\"").substringBefore("\"")
            val state = line.substringAfter("\"State\":\"").substringBefore("\"")
            val health = if (line.contains("\"Health\":\"")) {
                line.substringAfter("\"Health\":\"").substringBefore("\"")
            } else {
                "none"
            }

            when {
                state != "running" -> {
                    unhealthyCount++
                    unhealthyServices.add("$service: $state")
                }
                health == "healthy" || health == "none" -> {
                    healthyCount++
                }
                else -> {
                    unhealthyCount++
                    unhealthyServices.add("$service: $health")
                }
            }
        } catch (_: Exception) {
            // Skip unparseable lines
        }
    }

    println()
    if (unhealthyCount == 0) {
        success("All $healthyCount services healthy ✓")
        exitProcess(0)
    } else {
        warn("Health check failed:")
        warn("  Healthy:   $healthyCount")
        warn("  Unhealthy: $unhealthyCount")
        println()
        err("Unhealthy services:")
        unhealthyServices.forEach { println("  ✗ $it") }
        exitProcess(1)
    }
}

private fun cmdDiagnose(service: String? = null, tail: Int = 100, follow: Boolean = false) {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")

    if (service != null) {
        // Diagnose specific service
        info("Diagnosing service: $service")

        // Get container status
        val status = run("docker", "compose", "--env-file", runtimeEnv.toString(),
            "ps", service, "--format", "{{.Status}}", cwd = root, allowFail = true)
        println("${ANSI_CYAN}Status:${ANSI_RESET} $status")

        // Get logs
        println("\n${ANSI_CYAN}Recent logs (last $tail lines):${ANSI_RESET}")
        val logsCmd = mutableListOf("docker", "compose", "--env-file", runtimeEnv.toString(),
            "logs", "--tail", tail.toString())
        if (follow) logsCmd.add("-f")
        logsCmd.add(service)
        run(*logsCmd.toTypedArray(), cwd = root, allowFail = true)
    } else {
        // Diagnose all unhealthy services
        info("Diagnosing all unhealthy services...")

        val result = run("docker", "compose", "--env-file", runtimeEnv.toString(),
            "ps", "--format", "json", cwd = root, allowFail = true)

        val lines = result.trim().lines().filter { it.isNotBlank() }
        val unhealthyServices = mutableListOf<String>()

        lines.forEach { line ->
            try {
                val svc = line.substringAfter("\"Service\":\"").substringBefore("\"")
                val state = line.substringAfter("\"State\":\"").substringBefore("\"")
                val health = if (line.contains("\"Health\":\"")) {
                    line.substringAfter("\"Health\":\"").substringBefore("\"")
                } else {
                    "none"
                }

                if (state != "running" || (health != "healthy" && health != "none")) {
                    unhealthyServices.add(svc)
                }
            } catch (_: Exception) {}
        }

        if (unhealthyServices.isEmpty()) {
            success("All services are healthy!")
            return
        }

        unhealthyServices.forEach { svc ->
            println("\n$ANSI_YELLOW═══════════════════════════════════════$ANSI_RESET")
            println("${ANSI_YELLOW}Service: $svc$ANSI_RESET")
            println("$ANSI_YELLOW═══════════════════════════════════════$ANSI_RESET")

            val status = run("docker", "compose", "--env-file", runtimeEnv.toString(),
                "ps", svc, "--format", "{{.Status}}", cwd = root, allowFail = true)
            println("${ANSI_CYAN}Status:${ANSI_RESET} $status")

            println("\n${ANSI_CYAN}Last $tail log lines:${ANSI_RESET}")
            run("docker", "compose", "--env-file", runtimeEnv.toString(),
                "logs", "--tail", tail.toString(), svc, cwd = root, allowFail = true)
        }
    }
}

private fun cmdTestIterate(maxIterations: Int = 5) {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val ldapBootstrap = dataDir.resolve("bootstrap_ldap.ldif")
    val configsDir = dataDir.resolve("configs")
    val volumesDir = dataDir.resolve("volumes")

    println("""
        |$ANSI_CYAN========================================
        |  Iterative Stack Testing
        |========================================$ANSI_RESET
        |Max iterations: $maxIterations
        |
        |This will:
        |1. Check stack health
        |2. Collect logs from unhealthy services
        |3. Suggest fixes
        |4. Wait for user to apply fixes
        |5. Regenerate configs and restart
        |6. Repeat until healthy or max iterations
        |
    """.trimMargin())

    for (iteration in 1..maxIterations) {
        println("\n$ANSI_GREEN╔════════════════════════════════════╗$ANSI_RESET")
        println("$ANSI_GREEN║  Iteration $iteration of $maxIterations                  ║$ANSI_RESET")
        println("$ANSI_GREEN╚════════════════════════════════════╝$ANSI_RESET\n")

        // Check health
        info("Checking stack health...")
        val healthCheck = run("docker", "compose", "ps", "--format", "json", cwd = root, allowFail = true)
        val lines = healthCheck.trim().lines().filter { it.isNotBlank() }
        val unhealthyServices = mutableListOf<String>()

        lines.forEach { line ->
            try {
                val svc = line.substringAfter("\"Service\":\"").substringBefore("\"")
                val state = line.substringAfter("\"State\":\"").substringBefore("\"")
                val health = if (line.contains("\"Health\":\"")) {
                    line.substringAfter("\"Health\":\"").substringBefore("\"")
                } else {
                    "none"
                }

                if (state != "running" || (health != "healthy" && health != "none" && health != "starting")) {
                    unhealthyServices.add(svc)
                }
            } catch (_: Exception) {}
        }

        if (unhealthyServices.isEmpty()) {
            success("All services healthy! Testing complete.")
            return
        }

        warn("Found ${unhealthyServices.size} unhealthy service(s): ${unhealthyServices.joinToString(", ")}")

        // Diagnose each unhealthy service
        cmdDiagnose()

        if (iteration < maxIterations) {
            println("\n$ANSI_YELLOW========================================$ANSI_RESET")
            println("${ANSI_YELLOW}Action required:$ANSI_RESET")
            println("1. Review logs above")
            println("2. Fix issues in templates or docker-compose.yml")
            println("3. Press Enter to regenerate configs and restart")
            println("   Or type 'skip' to continue without restart")
            println("   Or type 'quit' to exit")
            print("\n${ANSI_CYAN}Your choice:${ANSI_RESET} ")

            val choice = readLine()?.trim()?.lowercase()
            when (choice) {
                "quit" -> {
                    info("Exiting test iteration")
                    return
                }
                "skip" -> {
                    info("Skipping restart, continuing to next iteration")
                    Thread.sleep(10000) // Wait 10s for services to start
                }
                else -> {
                    info("Regenerating configs and restarting affected services...")
                    cmdConfigProcess()
                    unhealthyServices.forEach { svc ->
                        info("Restarting: $svc")
                        restartService(svc)
                    }
                    info("Waiting 15 seconds for services to start...")
                    Thread.sleep(15000)
                }
            }
        }
    }

    warn("Max iterations reached. Some services may still be unhealthy.")
    info("Run './stack-controller diagnose' to see remaining issues")
}

private fun cmdQuickStart() {
    val root = projectRoot()
    val dataDir = ensureDatamancyDataDir()
    val runtimeEnv = dataDir.resolve(".env.runtime")
    val ldapBootstrap = dataDir.resolve("bootstrap_ldap.ldif")
    val configsDir = dataDir.resolve("configs")
    val volumesDir = dataDir.resolve("volumes")

    println("""
        |$ANSI_CYAN========================================
        |  Datamancy Quick Start
        |========================================$ANSI_RESET
        |This will perform a complete initial setup:
        |1. Generate runtime configuration
        |2. Bootstrap LDAP
        |3. Process config templates
        |4. Create volume directories
        |5. Start all services
        |
    """.trimMargin())

    print("${ANSI_YELLOW}Continue? (y/N):${ANSI_RESET} ")
    val response = readLine()?.trim()?.lowercase()
    if (response != "y" && response != "yes") {
        info("Cancelled")
        return
    }

    info("Step 1/5: Generating runtime configuration...")
    cmdConfigGenerate()

    info("Step 2/5: Bootstrapping LDAP...")
    cmdLdapBootstrap()

    info("Step 3/5: Processing config templates...")
    cmdConfigProcess()

    info("Step 4/5: Creating volume directories...")
    cmdVolumesCreate()

    info("Step 5/5: Starting all services...")
    bringUpStack()

    success("Quick start complete!")
    println("\n${ANSI_GREEN}Next steps:$ANSI_RESET")
    println("1. Wait 2-3 minutes for services to initialize")
    println("2. Check service health: ./stack-controller health")
    println("3. View service URLs: ./stack-controller urls")
    println("4. Access services via https://servicename.yourdomain.com")
}

private fun cmdShowUrls() {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")

    if (!Files.exists(runtimeEnv)) {
        err("Runtime config not found. Run: ./stack-controller config generate")
    }

    // Load DOMAIN from env
    val envContent = Files.readString(runtimeEnv)
    val domain = envContent.lines()
        .find { it.startsWith("DOMAIN=") }
        ?.substringAfter("DOMAIN=")
        ?.trim()
        ?.trim('"')
        ?: "yourdomain.com"

    println("""
        |$ANSI_CYAN========================================
        |  Service URLs
        |========================================$ANSI_RESET
        |Domain: $domain
        |
        |${ANSI_GREEN}🔐 Infrastructure${ANSI_RESET}
        |  Auth/SSO:     https://auth.$domain
        |  Caddy:        https://$domain (reverse proxy)
        |  Portainer:    https://portainer.$domain
        |  Adminer:      https://adminer.$domain
        |  pgAdmin:      https://pgadmin.$domain
        |
        |${ANSI_GREEN}📊 Monitoring & AI${ANSI_RESET}
        |  Grafana:      https://grafana.$domain
        |  Open WebUI:   https://open-webui.$domain
        |  LiteLLM:      https://litellm.$domain
        |  Homepage:     https://homepage.$domain
        |
        |${ANSI_GREEN}📝 Productivity${ANSI_RESET}
        |  Bookstack:    https://bookstack.$domain
        |  Planka:       https://planka.$domain
        |  Seafile:      https://seafile.$domain
        |  OnlyOffice:   https://onlyoffice.$domain
        |  JupyterHub:   https://jupyterhub.$domain
        |
        |${ANSI_GREEN}💬 Communication${ANSI_RESET}
        |  Matrix:       https://matrix.$domain
        |  Mastodon:     https://mastodon.$domain (if enabled)
        |  Mail (SOGo):  https://sogo.$domain
        |  Mailu Admin:  https://mail.$domain/admin
        |
        |${ANSI_GREEN}🔒 Security & Secrets${ANSI_RESET}
        |  Vaultwarden:  https://vaultwarden.$domain
        |  LDAP Manager: https://lam.$domain
        |
        |${ANSI_GREEN}🏠 Home Automation${ANSI_RESET}
        |  Home Assistant: https://homeassistant.$domain
        |
        |${ANSI_YELLOW}Note:${ANSI_RESET} All services use Authelia SSO (except Mailu)
        |Default credentials: Check .env.runtime for STACK_ADMIN_USER/PASSWORD
        |
    """.trimMargin())
}

// ============================================================================
// Configuration Commands
// ============================================================================

/**
 * Processes configuration templates to generate runtime configuration files.
 * Sources: configs.templates → Target: ~/.datamancy/configs
 * Idempotent: overwrites existing files safely when --force is used.
 */
private fun cmdConfigProcess() {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")

    info("Processing configuration templates")
    info("Output: $runtimeDir/configs")

    val script = root.resolve("scripts/core/process-config-templates.main.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/process-config-templates.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), "--force", "--output=$runtimeDir/configs", "--env=$runtimeEnv", cwd = root)
        } else {
            err("process-config-templates.main.kts not found")
        }
    } else {
        run("kotlin", script.toString(), "--force", "--output=$runtimeDir/configs", "--env=$runtimeEnv", cwd = root)
    }

    success("Configuration templates processed")
    info("Configs location: $runtimeDir/configs")

    // Setup application-specific init scripts
    info("Setting up application init scripts")
    val bookstackInitScript = root.resolve("scripts/core/setup-bookstack-init.sh")
    if (Files.exists(bookstackInitScript)) {
        run("bash", bookstackInitScript.toString(), cwd = root)
    }
}

/**
 * Generates the environment configuration (.env.runtime) under ~/.datamancy.
 * Idempotent: will overwrite the previous file with new values.
 */
private fun cmdConfigGenerate() {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")

    info("Generating .env from defaults")
    info("Output: $runtimeEnv")

    val script = root.resolve("scripts/core/configure-environment.kts")

    if (!Files.exists(script)) {
        warn("Script not found at new location, trying old location...")
        val oldScript = root.resolve("scripts/configure-environment.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), "export", cwd = root)
        } else {
            err("configure-environment.kts not found")
        }
    } else {
        run("kotlin", script.toString(), "export", cwd = root)
    }

    // Script writes to .env in project root, move it to runtime location
    val projectEnv = root.resolve(".env")
    if (Files.exists(projectEnv)) {
        Files.move(projectEnv, runtimeEnv, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ensurePerm(runtimeEnv, executable = false)
    } else {
        err("Script did not generate .env file")
    }

    success("Environment configuration generated")
    info("Location: $runtimeEnv")
    info("Note: OAuth client secret hashes are set to PENDING")
    info("Run './stack-controller hash-oidc' after starting Authelia to generate hashes")
    info("Ready to use with: ./stack-controller up")
}

private fun cmdHashOidc() {
    val root = projectRoot()
    val script = root.resolve("scripts/security/generate-oidc-hashes.main.kts")

    if (!Files.exists(script)) {
        err("Hash generation script not found at: $script")
    }

    info("Generating OAuth/OIDC client secret hashes for Authelia")
    run("kotlin", script.toString(), cwd = root)
    success("Hashes generated successfully")
    info("Run './stack-controller config process' to apply changes to Authelia config")
}

// ============================================================================
// Deployment Commands (SSH-based, from stackops)
// ============================================================================

private fun cmdDeployCreateUser() {
    if (!isRoot()) err("This command must be run with sudo/root")
    info("Creating system user 'stackops'")
    run("bash", "-lc", "id -u stackops >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin stackops")
    run("bash", "-lc", "id -nG stackops | grep -q \\\"\\bdocker\\b\\\" || usermod -aG docker stackops")
    run("bash", "-lc", "install -d -m 0700 -o stackops -g stackops /home/stackops/.ssh")
    success("User 'stackops' created and added to docker group")
}

private fun cmdDeployInstallWrapper() {
    if (!isRoot()) err("This command must be run with sudo/root")
    val wrapperPath = Paths.get("/usr/local/bin/stackops-wrapper")
    info("Installing SSH forced-command wrapper")

    val script = """
        |#!/usr/bin/env bash
        |set -euo pipefail
        |
        |# Stackops SSH Command Wrapper with SOPS Integration
        |# Allows remote management of Docker services and LDAP sync
        |ALLOWED_CMDS=(
        |  "docker ps"
        |  "docker logs"
        |  "docker restart"
        |  "docker compose"
        |  "./stack-controller.main.kts"
        |  "kotlin stack-controller.main.kts"
        |)
        |
        |REQ="${'$'}{SSH_ORIGINAL_COMMAND:-}"
        |if [[ -z "${'$'}REQ" ]]; then
        |  echo "No command specified" >&2
        |  exit 1
        |fi
        |
        |# Normalize command
        |NORM="${'$'}(echo "${'$'}REQ" | tr -s ' ')"
        |
        |# Check allowlist
        |ALLOWED=false
        |for allowed in "${'$'}{ALLOWED_CMDS[@]}"; do
        |  if [[ "${'$'}NORM" == "${'$'}allowed"* ]]; then
        |    ALLOWED=true
        |    break
        |  fi
        |done
        |
        |if [[ "${'$'}ALLOWED" != "true" ]]; then
        |  echo "Command not allowed: ${'$'}NORM" >&2
        |  echo "" >&2
        |  echo "Allowed commands:" >&2
        |  printf '  %s\n' "${'$'}{ALLOWED_CMDS[@]}" >&2
        |  exit 1
        |fi
        |
        |# Auto-decrypt secrets if needed
        |PROJECT_ROOT="/home/stackops/datamancy"
        |if [[ -f "${'$'}PROJECT_ROOT/.env.enc" && ! -f "${'$'}PROJECT_ROOT/.env" ]]; then
        |  if [[ -f /home/stackops/.config/sops/age/keys.txt ]]; then
        |    sops -d --input-type dotenv --output-type dotenv "${'$'}PROJECT_ROOT/.env.enc" > "${'$'}PROJECT_ROOT/.env"
        |    chmod 600 "${'$'}PROJECT_ROOT/.env"
        |  fi
        |fi
        |
        |cd "${'$'}PROJECT_ROOT"
        |exec bash -lc -- "${'$'}NORM"
    """.trimMargin()

    Files.writeString(wrapperPath, script)
    ensurePerm(wrapperPath, executable = true)
    success("Wrapper installed at $wrapperPath")
    println("\nTo activate, add to ~/.ssh/authorized_keys:")
    println("command=\"/usr/local/bin/stackops-wrapper\",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ssh-ed25519 AAAA...")
}

private fun cmdDeployHardenSshd() {
    if (!isRoot()) err("This command must be run with sudo/root")
    info("Hardening SSH daemon configuration")

    val cfg = Paths.get("/etc/ssh/sshd_config")
    if (!Files.isRegularFile(cfg)) err("sshd_config not found")

    val backup = cfg.resolveSibling("sshd_config.bak")
    if (!Files.exists(backup)) {
        Files.copy(cfg, backup)
        info("Backup created: $backup")
    }

    fun applyKv(key: String, value: String) {
        val lines = Files.readAllLines(cfg).toMutableList()
        var found = false
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith(key, ignoreCase = true)) {
                lines[i] = "$key $value"
                found = true
            }
        }
        if (!found) lines.add("$key $value")
        Files.write(cfg, lines)
    }

    applyKv("PubkeyAuthentication", "yes")
    applyKv("PasswordAuthentication", "no")
    applyKv("PermitTunnel", "no")
    applyKv("PermitTTY", "no")

    run("systemctl", "reload", "sshd", allowFail = true)
    success("SSH daemon hardened")
}

private fun cmdDeployGenerateKeys() {
    val root = projectRoot()
    val secretsDir = root.resolve("volumes/secrets")
    Files.createDirectories(secretsDir)

    val privateKey = secretsDir.resolve("stackops_ed25519")
    if (Files.exists(privateKey)) {
        info("SSH key already exists")
        println(run("ssh-keygen", "-lf", privateKey.toString()))
        return
    }

    info("Generating ed25519 keypair")
    run("ssh-keygen", "-t", "ed25519", "-f", privateKey.toString(), "-C", "stackops@datamancy", "-N", "")
    ensurePerm(privateKey, executable = false)
    success("SSH keypair generated at: $privateKey")
}


// ============================================================================
// Maintenance Commands
// ============================================================================

/**
 * Creates the Docker volume directory structure under ~/.datamancy/volumes.
 * Parses docker-compose.yml for ${VOLUMES_ROOT} paths and creates directories.
 * Idempotent: existing directories are left untouched.
 */
private fun cmdVolumesCreate() {
    val root = projectRoot()
    info("Creating volume directory structure")
    val script = root.resolve("scripts/core/create-volume-dirs.main.kts")

    if (!Files.exists(script)) {
        val oldScript = root.resolve("scripts/create-volume-dirs.main.kts")
        if (Files.exists(oldScript)) {
            run("kotlin", oldScript.toString(), cwd = root)
        } else {
            err("create-volume-dirs.main.kts not found")
        }
    } else {
        run("kotlin", script.toString(), cwd = root)
    }
    success("Volume directories created")
}

private fun cmdSshBootstrap() {
    val root = projectRoot()
    val knownHostsFile = root.resolve("volumes/agent_tool_server/known_hosts")
    val sshHost = System.getenv("TOOLSERVER_SSH_HOST") ?: "host.docker.internal"

    info("Bootstrapping SSH known_hosts for agent-tool-server")
    info("Scanning SSH keys from: $sshHost")

    // Ensure parent directory exists
    Files.createDirectories(knownHostsFile.parent)

    // Remove if it's a directory (common mistake)
    if (Files.isDirectory(knownHostsFile)) {
        info("Removing incorrectly created directory: $knownHostsFile")
        run("docker", "run", "--rm",
            "-v", "${knownHostsFile}:/data",
            "alpine", "sh", "-c", "rm -rf /data")
    }

    // Scan SSH host keys
    val tmpFile = Files.createTempFile("known_hosts", ".tmp")
    try {
        val keys = run("ssh-keyscan", "-H", "-t", "rsa,ecdsa,ed25519", sshHost, allowFail = true)
        if (keys.trim().isEmpty()) {
            warn("No SSH keys found for $sshHost - service may not be accessible")
            warn("This is OK if agent-tool-server doesn't need SSH access")
            // Create empty file so volume mount works
            Files.writeString(knownHostsFile, "")
        } else {
            Files.writeString(tmpFile, keys)
            Files.copy(tmpFile, knownHostsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ensurePerm(knownHostsFile, executable = false)
            success("SSH known_hosts created: $knownHostsFile")
            println("Keys captured: ${keys.lines().filter { it.isNotBlank() }.size}")
        }
    } finally {
        Files.deleteIfExists(tmpFile)
    }
}

private fun cmdVolumesClean() {
    val root = projectRoot()
    val volumesPath = root.resolve("volumes").toString()

    info("Cleaning volumes directory (removing root-owned files)")

    // Get current user UID and GID
    val uid = run("id", "-u").trim()
    val gid = run("id", "-g").trim()

    // Use a Docker container to clean and fix ownership
    // This works because Docker has permission to manipulate files in bind mounts
    run(
        "docker", "run", "--rm",
        "-v", "$volumesPath:/volumes",
        "alpine",
        "sh", "-c",
        "rm -rf /volumes/* && chown -R $uid:$gid /volumes"
    )

    success("Volumes directory cleaned and ownership fixed")
    info("You can now run: ./stack-controller volumes create")
}

private fun cmdCleanDocker() {
    info("Cleaning unused Docker resources")
    run("docker", "system", "prune", "-af", "--volumes")
    success("Docker cleanup complete")
}

/**
 * Performs a complete cleanup of the Datamancy stack.
 *
 * What it does:
 * 1. Stops all containers
 * 2. Removes all Docker volumes (requires Docker to remove bind mounts created as root)
 * 3. Removes networks
 * 4. Deletes ~/.datamancy directory
 *
 * WARNING: This is destructive and will delete ALL data.
 *
 * @param force if true, skip confirmation prompt
 */
private fun cmdObliterate(force: Boolean = false) {
    val root = projectRoot()
    val dataDir = datamancyDataDir()

    println("""
        |${ANSI_RED}╔═══════════════════════════════════════════════════╗
        |║  ⚠️  NUCLEAR OPTION - COMPLETE STACK CLEANUP  ⚠️  ║
        |╚═══════════════════════════════════════════════════╝${ANSI_RESET}
        |
        |${ANSI_YELLOW}This will PERMANENTLY DELETE:${ANSI_RESET}
        |  • All Docker containers
        |  • All Docker volumes (including databases)
        |  • All Docker networks
        |  • Entire ~/.datamancy directory
        |  • All configuration files
        |  • All data (postgres, mariadb, ldap, etc.)
        |
        |${ANSI_RED}THIS CANNOT BE UNDONE!${ANSI_RESET}
        |
    """.trimMargin())

    if (!force) {
        print("${ANSI_YELLOW}Type 'OBLITERATE' (all caps) to confirm: ${ANSI_RESET}")
        val confirmation = readLine()?.trim()
        if (confirmation != "OBLITERATE") {
            info("Cleanup cancelled")
            return
        }
        println()
    }

    info("Step 1/4: Stopping all containers")
    try {
        val runtimeEnv = dataDir.resolve(".env.runtime")
        if (Files.exists(runtimeEnv)) {
            run("docker", "compose", "--env-file", runtimeEnv.toString(), "down", "-v", cwd = root, allowFail = true)
        } else {
            run("docker", "compose", "down", "-v", cwd = root, allowFail = true)
        }
        success("Containers stopped")
    } catch (e: Exception) {
        warn("Failed to stop containers gracefully: ${e.message}")
    }

    info("Step 2/4: Removing Docker volumes")
    try {
        // List all datamancy volumes
        val volumes = run("docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true)
        val volumeList = volumes.trim().lines().filter { it.isNotBlank() }

        if (volumeList.isNotEmpty()) {
            info("Found ${volumeList.size} volumes to remove")
            for (volume in volumeList) {
                try {
                    run("docker", "volume", "rm", volume, allowFail = true)
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $volume")
                } catch (e: Exception) {
                    warn("  Failed to remove volume: $volume")
                }
            }
        } else {
            info("No Docker volumes found")
        }
        success("Docker volumes removed")
    } catch (e: Exception) {
        warn("Failed to remove volumes: ${e.message}")
    }

    info("Step 3/4: Removing Docker networks")
    try {
        val networks = run("docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=datamancy", allowFail = true)
        val networkList = networks.trim().lines().filter { it.isNotBlank() }

        if (networkList.isNotEmpty()) {
            for (network in networkList) {
                try {
                    run("docker", "network", "rm", network, allowFail = true)
                    println("  ${ANSI_GREEN}✓${ANSI_RESET} Removed: $network")
                } catch (e: Exception) {
                    warn("  Failed to remove network: $network")
                }
            }
        }
        success("Docker networks removed")
    } catch (e: Exception) {
        warn("Failed to remove networks: ${e.message}")
    }

    info("Step 4/4: Removing ~/.datamancy directory")
    try {
        if (Files.exists(dataDir)) {
            // Use docker to remove any root-owned files
            val dataDirStr = dataDir.toString()
            try {
                run("docker", "run", "--rm",
                    "-v", "$dataDirStr:/data",
                    "alpine", "rm", "-rf", "/data",
                    allowFail = true)
            } catch (e: Exception) {
                warn("Docker cleanup failed, trying direct removal: ${e.message}")
            }

            // Clean up any remaining files
            if (Files.exists(dataDir)) {
                dataDir.toFile().deleteRecursively()
            }

            success("Data directory removed")
        } else {
            info("Data directory doesn't exist")
        }
    } catch (e: Exception) {
        warn("Failed to remove data directory: ${e.message}")
        warn("You may need to manually remove: $dataDir")
    }

    println("""
        |
        |${ANSI_GREEN}╔════════════════════════════════════╗
        |║  Cleanup Complete!                ║
        |╚════════════════════════════════════╝${ANSI_RESET}
        |
        |Stack has been completely removed.
        |To start fresh, run: ./stack-controller up
        |
    """.trimMargin())
}

private fun cmdLdapSync() {
    val root = projectRoot()
    val runtimeDir = runtimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    info("Syncing LDAP users to services")
    if (Files.exists(runtimeEnv)) {
        run("docker", "compose", "--env-file", runtimeEnv.toString(), "run", "--rm", "ldap-sync-service", "sync", cwd = root)
    } else {
        run("docker", "compose", "run", "--rm", "ldap-sync-service", "sync", cwd = root)
    }
    success("LDAP sync complete")
}

/**
 * Generates the LDAP bootstrap LDIF file under ~/.datamancy/bootstrap_ldap.ldif.
 * Uses passwords and settings from ~/.datamancy/.env.runtime.
 *
 * Parameters:
 * - dryRun: if true, previews the generated content without writing
 * - force: if true, overwrites an existing bootstrap file
 */
private fun cmdLdapBootstrap(dryRun: Boolean = false, force: Boolean = false) {
    val root = projectRoot()
    val runtimeDir = ensureRuntimeConfigDir()
    val runtimeEnv = runtimeDir.resolve(".env.runtime")
    val outputFile = runtimeDir.resolve("bootstrap_ldap.ldif")

    info("Generating LDAP bootstrap file from template")
    info("Output: $outputFile")

    val script = root.resolve("scripts/core/generate-ldap-bootstrap.main.kts")

    if (!Files.exists(script)) {
        err("LDAP bootstrap generator not found: $script")
    }

    val args = mutableListOf("kotlin", script.toString(), "--output=$outputFile", "--env=$runtimeEnv")
    if (dryRun) args.add("--dry-run")
    if (force) args.add("--force")
    run(*args.toTypedArray(), cwd = root)
    success("LDAP bootstrap file generated")
    info("File location: $outputFile")
}

// ============================================================================
// Descriptive API wrappers (for new function names)
// ============================================================================

/**
 * Creates ~/.datamancy/.env.runtime by invoking the environment configurator.
 *
 * What it does:
 * - Runs scripts/core/configure-environment.kts to export a .env file, then
 *   moves it to ~/.datamancy/.env.runtime with secure permissions.
 *
 * Side effects:
 * - Creates ~/.datamancy directory if missing
 * - Writes/overwrites ~/.datamancy/.env.runtime
 *
 * Error conditions:
 * - Environment configurator script missing or fails
 * - Unable to write to the user home directory
 */
private fun generateEnvironmentConfig() = cmdConfigGenerate()

/**
 * Processes templates under configs.templates/ into ~/.datamancy/configs.
 *
 * What it does:
 * - Loads variables from ~/.datamancy/.env.runtime
 * - Renders templates from configs.templates/ to ~/.datamancy/configs
 *
 * Side effects:
 * - Creates or overwrites files under ~/.datamancy/configs
 *
 * Error conditions:
 * - Missing critical variables in .env.runtime
 * - Template processor script missing or fails
 */
private fun processConfigurationTemplates() = cmdConfigProcess()

/**
 * Generates ~/.datamancy/bootstrap_ldap.ldif using the LDAP template.
 *
 * Parameters:
 * - dryRun: preview output without writing
 * - force: overwrite existing file if present
 *
 * Side effects:
 * - Writes/overwrites ~/.datamancy/bootstrap_ldap.ldif
 *
 * Error conditions:
 * - Template file missing
 * - Missing required environment variables such as STACK_ADMIN_USER, DOMAIN
 */
private fun generateLdapBootstrap(dryRun: Boolean = false, force: Boolean = false) = cmdLdapBootstrap(dryRun, force)

/**
 * Creates the volume directories under ~/.datamancy/volumes as required by docker-compose.yml.
 *
 * What it does:
 * - Scans docker-compose.yml for ${'$'}{VOLUMES_ROOT} mounts and ensures host directories exist
 *
 * Side effects:
 * - Creates directories under ~/.datamancy/volumes (or VOLUMES_ROOT if set)
 *
 * Error conditions:
 * - docker-compose.yml missing
 * - Insufficient permissions to create directories
 */
private fun createVolumeDirectories() = cmdVolumesCreate()

// ============================================================================
// Help & Main
// ============================================================================

private fun showHelp() {
    println("""
        |Datamancy Stack Controller
        |
        |Usage: ./stack-controller <command> [options]
        |
        |📚 Documentation:
        |  README.md                      Quick start guide
        |  docs/DEPLOYMENT.md             Complete deployment guide with troubleshooting
        |  docs/STACK_CONTROLLER_GUIDE.md This CLI reference (detailed)
        |  docs/ARCHITECTURE.md           System architecture
        |  docs/SECURITY.md               Security setup
        |
        |Stack Operations:
        |  up [--profiles=p1,p2]   Start stack with full automated setup
        |                          Profiles: applications, bootstrap, databases, infrastructure
        |  down                    Stop all services
        |  recreate [options]      Full recreate: down → clean → regenerate → up
        |    --clean-volumes         Clean volumes (DELETES DATA - prompts for confirmation)
        |    --skip-configs          Skip config regeneration
        |  restart <service>       Restart a service
        |  logs <service> [-f]     View service logs (-f to follow)
        |  status                  Show stack status
        |  health                  Check health of all services (exit 1 if any unhealthy)
        |  ps                      Alias for 'status'
        |
        |Diagnostics & Testing:
        |  diagnose [service]      Show logs and status for unhealthy services
        |    --tail=N                Lines of logs to show (default: 100)
        |    -f, --follow            Follow log output
        |  test-iterate [--max=N]  Iterative testing: check→diagnose→fix→restart→repeat
        |  test-all                Check health of all services (summary report)
        |  full-deploy             Complete deployment: generate→bootstrap→process→up
        |  quick-start             Complete initial setup (generate→bootstrap→up)
        |  urls                    Display all service URLs with domain
        |
        |Configuration:
        |  config generate         Generate .env.runtime in ~/.datamancy
        |  config process          Process templates to ~/.datamancy/configs
        |
        |Deployment (requires sudo):
        |  deploy create-user      Create stackops system user
        |  deploy install-wrapper  Install SSH forced-command wrapper
        |  deploy harden-sshd      Harden SSH daemon configuration
        |  deploy generate-keys    Generate SSH keypair for agent-tool-server
        |
        |Maintenance:
        |  volumes create          Create volume directory structure
        |  volumes clean           Clean volumes directory (fix root ownership)
        |  ssh bootstrap           Bootstrap SSH known_hosts for agent-tool-server
        |  clean docker            Clean unused Docker resources
        |  ldap sync               Sync LDAP users to services
        |  ldap bootstrap          Generate LDAP bootstrap in ~/.datamancy
        |
        |Destructive Operations:
        |  obliterate [--force]          COMPLETE CLEANUP - removes all data, volumes, and ~/.datamancy
        |                          Requires typing 'OBLITERATE' to confirm (unless --force)
        |
        |Quick Start (Development):
        |  ./stack-controller up                  # End-to-end automated setup
        |  ./stack-controller status              # Check services
        |
        |Examples:
        |  ./stack-controller diagnose            # Show all unhealthy services
        |  ./stack-controller diagnose bookstack  # Diagnose specific service
        |  ./stack-controller test-iterate        # Iterative fix workflow
        |  ./stack-controller restart caddy
        |  sudo ./stack-controller deploy create-user
        |
        |Note: All runtime configs stored in ~/.datamancy (outside git tree)
        |Note: Docker Compose uses --env-file flag (no .env symlink needed)
        |
        |For detailed troubleshooting, see: docs/DEPLOYMENT.md#troubleshooting
    """.trimMargin())
}

// Main
if (args.isEmpty()) {
    showHelp()
    exitProcess(0)
}

when (args[0]) {
    // Stack operations
    "up" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        // Check for --profiles argument
        val profilesArg = args.find { it.startsWith("--profiles=") }
        if (profilesArg != null) {
            val profiles = profilesArg.substringAfter("=").split(",").map { it.trim() }
            info("Starting stack with profiles: ${profiles.joinToString(", ")}")
            bringUpStackWithProfiles(profiles)
        } else {
            bringUpStack()
        }
    }
    "ps" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        showStackStatus()
    }
    "down" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        stopStack()
    }
    "recreate" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val cleanVolumes = args.contains("--clean-volumes")
        val skipConfigs = args.contains("--skip-configs")
        cmdRecreate(cleanVolumes, skipConfigs)
    }
    "restart" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val service = args.getOrNull(1) ?: err("Service name required")
        restartService(service)
    }
    "logs" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val service = args.getOrNull(1) ?: err("Service name required")
        val follow = args.contains("-f") || args.contains("--follow")
        showServiceLogs(service, follow)
    }
    "status" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        showStackStatus()
    }
    "health" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        cmdHealth()
    }
    "diagnose" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val service = args.getOrNull(1)?.takeIf { !it.startsWith("--") && !it.startsWith("-") }
        val tail = args.find { it.startsWith("--tail=") }?.substringAfter("=")?.toIntOrNull() ?: 100
        val follow = args.contains("-f") || args.contains("--follow")
        cmdDiagnose(service, tail, follow)
    }
    "test-iterate" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val maxIterations = args.find { it.startsWith("--max=") }?.substringAfter("=")?.toIntOrNull() ?: 5
        cmdTestIterate(maxIterations)
    }
    "full-deploy" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        info("Running full deployment workflow...")
        cmdConfigGenerate()
        cmdLdapBootstrap()
        cmdConfigProcess()
        cmdVolumesCreate()
        bringUpStack()
        success("Full deployment complete!")
    }
    "test-all" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        val root = projectRoot()
        val runtimeDir = runtimeConfigDir()
        val runtimeEnv = runtimeDir.resolve(".env.runtime")

        info("Testing all services...")

        // Use table format instead of JSON for more reliable parsing
        val result = if (Files.exists(runtimeEnv)) {
            run("docker", "compose", "--env-file", runtimeEnv.toString(), "ps", "--format", "table {{.Service}}\t{{.State}}\t{{.Health}}", cwd = root, allowFail = true)
        } else {
            run("docker", "compose", "ps", "--format", "table {{.Service}}\t{{.State}}\t{{.Health}}", cwd = root, allowFail = true)
        }

        if (result.trim().isEmpty()) {
            warn("No services running")
            exitProcess(1)
        }

        val lines = result.trim().lines().filter { it.isNotBlank() }.drop(1)  // Skip header
        var healthyCount = 0
        var unhealthyCount = 0
        var noHealthcheckCount = 0
        val unhealthyServices = mutableListOf<String>()

        lines.forEach { line ->
            try {
                // Split by whitespace to handle table format
                val parts = line.split(Regex("\\s+"), limit = 3)
                if (parts.size >= 2) {
                    val service = parts[0]
                    val state = parts[1]
                    val health = if (parts.size >= 3) parts[2] else ""

                    when {
                        state != "running" -> {
                            unhealthyCount++
                            unhealthyServices.add("$service: $state")
                        }
                        health == "healthy" -> healthyCount++
                        health.isEmpty() -> noHealthcheckCount++
                        else -> {
                            unhealthyCount++
                            unhealthyServices.add("$service: $health")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        println()
        println("${ANSI_CYAN}Service Status Summary:${ANSI_RESET}")
        println("  Total services:              ${lines.size}")
        println("  ${ANSI_GREEN}Healthy:${ANSI_RESET}                  $healthyCount")
        println("  Without healthcheck:        $noHealthcheckCount")
        println("  ${if (unhealthyCount > 0) ANSI_RED else ANSI_GREEN}Unhealthy:${ANSI_RESET}                $unhealthyCount")
        println()

        if (unhealthyCount == 0) {
            success("All services with healthchecks are healthy! ✓")
            exitProcess(0)
        } else {
            warn("Unhealthy services:")
            unhealthyServices.forEach { println("  ${ANSI_RED}✗${ANSI_RESET} $it") }
            exitProcess(1)
        }
    }
    "quick-start" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        cmdQuickStart()
    }
    "urls" -> {
        if (isRoot()) err("Stack operations must not be run as root. Run without sudo.")
        cmdShowUrls()
    }

    // Configuration
    "config" -> {
        if (isRoot()) err("Config operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "process" -> cmdConfigProcess()
            "generate" -> cmdConfigGenerate()
            else -> {
                println("Unknown config command: ${args.getOrNull(1)}")
                println("Valid: process, generate")
                exitProcess(1)
            }
        }
    }

    // Generate OAuth/OIDC client secret hashes for Authelia
    "hash-oidc" -> {
        if (isRoot()) err("Hash generation must not be run as root. Run without sudo.")
        cmdHashOidc()
    }

    // Deployment
    "deploy" -> when (args.getOrNull(1)) {
        "create-user" -> cmdDeployCreateUser()
        "install-wrapper" -> cmdDeployInstallWrapper()
        "harden-sshd" -> cmdDeployHardenSshd()
        "generate-keys" -> cmdDeployGenerateKeys()
        else -> {
            println("Unknown deploy command: ${args.getOrNull(1)}")
            println("Valid: create-user, install-wrapper, harden-sshd, generate-keys")
            exitProcess(1)
        }
    }

    // Maintenance
    "volumes" -> {
        if (isRoot()) err("Volume operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "create" -> cmdVolumesCreate()
            "clean" -> cmdVolumesClean()
            else -> {
                println("Unknown volumes command: ${args.getOrNull(1)}")
                println("Valid: create, clean")
                exitProcess(1)
            }
        }
    }
    "ssh" -> {
        if (isRoot()) err("SSH operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "bootstrap" -> cmdSshBootstrap()
            else -> {
                println("Unknown ssh command: ${args.getOrNull(1)}")
                println("Valid: bootstrap")
                exitProcess(1)
            }
        }
    }
    "clean" -> {
        if (isRoot()) err("Clean operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "docker" -> cmdCleanDocker()
            else -> {
                println("Unknown clean command: ${args.getOrNull(1)}")
                println("Valid: docker")
                exitProcess(1)
            }
        }
    }
    "obliterate" -> {
        if (isRoot()) err("Obliterate operation must not be run as root. Run without sudo.")
        val force = args.contains("--force")
        cmdObliterate(force)
    }
    "ldap" -> {
        if (isRoot()) err("LDAP operations must not be run as root. Run without sudo.")
        when (args.getOrNull(1)) {
            "sync" -> cmdLdapSync()
            "bootstrap" -> {
                val dryRun = args.contains("--dry-run") || args.contains("-n")
                val force = args.contains("--force") || args.contains("-f")
                cmdLdapBootstrap(dryRun, force)
            }
            else -> {
                println("Unknown ldap command: ${args.getOrNull(1)}")
                println("Valid: sync, bootstrap [--force] [--dry-run]")
                exitProcess(1)
            }
        }
    }

    // Help
    "help", "--help", "-h" -> showHelp()

    else -> {
        println("Unknown command: ${args[0]}")
        println("Run './stack-controller help' for usage")
        exitProcess(1)
    }
}
