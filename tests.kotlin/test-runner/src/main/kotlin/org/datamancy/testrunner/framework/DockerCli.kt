package org.datamancy.testrunner.framework

import java.net.InetAddress

data class DockerCommandResult(
    val exitCode: Int,
    val output: String
)

object DockerCli {
    private const val DOCKER_PROXY_HOST = "tcp://docker-socket-proxy:2375"

    fun run(vararg args: String): DockerCommandResult {
        val command = listOf("docker") + args
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)

        val explicitDockerHost = System.getenv("DOCKER_HOST")
        if (explicitDockerHost.isNullOrBlank()) {
            if (isDockerProxyReachable()) {
                processBuilder.environment()["DOCKER_HOST"] = DOCKER_PROXY_HOST
            }
        }

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return DockerCommandResult(exitCode = exitCode, output = output)
    }

    private fun isDockerProxyReachable(): Boolean {
        return try {
            InetAddress.getByName("docker-socket-proxy")
            true
        } catch (_: Exception) {
            false
        }
    }
}
