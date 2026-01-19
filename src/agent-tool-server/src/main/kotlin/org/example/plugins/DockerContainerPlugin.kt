package org.example.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Docker Container Plugin - Spawns Linux containers with SSH access
 * Uses Docker-in-Docker (DinD) to create isolated environments for agents
 */
class DockerContainerPlugin : Plugin {
    private val dockerHost = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
    private val dindNetwork = System.getenv("DIND_NETWORK") ?: "datamancy-stack_docker-proxy"
    private val sshKeysDir = File("/tmp/agent-ssh-keys")
    private val objectMapper = ObjectMapper()

    override fun manifest() = PluginManifest(
        id = "org.example.plugins.dockercontainer",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.DockerContainerPlugin",
        capabilities = listOf("docker.container.create", "docker.container.manage", "ssh.keymanagement"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        // Ensure SSH keys directory exists
        if (!sshKeysDir.exists()) {
            sshKeysDir.mkdirs()
        }
    }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        registry.register(
            ToolDefinition(
                name = "docker_container_create",
                description = "Create a Docker container with SSH access",
                shortDescription = "Spawn Linux container with SSH",
                longDescription = """
                    Creates a Docker container with SSH server enabled and returns connection details.
                    The container will have an SSH key pair generated automatically for pubkey authentication.
                    Supports Ubuntu, Debian, Alpine, and custom images.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("name", "string", true, "Unique container name"),
                    ToolParam("image", "string", false, "Docker image (default: ubuntu:22.04)"),
                    ToolParam("ports", "array", false, "Additional ports to expose"),
                    ToolParam("environment", "object", false, "Environment variables")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["name"],
                      "properties":{
                        "name":{"type":"string","description":"Container name"},
                        "image":{"type":"string","default":"ubuntu:22.04","description":"Docker image"},
                        "ports":{"type":"array","items":{"type":"integer"},"description":"Additional ports to expose"},
                        "environment":{"type":"object","additionalProperties":{"type":"string"},"description":"Environment variables"}
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                val image = args.get("image")?.asText() ?: "ubuntu:22.04"
                val ports = args.get("ports")?.map { it.asInt() } ?: emptyList()
                val env = args.get("environment")?.fields()?.asSequence()
                    ?.associate { it.key to it.value.asText() } ?: emptyMap()

                tools.docker_container_create(name, image, ports, env)
            }
        )

        registry.register(
            ToolDefinition(
                name = "docker_container_list",
                description = "List all containers",
                shortDescription = "List Docker containers",
                longDescription = "Returns a list of all Docker containers with their status, ports, and SSH connection info.",
                parameters = emptyList(),
                paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}""",
                pluginId = pluginId
            ),
            ToolHandler { _, _ -> tools.docker_container_list() }
        )

        registry.register(
            ToolDefinition(
                name = "docker_container_stop",
                description = "Stop a container",
                shortDescription = "Stop running container",
                longDescription = "Stops a running Docker container gracefully.",
                parameters = listOf(
                    ToolParam("name", "string", true, "Container name")
                ),
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                tools.docker_container_stop(name)
            }
        )

        registry.register(
            ToolDefinition(
                name = "docker_container_remove",
                description = "Remove a container",
                shortDescription = "Delete container permanently",
                longDescription = "Removes a Docker container and cleans up associated resources.",
                parameters = listOf(
                    ToolParam("name", "string", true, "Container name")
                ),
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                tools.docker_container_remove(name)
            }
        )

        registry.register(
            ToolDefinition(
                name = "docker_container_exec",
                description = "Execute command in container",
                shortDescription = "Run command in container",
                longDescription = "Executes a command inside a running container and returns the output.",
                parameters = listOf(
                    ToolParam("name", "string", true, "Container name"),
                    ToolParam("command", "string", true, "Command to execute")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["name","command"],
                      "properties":{
                        "name":{"type":"string","description":"Container name"},
                        "command":{"type":"string","description":"Command to execute"}
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                val command = args.get("command")?.asText() ?: throw IllegalArgumentException("command required")
                tools.docker_container_exec(name, command)
            }
        )

        registry.register(
            ToolDefinition(
                name = "docker_ssh_key_get",
                description = "Get SSH private key for container",
                shortDescription = "Retrieve SSH key for container access",
                longDescription = "Returns the SSH private key for accessing a container via SSH.",
                parameters = listOf(
                    ToolParam("containerName", "string", true, "Container name")
                ),
                paramsSpec = """{"type":"object","required":["containerName"],"properties":{"containerName":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val containerName = args.get("containerName")?.asText()
                    ?: throw IllegalArgumentException("containerName required")
                tools.docker_ssh_key_get(containerName)
            }
        )
    }

    inner class Tools {
        @LlmTool(
            shortDescription = "Create Docker container with SSH",
            longDescription = "Spawns a Linux container with SSH access and returns connection details.",
            paramsSpec = """
                {"type":"object","required":["name"],"properties":{
                  "name":{"type":"string"},
                  "image":{"type":"string","default":"ubuntu:22.04"},
                  "ports":{"type":"array","items":{"type":"integer"}},
                  "environment":{"type":"object","additionalProperties":{"type":"string"}}
                }}
            """
        )
        fun docker_container_create(
            name: String,
            image: String = "ubuntu:22.04",
            ports: List<Int> = emptyList(),
            environment: Map<String, String> = emptyMap()
        ): String {
            // Generate SSH key pair for this container
            val keyPair = generateSshKeyPair(name)

            // Create Dockerfile for SSH-enabled container
            val dockerfile = createSshDockerfile(image, keyPair.publicKey)

            // Build custom image with SSH
            val imageName = "agent-container-$name"
            buildImage(imageName, dockerfile)

            // Run container
            val containerId = runContainer(name, imageName, ports, environment)

            // Get container IP
            val containerIp = getContainerIp(name)

            return objectMapper.writeValueAsString(mapOf(
                "status" to "success",
                "container_id" to containerId,
                "container_name" to name,
                "image" to imageName,
                "ssh_host" to containerIp,
                "ssh_port" to 22,
                "ssh_user" to "root",
                "ssh_key_path" to keyPair.privateKeyPath,
                "connection_command" to "ssh -i ${keyPair.privateKeyPath} -o StrictHostKeyChecking=no root@$containerIp"
            ))
        }

        @LlmTool(
            shortDescription = "List Docker containers",
            longDescription = "Returns all containers with their status and connection info.",
            paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}"""
        )
        fun docker_container_list(): String {
            val output = executeDockerCommand("ps", "-a", "--format", "{{.Names}}\\t{{.Status}}\\t{{.Image}}")
            val containers = output.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split("\t")
                    mapOf(
                        "name" to parts.getOrNull(0).orEmpty(),
                        "status" to parts.getOrNull(1).orEmpty(),
                        "image" to parts.getOrNull(2).orEmpty()
                    )
                }

            return objectMapper.writeValueAsString(mapOf("containers" to containers))
        }

        @LlmTool(
            shortDescription = "Stop Docker container",
            longDescription = "Stops a running container gracefully.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun docker_container_stop(name: String): String {
            executeDockerCommand("stop", name)
            return objectMapper.writeValueAsString(mapOf("status" to "success", "message" to "Container stopped"))
        }

        @LlmTool(
            shortDescription = "Remove Docker container",
            longDescription = "Removes a container and cleans up resources.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun docker_container_remove(name: String): String {
            executeDockerCommand("rm", "-f", name)

            // Clean up SSH keys
            val privateKey = File(sshKeysDir, "$name")
            val publicKey = File(sshKeysDir, "$name.pub")
            privateKey.delete()
            publicKey.delete()

            return objectMapper.writeValueAsString(mapOf("status" to "success", "message" to "Container removed"))
        }

        @LlmTool(
            shortDescription = "Execute command in container",
            longDescription = "Runs a command inside a container and returns output.",
            paramsSpec = """{"type":"object","required":["name","command"],"properties":{"name":{"type":"string"},"command":{"type":"string"}}}"""
        )
        fun docker_container_exec(name: String, command: String): String {
            val output = executeDockerCommand("exec", name, "sh", "-c", command)
            return objectMapper.writeValueAsString(mapOf("status" to "success", "output" to output))
        }

        @LlmTool(
            shortDescription = "Get SSH private key",
            longDescription = "Returns the SSH private key for container access.",
            paramsSpec = """{"type":"object","required":["containerName"],"properties":{"containerName":{"type":"string"}}}"""
        )
        fun docker_ssh_key_get(containerName: String): String {
            val privateKeyFile = File(sshKeysDir, containerName)
            if (!privateKeyFile.exists()) {
                throw IllegalArgumentException("No SSH key found for container: $containerName")
            }

            val privateKey = privateKeyFile.readText()
            return objectMapper.writeValueAsString(mapOf(
                "status" to "success",
                "private_key" to privateKey,
                "key_path" to privateKeyFile.absolutePath
            ))
        }

        // Helper functions
        private fun generateSshKeyPair(name: String): KeyPair {
            val privateKeyFile = File(sshKeysDir, name)
            val publicKeyFile = File(sshKeysDir, "$name.pub")

            // Generate ED25519 key (faster and more secure than RSA)
            val process = ProcessBuilder(
                "ssh-keygen", "-t", "ed25519", "-f", privateKeyFile.absolutePath,
                "-N", "", "-C", "agent-container-$name"
            ).redirectErrorStream(true).start()

            process.waitFor(30, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                throw RuntimeException("Failed to generate SSH key: ${process.inputStream.bufferedReader().readText()}")
            }

            // Set proper permissions
            Files.setPosixFilePermissions(privateKeyFile.toPath(), PosixFilePermissions.fromString("rw-------"))

            return KeyPair(
                privateKeyPath = privateKeyFile.absolutePath,
                publicKey = publicKeyFile.readText().trim()
            )
        }

        private fun createSshDockerfile(baseImage: String, publicKey: String): String {
            return """
                FROM $baseImage

                # Install SSH server
                RUN if command -v apt-get > /dev/null; then \
                      apt-get update && apt-get install -y openssh-server && rm -rf /var/lib/apt/lists/*; \
                    elif command -v apk > /dev/null; then \
                      apk add --no-cache openssh-server; \
                    elif command -v yum > /dev/null; then \
                      yum install -y openssh-server && yum clean all; \
                    fi

                # Configure SSH
                RUN mkdir -p /var/run/sshd /root/.ssh && \
                    chmod 700 /root/.ssh && \
                    echo '$publicKey' > /root/.ssh/authorized_keys && \
                    chmod 600 /root/.ssh/authorized_keys && \
                    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config || true && \
                    sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config || true

                # Expose SSH port
                EXPOSE 22

                # Start SSH daemon
                CMD ["/usr/sbin/sshd", "-D"]
            """.trimIndent()
        }

        private fun buildImage(imageName: String, dockerfile: String): String {
            // Create temporary directory for build context
            val buildDir = Files.createTempDirectory("docker-build-").toFile()
            try {
                File(buildDir, "Dockerfile").writeText(dockerfile)

                val command = mutableListOf("docker", "build", "-t", imageName, ".")

                // Add DOCKER_HOST if using TCP (must apply to build as well)
                if (dockerHost.startsWith("tcp://")) {
                    command.add(1, "-H")
                    command.add(2, dockerHost)
                }

                val process = ProcessBuilder(command)
                    .directory(buildDir)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(300, TimeUnit.SECONDS)

                if (process.exitValue() != 0) {
                    throw RuntimeException("Failed to build image: $output")
                }

                return output
            } finally {
                buildDir.deleteRecursively()
            }
        }

        private fun runContainer(
            name: String,
            image: String,
            ports: List<Int>,
            environment: Map<String, String>
        ): String {
            val args = mutableListOf("run", "-d", "--name", name, "--network", dindNetwork)

            // Add additional port mappings (SSH is already exposed internally on 22)
            ports.forEach { port ->
                args.add("-p")
                args.add("$port:$port")
            }

            // Add environment variables
            environment.forEach { (key, value) ->
                args.add("-e")
                args.add("$key=$value")
            }

            args.add(image)

            return executeDockerCommand(*args.toTypedArray()).trim()
        }

        private fun getContainerIp(name: String): String {
            return executeDockerCommand(
                "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name
            ).trim()
        }

        private fun executeDockerCommand(vararg args: String): String {
            val command = mutableListOf("docker")

            // Add DOCKER_HOST if using TCP
            if (dockerHost.startsWith("tcp://")) {
                command.add("-H")
                command.add(dockerHost)
            }

            command.addAll(args)

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                throw RuntimeException("Docker command failed: $output")
            }

            return output
        }
    }

    data class KeyPair(
        val privateKeyPath: String,
        val publicKey: String
    )
}
