#!/usr/bin/env kotlin

/**
 * BookStack API Token Generator
 *
 * Generates API tokens for BookStack automation services
 * Persists across obliterate operations
 */

import java.security.SecureRandom

fun run(vararg cmd: String): String {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = p.waitFor()
    if (code != 0) {
        println("[ERROR] Command failed: ${cmd.joinToString(" ")}")
        println(out)
        kotlin.system.exitProcess(1)
    }
    return out.trim()
}

fun generateToken(): String {
    val random = SecureRandom()
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun main() {
    println("[INFO] Generating BookStack API token...")

    // Generate token credentials
    val tokenId = "datamancy-automation-${System.currentTimeMillis()}"
    val tokenSecret = generateToken()

    println("[INFO] Token ID: $tokenId")
    println("[INFO] Generating secret...")

    // Get admin user ID from BookStack
    val userId = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker", "--execute=echo BookStack\\Users\\Models\\User::where('email', 'admin@admin.com')->first()->id ?? 1;"
    ).trim()

    println("[INFO] Using user ID: $userId")

    // Check if token already exists
    val existingCheck = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker", "--execute=echo BookStack\\Api\\ApiToken::where('name', 'Datamancy Automation')->count();"
    ).trim()

    if (existingCheck != "0") {
        println("[WARN] Token 'Datamancy Automation' already exists, removing old one...")
        run(
            "docker", "exec", "bookstack",
            "php", "/app/www/artisan", "tinker", "--execute=BookStack\\Api\\ApiToken::where('name', 'Datamancy Automation')->delete();"
        )
    }

    // Create token via Tinker (expires_at set to far future: year 2099)
    val createCmd = "\$user = BookStack\\Users\\Models\\User::find($userId); " +
        "\$token = new BookStack\\Api\\ApiToken(); " +
        "\$token->user_id = \$user->id; " +
        "\$token->name = 'Datamancy Automation'; " +
        "\$token->token_id = '$tokenId'; " +
        "\$token->secret = '$tokenSecret'; " +
        "\$token->expires_at = '2099-12-31 23:59:59'; " +
        "\$token->save(); " +
        "echo 'Token created successfully';"

    val result = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker", "--execute=$createCmd"
    )

    println("[SUCCESS] $result")
    println()
    println("=".repeat(80))
    println("Add these to your .env file:")
    println("=".repeat(80))
    println("BOOKSTACK_API_TOKEN_ID=$tokenId")
    println("BOOKSTACK_API_TOKEN_SECRET=$tokenSecret")
    println()
    println("Or export them now:")
    println("export BOOKSTACK_API_TOKEN_ID=$tokenId")
    println("export BOOKSTACK_API_TOKEN_SECRET=$tokenSecret")
    println("=".repeat(80))
}

main()
