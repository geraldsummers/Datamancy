package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
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

class DockerContainerPlugin : Plugin {
    private val dockerHost = sequenceOf(
        System.getenv("DOCKER_HOST_ISOLATED"),
        System.getenv("ISOLATED_DOCKER_VM_DOCKER_HOST"),
        System.getenv("DOCKER_HOST")
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull() ?: "unix:///var/run/docker.sock"

    private val dindNetwork = (System.getenv("DIND_NETWORK") ?: "datamancy-stack_docker-proxy")
        .trim()
        .ifEmpty { "datamancy-stack_docker-proxy" }

    private val sshKeysDir = File("/tmp/agent-ssh-keys")
    private val objectMapper = ObjectMapper()
    private val allowPrivateKeyExport = envFlag("TOOLSERVER_ENABLE_PRIVATE_KEY_EXPORT", false)

    companion object {
        private val containerNamePattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")
        private val imagePattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_./:-]{0,255}(?:@sha256:[a-f0-9]{64})?$")
        private val networkNamePattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")
        private val envKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val commandTokenPattern = Regex("^[^\\u0000\\n\\r]{1,256}$")
        private val forbiddenCommandFragments = listOf(";", "&&", "||", "|", "`", "$(", "<", ">")

        private fun envFlag(name: String, defaultValue: Boolean): Boolean {
            return when ((System.getProperty(name) ?: System.getenv(name) ?: "").trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> defaultValue
            }
        }
    }

