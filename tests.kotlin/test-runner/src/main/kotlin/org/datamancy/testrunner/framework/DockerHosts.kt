package org.datamancy.testrunner.framework

fun isolatedDockerHostFromEnv(): String {
    val explicitHost = System.getenv("ISOLATED_DOCKER_VM_DOCKER_HOST")
        ?: System.getenv("ISOLATED_DOCKER_HOST")
        ?: System.getenv("DOCKER_HOST_ISOLATED")

    if (!explicitHost.isNullOrBlank()) {
        return explicitHost
    }

    val dockerHost = System.getenv("DOCKER_HOST")
    if (!dockerHost.isNullOrBlank() &&
        !dockerHost.contains("docker-socket-proxy") &&
        !dockerHost.contains("docker-proxy:2375")) {
        return dockerHost
    }

    return "tcp://docker-vm-socket-proxy:2375"
}
