package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * VM Provisioning Plugin - Manages virtual machines via libvirt/QEMU
 */
class VmProvisioningPlugin : Plugin {
    private val vmServiceUrl = System.getenv("VM_PROVISIONER_URL") ?: "http://vm-provisioner:8092"
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val objectMapper = ObjectMapper()

    override fun manifest() = PluginManifest(
        id = "org.example.plugins.vmprovisioning",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.VmProvisioningPlugin",
        capabilities = listOf("vm.create", "vm.manage", "ssh.keymanagement"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) { /* no-op */ }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        registry.register(
            ToolDefinition(
                name = "vm_list",
                description = "List all virtual machines",
                shortDescription = "List all VMs managed by libvirt",
                longDescription = "Returns a list of all virtual machines with their current state, memory, vCPUs, and other properties.",
                parameters = emptyList(),
                paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}""",
                pluginId = pluginId
            ),
            ToolHandler { _, _ -> tools.vm_list() }
        )

        registry.register(
            ToolDefinition(
                name = "vm_create",
                description = "Create a new virtual machine",
                shortDescription = "Create and start a new VM",
                longDescription = """
                    Creates a new virtual machine with the specified configuration. Optionally downloads
                    a cloud image (e.g., Ubuntu Cloud Image) and injects cloud-init for initial setup.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("name", "string", true, "Unique name for the VM"),
                    ToolParam("memory", "integer", false, "Memory in MB (default: 2048)"),
                    ToolParam("vcpus", "integer", false, "Number of virtual CPUs (default: 2)"),
                    ToolParam("diskSize", "integer", false, "Disk size in GB (default: 20)"),
                    ToolParam("network", "string", false, "Libvirt network name (default: 'default')"),
                    ToolParam("imageUrl", "string", false, "URL to cloud image (optional)")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["name"],
                      "properties":{
                        "name":{"type":"string","description":"Unique VM name"},
                        "memory":{"type":"integer","minimum":512,"default":2048,"description":"Memory in MB"},
                        "vcpus":{"type":"integer","minimum":1,"default":2,"description":"Number of vCPUs"},
                        "diskSize":{"type":"integer","minimum":1,"default":20,"description":"Disk size in GB"},
                        "network":{"type":"string","default":"default","description":"Network name"},
                        "imageUrl":{"type":"string","description":"Cloud image URL (optional)"}
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                val memory = args.get("memory")?.asLong() ?: 2048
                val vcpus = args.get("vcpus")?.asInt() ?: 2
                val diskSize = args.get("diskSize")?.asLong() ?: 20
                val network = args.get("network")?.asText() ?: "default"
                val imageUrl = args.get("imageUrl")?.asText()

                tools.vm_create(name, memory, vcpus, diskSize, network, imageUrl)
            }
        )

        registry.register(
            ToolDefinition(
                name = "vm_start",
                description = "Start a virtual machine",
                shortDescription = "Start a stopped VM",
                longDescription = "Starts a virtual machine that is currently in the 'shutoff' state.",
                parameters = listOf(
                    ToolParam("name", "string", true, "VM name to start")
                ),
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                tools.vm_start(name)
            }
        )

        registry.register(
            ToolDefinition(
                name = "vm_stop",
                description = "Stop a virtual machine",
                shortDescription = "Gracefully shutdown a VM",
                longDescription = "Sends a shutdown signal to the virtual machine. The VM will attempt to shut down gracefully.",
                parameters = listOf(
                    ToolParam("name", "string", true, "VM name to stop")
                ),
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                tools.vm_stop(name)
            }
        )

        registry.register(
            ToolDefinition(
                name = "vm_delete",
                description = "Delete a virtual machine",
                shortDescription = "Delete VM and its disk",
                longDescription = "Permanently deletes a virtual machine and its associated disk image. The VM must be stopped first.",
                parameters = listOf(
                    ToolParam("name", "string", true, "VM name to delete")
                ),
                paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                tools.vm_delete(name)
            }
        )

        registry.register(
            ToolDefinition(
                name = "ssh_key_generate",
                description = "Generate an SSH key pair",
                shortDescription = "Generate RSA SSH key pair",
                longDescription = """
                    Generates a 4096-bit RSA SSH key pair for secure authentication. The private key
                    is stored securely with 600 permissions, and the public key is in OpenSSH format.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("name", "string", true, "Key pair name"),
                    ToolParam("comment", "string", false, "Comment for the key (default: key name)")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["name"],
                      "properties":{
                        "name":{"type":"string","description":"Key name"},
                        "comment":{"type":"string","description":"Key comment (optional)"}
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val name = args.get("name")?.asText() ?: throw IllegalArgumentException("name required")
                val comment = args.get("comment")?.asText()
                tools.ssh_key_generate(name, comment)
            }
        )

        registry.register(
            ToolDefinition(
                name = "ssh_key_list",
                description = "List all SSH keys",
                shortDescription = "List managed SSH keys",
                longDescription = "Returns a list of all SSH key pairs managed by the system.",
                parameters = emptyList(),
                paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}""",
                pluginId = pluginId
            ),
            ToolHandler { _, _ -> tools.ssh_key_list() }
        )

        registry.register(
            ToolDefinition(
                name = "vm_inject_ssh_key",
                description = "Inject SSH key into VM",
                shortDescription = "Add SSH key to VM for access",
                longDescription = """
                    Injects an SSH public key into a virtual machine using cloud-init. The key will be
                    added to the default user's authorized_keys file. The VM must be restarted for changes to take effect.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("vmName", "string", true, "VM name"),
                    ToolParam("keyName", "string", true, "SSH key name to inject")
                ),
                paramsSpec = """
                    {
                      "type":"object",
                      "required":["vmName","keyName"],
                      "properties":{
                        "vmName":{"type":"string","description":"Target VM name"},
                        "keyName":{"type":"string","description":"SSH key to inject"}
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val vmName = args.get("vmName")?.asText() ?: throw IllegalArgumentException("vmName required")
                val keyName = args.get("keyName")?.asText() ?: throw IllegalArgumentException("keyName required")
                tools.vm_inject_ssh_key(vmName, keyName)
            }
        )
    }

    inner class Tools {
        @LlmTool(
            shortDescription = "List all virtual machines",
            longDescription = "Returns a list of all VMs with their state, memory, vCPUs, and other properties.",
            paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}"""
        )
        fun vm_list(): String {
            val response = makeRequest("GET", "/api/vms")
            return response
        }

        @LlmTool(
            shortDescription = "Create a new virtual machine",
            longDescription = "Creates and starts a new VM with specified configuration.",
            paramsSpec = """
                {"type":"object","required":["name"],"properties":{
                  "name":{"type":"string"},
                  "memory":{"type":"integer","default":2048},
                  "vcpus":{"type":"integer","default":2},
                  "diskSize":{"type":"integer","default":20},
                  "network":{"type":"string","default":"default"},
                  "imageUrl":{"type":"string"}
                }}
            """
        )
        fun vm_create(
            name: String,
            memory: Long = 2048,
            vcpus: Int = 2,
            diskSize: Long = 20,
            network: String = "default",
            imageUrl: String? = null
        ): String {
            val payload = mapOf(
                "name" to name,
                "memory" to memory,
                "vcpus" to vcpus,
                "diskSize" to diskSize,
                "network" to network,
                "imageUrl" to imageUrl
            )
            return makeRequest("POST", "/api/vms", payload)
        }

        @LlmTool(
            shortDescription = "Start a virtual machine",
            longDescription = "Starts a VM that is currently stopped.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun vm_start(name: String): String {
            return makeRequest("POST", "/api/vms/$name/start")
        }

        @LlmTool(
            shortDescription = "Stop a virtual machine",
            longDescription = "Gracefully shuts down a running VM.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun vm_stop(name: String): String {
            return makeRequest("POST", "/api/vms/$name/stop")
        }

        @LlmTool(
            shortDescription = "Delete a virtual machine",
            longDescription = "Permanently deletes a VM and its disk.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""
        )
        fun vm_delete(name: String): String {
            return makeRequest("DELETE", "/api/vms/$name")
        }

        @LlmTool(
            shortDescription = "Generate SSH key pair",
            longDescription = "Generates a 4096-bit RSA SSH key pair.",
            paramsSpec = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"},"comment":{"type":"string"}}}"""
        )
        fun ssh_key_generate(name: String, comment: String? = null): String {
            val payload = mapOf("name" to name, "comment" to comment)
            return makeRequest("POST", "/api/ssh-keys", payload)
        }

        @LlmTool(
            shortDescription = "List SSH keys",
            longDescription = "Returns all managed SSH key pairs.",
            paramsSpec = """{"type":"object","properties":{},"additionalProperties":false}"""
        )
        fun ssh_key_list(): String {
            return makeRequest("GET", "/api/ssh-keys")
        }

        @LlmTool(
            shortDescription = "Inject SSH key into VM",
            longDescription = "Adds an SSH public key to a VM's authorized_keys via cloud-init.",
            paramsSpec = """{"type":"object","required":["vmName","keyName"],"properties":{"vmName":{"type":"string"},"keyName":{"type":"string"}}}"""
        )
        fun vm_inject_ssh_key(vmName: String, keyName: String): String {
            return makeRequest("POST", "/api/vms/$vmName/ssh-keys/$keyName")
        }

        private fun makeRequest(method: String, path: String, body: Any? = null): String {
            val uri = URI.create("$vmServiceUrl$path")
            val requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60))

            if (body != null) {
                val json = objectMapper.writeValueAsString(body)
                requestBuilder.header("Content-Type", "application/json")
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(json))
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody())
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                throw RuntimeException("VM service error: ${response.statusCode()} - ${response.body()}")
            }

            return response.body()
        }
    }
}
