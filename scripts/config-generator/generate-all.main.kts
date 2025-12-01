#!/usr/bin/env kotlin
@file:DependsOn("org.datamancy:config-generator:1.0-SNAPSHOT")

import org.datamancy.configgen.ConfigGenerator
import org.datamancy.configgen.ConfigGenerator.Mode
import org.datamancy.configgen.model.StackConfig
import org.datamancy.configgen.model.StackConfigs
import org.datamancy.configgen.secrets.EnvSecretsProvider
import java.io.File

data class Args(
    val env: String,
    val mode: String = "generate",
    val outputDir: String = File(".").absoluteFile.normalize().path,
    val verbose: Boolean = false
)

fun parseArgs(argv: Array<String>): Args {
    var env: String? = null
    var mode = "generate"
    var outputDir: String = File(".").absolutePath
    var verbose = false

    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--env" -> { env = argv.getOrNull(++i) }
            "--mode" -> { mode = argv.getOrNull(++i) ?: mode }
            "--output-dir" -> { outputDir = argv.getOrNull(++i) ?: outputDir }
            "--verbose" -> { verbose = true }
            else -> {
                // ignore unknown for now
            }
        }
        i++
    }
    val finalEnv = env ?: System.getenv("DATAMANCY_ENV") ?: error("--env or DATAMANCY_ENV is required")
    return Args(env = finalEnv, mode = mode, outputDir = outputDir, verbose = verbose)
}

fun loadStack(env: String): StackConfig {
    return when (env) {
        "lab" -> StackConfigs.lab()
        else -> error("Unknown environment: $env (only 'lab' is provided in MVP)")
    }
}

val args = parseArgs(args)
val stack = loadStack(args.env)
val secrets = EnvSecretsProvider()
val generator = ConfigGenerator(secrets)
val mode = when (args.mode.lowercase()) {
    "generate" -> Mode.generate
    "dry-run", "dry_run" -> Mode.dry_run
    "validate" -> Mode.validate
    "diff" -> Mode.diff
    else -> error("Unknown mode: ${args.mode}")
}
val outDir = File(args.outputDir)
val report = generator.run(stack, mode, outDir)

fun printReport() {
    println("env: ${report.env}")
    println("mode: ${report.mode}")
    if (report.errors.isNotEmpty()) {
        println("errors:")
        report.errors.forEach { println("  - $it") }
    }
    if (report.warnings.isNotEmpty()) {
        println("warnings:")
        report.warnings.forEach { println("  - $it") }
    }
    if (report.generatedFiles.isNotEmpty()) {
        println("files:")
        report.generatedFiles.forEach { println("  - $it") }
    }
}

printReport()

if (report.errors.isNotEmpty()) {
    kotlin.system.exitProcess(1)
}
