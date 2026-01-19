#!/usr/bin/env kotlin

/**
 * Injects pre-generated BookStack API token into database
 * Reads BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET from .env
 */

import java.io.File

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

fun main() {
    // Read from .env
    val envFile = File(".env")
    if (!envFile.exists()) {
        println("[ERROR] .env file not found")
        kotlin.system.exitProcess(1)
    }

    val env = envFile.readLines()
        .filter { it.contains("=") && !it.startsWith("#") }
        .associate {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }

    val tokenId = env["BOOKSTACK_API_TOKEN_ID"] ?: run {
        println("[ERROR] BOOKSTACK_API_TOKEN_ID not found in .env")
        kotlin.system.exitProcess(1)
    }

    val tokenSecret = env["BOOKSTACK_API_TOKEN_SECRET"] ?: run {
        println("[ERROR] BOOKSTACK_API_TOKEN_SECRET not found in .env")
        kotlin.system.exitProcess(1)
    }

    println("[INFO] Injecting BookStack API token...")
    println("[INFO] Token ID: $tokenId")

    // Get admin user ID
    val userId = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=echo BookStack\\Users\\Models\\User::where('email', 'admin@admin.com')->first()->id ?? 1;"
    ).trim()

    println("[INFO] Using user ID: $userId")

    // Delete existing token if present
    run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=BookStack\\Api\\ApiToken::where('name', 'Datamancy Automation')->delete();"
    )

    // Create token
    val createCmd = """
        ${'$'}user = BookStack\Users\Models\User::find($userId);
        ${'$'}token = new BookStack\Api\ApiToken();
        ${'$'}token->user_id = ${'$'}user->id;
        ${'$'}token->name = 'Datamancy Automation';
        ${'$'}token->token_id = '$tokenId';
        ${'$'}token->secret = '$tokenSecret';
        ${'$'}token->expires_at = '2099-12-31 23:59:59';
        ${'$'}token->save();
        echo 'Token created successfully';
    """.trimIndent()

    val result = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=$createCmd"
    )

    println("[SUCCESS] $result")
    println("[INFO] BookStack API token is now active")
}

main()
