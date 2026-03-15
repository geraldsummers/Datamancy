package org.datamancy.testrunner.framework

data class DockerCommandResult(
    val exitCode: Int,
    val output: String
)

object DockerCli {
    private const val DOCKER_PROXY_HOST = "tcp://docker-socket-proxy:2375"

    fun run(vararg args: String): DockerCommandResult {
        val explicitDockerHost = System.getenv("DOCKER_HOST")
        if (!explicitDockerHost.isNullOrBlank()) {
            return runWithDockerHost(args.toList(), explicitDockerHost)
        }

        val proxyAttempt = runWithDockerHost(args.toList(), DOCKER_PROXY_HOST)
        if (proxyAttempt.exitCode == 0) return proxyAttempt

        val outputLower = proxyAttempt.output.lowercase()
        val proxyUnavailable = outputLower.contains("no such host") ||
            outputLower.contains("lookup docker-socket-proxy") ||
            outputLower.contains("name or service not known")

        return if (proxyUnavailable) {
            runWithDockerHost(args.toList(), null)
        } else {
            proxyAttempt
        }
    }

    private fun runWithDockerHost(args: List<String>, dockerHost: String?): DockerCommandResult {
        val command = listOf("docker") + args
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        if (!dockerHost.isNullOrBlank()) {
            processBuilder.environment()["DOCKER_HOST"] = dockerHost
        }
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return DockerCommandResult(exitCode = exitCode, output = output)
    }
}
