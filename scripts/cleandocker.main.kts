#!/usr/bin/env kotlin

import java.io.BufferedReader
        import java.io.InputStreamReader
        import java.util.concurrent.TimeUnit

fun executeCommand(command: List<String>, ignoreErrors: Boolean = false): String {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    val output = StringBuilder()
    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
    }

    val exitCode = process.waitFor()
    if (exitCode != 0 && !ignoreErrors) {
        throw RuntimeException("Command failed with exit code $exitCode:\n$output")
    }
    return output.toString().trim()
}

fun main() {
    try {
        println("===> Stopping all running containers...")
        val runningContainers = executeCommand(listOf("docker", "ps", "-q"))
        if (runningContainers.isNotEmpty()) {
            // Split by new lines to get individual IDs
            val ids = runningContainers.split("\\s+".toRegex())
            val stopCommand = mutableListOf("docker", "stop")
            stopCommand.addAll(ids)
            executeCommand(stopCommand)
            println("✓ All running containers stopped.")
        } else {
            println("ℹ️ No running containers to stop.")
        }

        println("===> Removing all containers...")
        val allContainers = executeCommand(listOf("docker", "ps", "-aq"))
        if (allContainers.isNotEmpty()) {
            val ids = allContainers.split("\\s+".toRegex())
            val rmCommand = mutableListOf("docker", "rm")
            rmCommand.addAll(ids)
            executeCommand(rmCommand)
            println("✓ All containers removed.")
        } else {
            println("ℹ️ No containers to remove.")
        }

        println("===> Removing all volumes...")
        val allVolumes = executeCommand(listOf("docker", "volume", "ls", "-q"))
        if (allVolumes.isNotEmpty()) {
            val ids = allVolumes.split("\\s+".toRegex())
            val volRmCommand = mutableListOf("docker", "volume", "rm")
            volRmCommand.addAll(ids)
            executeCommand(volRmCommand)
            println("✓ All volumes removed.")
        } else {
            println("ℹ️ No volumes to remove.")
        }

        println("===> Pruning unused networks...")
        executeCommand(listOf("docker", "network", "prune", "-f"))
        println("✓ Network prune complete.")

        println("✅ Docker cleanup complete.")

    } catch (e: Exception) {
        System.err.println("❌ Error occurred: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

main()