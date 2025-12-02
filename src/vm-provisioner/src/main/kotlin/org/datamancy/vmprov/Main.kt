package org.datamancy.vmprov

import com.fasterxml.jackson.databind.SerializationFeature
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.datamancy.vmprov.service.SshKeyManager
import org.datamancy.vmprov.service.VmManager

private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getenv("VM_PROVISIONER_PORT")?.toIntOrNull() ?: 8092
    val libvirtUri = System.getenv("LIBVIRT_URI") ?: "qemu:///system"
    val sshKeyDir = System.getenv("SSH_KEY_DIR") ?: "/app/ssh_keys"

    logger.info { "Starting VM Provisioner Service on port $port" }
    logger.info { "Libvirt URI: $libvirtUri" }
    logger.info { "SSH Key Directory: $sshKeyDir" }

    val vmManager = VmManager(libvirtUri)
    val sshKeyManager = SshKeyManager(sshKeyDir)

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }

        routing {
            get("/healthz") {
                call.respondText("OK")
            }

            get("/api/vms") {
                val vms = vmManager.listVms()
                call.respond(mapOf("vms" to vms))
            }

            get("/api/vms/{name}") {
                val name = call.parameters["name"] ?: return@get call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing VM name")
                )
                val vm = vmManager.getVmInfo(name)
                if (vm != null) {
                    call.respond(vm)
                } else {
                    call.respond(
                        io.ktor.http.HttpStatusCode.NotFound,
                        mapOf("error" to "VM not found")
                    )
                }
            }

            post("/api/vms") {
                val request = call.receive<VmCreateRequest>()
                try {
                    val result = vmManager.createVm(
                        name = request.name,
                        memory = request.memory,
                        vcpus = request.vcpus,
                        diskSize = request.diskSize,
                        network = request.network,
                        imageUrl = request.imageUrl
                    )
                    call.respond(result)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create VM: ${request.name}" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            post("/api/vms/{name}/start") {
                val name = call.parameters["name"] ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing VM name")
                )
                try {
                    vmManager.startVm(name)
                    call.respond(mapOf("status" to "started", "vm" to name))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to start VM: $name" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            post("/api/vms/{name}/stop") {
                val name = call.parameters["name"] ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing VM name")
                )
                try {
                    vmManager.stopVm(name)
                    call.respond(mapOf("status" to "stopped", "vm" to name))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to stop VM: $name" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            delete("/api/vms/{name}") {
                val name = call.parameters["name"] ?: return@delete call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing VM name")
                )
                try {
                    vmManager.deleteVm(name)
                    call.respond(mapOf("status" to "deleted", "vm" to name))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete VM: $name" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            // SSH Key Management
            post("/api/ssh-keys") {
                val request = call.receive<SshKeyCreateRequest>()
                try {
                    val keyPair = sshKeyManager.generateKeyPair(request.name, request.comment)
                    call.respond(keyPair)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to generate SSH key: ${request.name}" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }

            get("/api/ssh-keys") {
                val keys = sshKeyManager.listKeys()
                call.respond(mapOf("keys" to keys))
            }

            get("/api/ssh-keys/{name}/public") {
                val name = call.parameters["name"] ?: return@get call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing key name")
                )
                val publicKey = sshKeyManager.getPublicKey(name)
                if (publicKey != null) {
                    call.respondText(publicKey, contentType = io.ktor.http.ContentType.Text.Plain)
                } else {
                    call.respond(
                        io.ktor.http.HttpStatusCode.NotFound,
                        mapOf("error" to "Key not found")
                    )
                }
            }

            // Inject SSH key into VM
            post("/api/vms/{vmName}/ssh-keys/{keyName}") {
                val vmName = call.parameters["vmName"] ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing VM name")
                )
                val keyName = call.parameters["keyName"] ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing key name")
                )
                try {
                    val publicKey = sshKeyManager.getPublicKey(keyName)
                        ?: throw IllegalArgumentException("SSH key not found: $keyName")

                    vmManager.injectSshKey(vmName, publicKey)
                    call.respond(mapOf(
                        "status" to "injected",
                        "vm" to vmName,
                        "key" to keyName
                    ))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to inject SSH key into VM: $vmName" }
                    call.respond(
                        io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("error" to e.message)
                    )
                }
            }
        }
    }.start(wait = true)
}

data class VmCreateRequest(
    val name: String,
    val memory: Long = 2048,          // MB
    val vcpus: Int = 2,
    val diskSize: Long = 20,          // GB
    val network: String = "default",
    val imageUrl: String? = null      // URL to cloud image (e.g., Ubuntu cloud image)
)

data class SshKeyCreateRequest(
    val name: String,
    val comment: String? = null
)