    override fun manifest() = PluginManifest(
        id = "org.example.plugins.dockercontainer",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.DockerContainerPlugin",
        capabilities = listOf("docker.container.create", "docker.container.manage", "ssh.keymanagement"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        if (!sshKeysDir.exists()) {
            if (!sshKeysDir.mkdirs()) {
                throw IllegalStateException("Unable to create SSH key directory at ${sshKeysDir.absolutePath}")
            }
        }
        runCatching {
            Files.setPosixFilePermissions(sshKeysDir.toPath(), PosixFilePermissions.fromString("rwx------"))
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
                val ports = args.get("ports")
                    ?.map { it.asText().toIntOrNull() ?: throw IllegalArgumentException("ports must be integers") }
                    ?: emptyList()
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
                    ToolParam("command", "array[string]", true, "Command argv to execute")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["name","command"],
                      "properties":{
                        "name":{"type":"string","description":"Container name"},
                        "command":{
                          "oneOf":[
                            {"type":"array","items":{"type":"string"}},
                            {"type":"string"}
                          ],
                          "description":"Command argv array (or legacy string)"
                        }
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                val command = parseCommandArgument(args.get("command") ?: throw IllegalArgumentException("command required"))
                tools.docker_container_exec(name, command)
            }
        )

        if (allowPrivateKeyExport) {
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
            val sanitizedName = sanitizeContainerName(name)
            val sanitizedImage = sanitizeImage(image)
            val sanitizedNetwork = sanitizeNetworkName(dindNetwork)
            val sanitizedPorts = sanitizePorts(ports)
            val sanitizedEnv = sanitizeEnvironment(environment)

            val keyPair = generateSshKeyPair(sanitizedName)
            val dockerfile = createSshDockerfile(sanitizedImage, keyPair.publicKey)

            val imageName = "agent-container-$sanitizedName"
            buildImage(imageName, dockerfile)

            val containerId = runContainer(sanitizedName, imageName, sanitizedNetwork, sanitizedPorts, sanitizedEnv)
            val containerIp = getContainerIp(sanitizedName)

            return objectMapper.writeValueAsString(
                mapOf(
                    "status" to "success",
                    "container_id" to containerId,
                    "container_name" to sanitizedName,
                    "image" to imageName,
                    "docker_host" to dockerHost,
                    "ssh_host" to containerIp,
                    "ssh_port" to 22,
                    "ssh_user" to "root",
                    "ssh_key_path" to keyPair.privateKeyPath,
                    "connection_command" to "ssh -i ${keyPair.privateKeyPath} -o StrictHostKeyChecking=no root@$containerIp"
                )
            )
        }

        @LlmTool(
            shortDescription = "List Docker containers",
            longDescription = "Returns all containers with their status and connection info.",
            paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}"""
        )
        fun docker_container_list(): String {
            val output = executeDockerCommand(60, "ps", "-a", "--format", "{{.Names}}\\t{{.Status}}\\t{{.Image}}")
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
            val sanitizedName = sanitizeContainerName(name)
            executeDockerCommand(60, "stop", sanitizedName)
            return objectMapper.writeValueAsString(mapOf("status" to "success", "message" to "Container stopped"))
        }

        @LlmTool(
            shortDescription = "Remove Docker container",
            longDescription = "Removes a container and cleans up resources.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun docker_container_remove(name: String): String {
            val sanitizedName = sanitizeContainerName(name)
            executeDockerCommand(60, "rm", "-f", sanitizedName)

            val privateKey = privateKeyFileFor(sanitizedName)
            val publicKey = publicKeyFileFor(sanitizedName)
            privateKey.delete()
            publicKey.delete()

            return objectMapper.writeValueAsString(mapOf("status" to "success", "message" to "Container removed"))
        }

        @LlmTool(
            shortDescription = "Execute command in container",
            longDescription = "Runs a command inside a container and returns output.",
            paramsSpec = """{"type":"object","required":["name","command"],"properties":{"name":{"type":"string"},"command":{"type":"array","items":{"type":"string"}}}}"""
        )
        fun docker_container_exec(name: String, command: List<String>): String {
            val sanitizedName = sanitizeContainerName(name)
            val sanitizedCommand = sanitizeExecCommand(command)
            val args = mutableListOf("exec", sanitizedName)
            args.addAll(sanitizedCommand)
            val output = executeDockerCommand(60, *args.toTypedArray())
            return objectMapper.writeValueAsString(mapOf("status" to "success", "output" to output))
        }

        @LlmTool(
            shortDescription = "Get SSH private key",
            longDescription = "Returns the SSH private key for container access.",
            paramsSpec = """{"type":"object","required":["containerName"],"properties":{"containerName":{"type":"string"}}}"""
        )
        fun docker_ssh_key_get(containerName: String): String {
            if (!allowPrivateKeyExport) {
                throw SecurityException("Private key export is disabled (TOOLSERVER_ENABLE_PRIVATE_KEY_EXPORT=false)")
            }

            val sanitizedContainer = sanitizeContainerName(containerName)
            val privateKeyFile = privateKeyFileFor(sanitizedContainer)
            if (!privateKeyFile.exists()) {
                throw IllegalArgumentException("No SSH key found for container: $sanitizedContainer")
            }

            val privateKey = privateKeyFile.readText()
            return objectMapper.writeValueAsString(
                mapOf(
                    "status" to "success",
                    "private_key" to privateKey,
                    "key_path" to privateKeyFile.absolutePath
                )
            )
        }

        private fun generateSshKeyPair(name: String): KeyPair {
            val sanitizedName = sanitizeContainerName(name)
            val privateKeyFile = privateKeyFileFor(sanitizedName)
            val publicKeyFile = publicKeyFileFor(sanitizedName)
            privateKeyFile.delete()
            publicKeyFile.delete()

            val process = ProcessBuilder(
                "ssh-keygen", "-t", "ed25519", "-f", privateKeyFile.absolutePath,
                "-N", "", "-C", "agent-container-$sanitizedName", "-q"
            ).redirectErrorStream(true).start()

            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw RuntimeException("Failed to generate SSH key: timeout")
            }
            if (process.exitValue() != 0) {
                throw RuntimeException("Failed to generate SSH key: ${process.inputStream.bufferedReader().readText()}")
            }

            runCatching {
                Files.setPosixFilePermissions(privateKeyFile.toPath(), PosixFilePermissions.fromString("rw-------"))
            }

            return KeyPair(
                privateKeyPath = privateKeyFile.absolutePath,
                publicKey = publicKeyFile.readText().trim()
            )
        }

        private fun createSshDockerfile(baseImage: String, publicKey: String): String {
            val sanitizedImage = sanitizeImage(baseImage)
            val escapedPublicKey = publicKey.replace("'", "'\"'\"'")
            return """
                FROM $sanitizedImage

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
                    echo '$escapedPublicKey' > /root/.ssh/authorized_keys && \
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
            val buildDir = Files.createTempDirectory("docker-build-").toFile()
            try {
                File(buildDir, "Dockerfile").writeText(dockerfile)

                val command = dockerCliPrefix().apply {
                    addAll(listOf("build", "-t", imageName, "."))
                }

                val (exitCode, output) = runCommand(command, timeoutSeconds = 300, workingDirectory = buildDir)
                if (exitCode != 0) {
                    throw RuntimeException("Failed to build image (exit=$exitCode): $output")
                }

                return output
            } finally {
                buildDir.deleteRecursively()
            }
        }

        private fun runContainer(
            name: String,
            image: String,
            network: String,
            ports: List<Int>,
            environment: Map<String, String>
        ): String {
            val args = mutableListOf("run", "-d", "--name", sanitizeContainerName(name), "--network", sanitizeNetworkName(network))

            sanitizePorts(ports).forEach { port ->
                args.add("-p")
                args.add("$port:$port")
            }

            sanitizeEnvironment(environment).forEach { (key, value) ->
                args.add("-e")
                args.add("$key=$value")
            }

            args.add(sanitizeImage(image))

            return executeDockerCommand(60, *args.toTypedArray()).trim()
        }

        private fun getContainerIp(name: String): String {
            return executeDockerCommand(
                60,
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                sanitizeContainerName(name)
            ).trim()
        }

        private fun executeDockerCommand(timeoutSeconds: Long = 60, vararg args: String): String {
            val command = dockerCliPrefix().apply { addAll(args.toList()) }
            val (exitCode, output) = runCommand(command, timeoutSeconds = timeoutSeconds)
            if (exitCode != 0) {
                throw RuntimeException("Docker command failed (exit=$exitCode): $output")
            }
            return output
        }

        private fun dockerCliPrefix(): MutableList<String> {
            return mutableListOf("docker").apply {
                if (dockerHost.isNotBlank()) {
                    add("-H")
                    add(dockerHost)
                }
            }
        }

        private fun runCommand(
            command: List<String>,
            timeoutSeconds: Long,
            workingDirectory: File? = null
        ): Pair<Int, String> {
            val process = ProcessBuilder(command)
                .apply {
                    if (workingDirectory != null) {
                        directory(workingDirectory)
                    }
                    redirectErrorStream(true)
                }
                .start()

            val output = StringBuilder()
            val readerThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lines().forEach { output.append(it).append('\n') }
                }
            }
            readerThread.start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                readerThread.join(1000)
                throw RuntimeException("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
            }
            readerThread.join(1000)
            return process.exitValue() to output.toString()
        }
    }

    data class KeyPair(
        val privateKeyPath: String,
        val publicKey: String
    )

    private fun parseCommandArgument(node: JsonNode): List<String> {
        return when {
            node.isArray -> node.map { it.asText() }
            node.isTextual -> splitLegacyCommand(node.asText())
            else -> throw IllegalArgumentException("command must be array[string] or string")
        }
    }

    private fun splitLegacyCommand(raw: String): List<String> {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "command cannot be blank" }
        require(forbiddenCommandFragments.none { trimmed.contains(it) }) {
            "legacy command strings cannot include shell control operators"
        }
        return trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun sanitizeContainerName(name: String): String {
        val trimmed = name.trim()
        require(containerNamePattern.matches(trimmed)) { "Invalid container name: $name" }
        return trimmed
    }

    private fun sanitizeImage(image: String): String {
        val trimmed = image.trim()
        require(imagePattern.matches(trimmed)) { "Invalid image reference: $image" }
        require(!trimmed.contains("..")) { "Invalid image reference: path traversal sequence not allowed" }
        return trimmed
    }

    private fun sanitizeNetworkName(network: String): String {
        val trimmed = network.trim()
        require(networkNamePattern.matches(trimmed)) { "Invalid docker network name: $network" }
        return trimmed
    }

    private fun sanitizePorts(ports: List<Int>): List<Int> {
        require(ports.size <= 32) { "Too many ports requested (${ports.size}); max 32" }
        return ports.map { port ->
            require(port in 1..65535) { "Port out of range: $port" }
            port
        }.distinct()
    }

    private fun sanitizeEnvironment(environment: Map<String, String>): Map<String, String> {
        require(environment.size <= 64) { "Too many environment variables requested (${environment.size}); max 64" }
        return environment.map { (rawKey, rawValue) ->
            val key = rawKey.trim()
            require(envKeyPattern.matches(key)) { "Invalid environment variable name: $rawKey" }
            require(!rawValue.contains('\u0000')) { "Environment variable '$key' contains null byte" }
            require(rawValue.length <= 2048) { "Environment variable '$key' value too long" }
            key to rawValue
        }.toMap()
    }

    private fun sanitizeExecCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(command.size <= 32) { "command may contain at most 32 argv entries" }
        return command.map { token ->
            val sanitized = token.trim()
            require(commandTokenPattern.matches(sanitized)) { "Invalid command token: '$token'" }
            require(forbiddenCommandFragments.none { sanitized.contains(it) }) {
                "Command token contains forbidden shell fragment: '$token'"
            }
            sanitized
        }
    }

    private fun privateKeyFileFor(containerName: String): File {
        return File(sshKeysDir, sanitizeContainerName(containerName))
    }

    private fun publicKeyFileFor(containerName: String): File {
        return File(sshKeysDir, "${sanitizeContainerName(containerName)}.pub")
    }
}
