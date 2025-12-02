package org.datamancy.vmprov.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

class VmManager(private val libvirtUri: String) {
    private val storagePool = System.getenv("LIBVIRT_STORAGE_POOL") ?: "default"
    private val storagePoolPath = System.getenv("LIBVIRT_STORAGE_PATH") ?: "/var/lib/libvirt/images"

    /**
     * Execute virsh command and return output
     */
    private fun virsh(vararg args: String): String {
        val command = listOf("virsh", "-c", libvirtUri) + args
        val pb = ProcessBuilder(command)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("virsh command failed: ${error.ifEmpty { output }}")
        }

        return output.trim()
    }

    fun listVms(): List<VmInfo> {
        try {
            val output = virsh("list", "--all")
            val lines = output.split("\n").drop(2) // Skip header
            return lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.trim().split("\\s+".toRegex(), limit = 3)
                if (parts.size >= 3) {
                    VmInfo(
                        name = parts[1],
                        uuid = getVmUuid(parts[1]),
                        state = parts[2],
                        memory = getVmMemory(parts[1]),
                        vcpus = getVmVcpus(parts[1]),
                        autostart = false
                    )
                } else null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list VMs" }
            return emptyList()
        }
    }

    fun getVmInfo(name: String): VmInfo? {
        return try {
            val uuid = getVmUuid(name)
            val state = getVmState(name)
            val memory = getVmMemory(name)
            val vcpus = getVmVcpus(name)

            VmInfo(
                name = name,
                uuid = uuid,
                state = state,
                memory = memory,
                vcpus = vcpus,
                autostart = false
            )
        } catch (e: Exception) {
            logger.warn(e) { "VM not found: $name" }
            null
        }
    }

    private fun getVmUuid(name: String): String {
        return try {
            virsh("domuuid", name)
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getVmState(name: String): String {
        return try {
            virsh("domstate", name)
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getVmMemory(name: String): Long {
        return try {
            val info = virsh("dominfo", name)
            val memLine = info.split("\n").find { it.startsWith("Max memory:") }
            memLine?.split(":")?.get(1)?.trim()?.split(" ")?.get(0)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun getVmVcpus(name: String): Int {
        return try {
            val info = virsh("dominfo", name)
            val cpuLine = info.split("\n").find { it.startsWith("CPU(s):") }
            cpuLine?.split(":")?.get(1)?.trim()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun createVm(
        name: String,
        memory: Long,
        vcpus: Int,
        diskSize: Long,
        network: String,
        imageUrl: String?
    ): VmInfo {
        logger.info { "Creating VM: name=$name, memory=${memory}MB, vcpus=$vcpus, disk=${diskSize}GB" }

        // Create disk image
        val diskPath = "$storagePoolPath/$name.qcow2"

        if (imageUrl != null) {
            // Download cloud image
            logger.info { "Downloading cloud image from: $imageUrl" }
            downloadFile(imageUrl, diskPath)

            // Resize if needed
            if (diskSize > 0) {
                resizeDisk(diskPath, diskSize)
            }
        } else {
            // Create blank disk
            createDisk(diskPath, diskSize)
        }

        // Generate libvirt XML
        val xml = generateVmXml(name, memory, vcpus, diskPath, network)
        logger.debug { "VM XML generated" }

        // Write XML to temp file
        val xmlFile = File.createTempFile("vm-$name-", ".xml")
        try {
            xmlFile.writeText(xml)

            // Define domain from XML
            virsh("define", xmlFile.absolutePath)

            // Start the VM
            virsh("start", name)

            logger.info { "VM created and started: $name" }

            return getVmInfo(name) ?: throw RuntimeException("Failed to get VM info after creation")
        } finally {
            xmlFile.delete()
        }
    }

    fun startVm(name: String) {
        virsh("start", name)
        logger.info { "VM started: $name" }
    }

    fun stopVm(name: String) {
        virsh("shutdown", name)
        logger.info { "VM shutdown initiated: $name" }
    }

    fun deleteVm(name: String) {
        try {
            // Stop if running
            val state = getVmState(name)
            if (state == "running") {
                virsh("destroy", name)  // Force shutdown
            }

            // Undefine domain
            virsh("undefine", name)

            // Delete disk file
            val diskPath = "$storagePoolPath/$name.qcow2"
            val diskFile = File(diskPath)
            if (diskFile.exists()) {
                diskFile.delete()
                logger.info { "Deleted disk: $diskPath" }
            }

            logger.info { "VM deleted: $name" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete VM: $name" }
            throw e
        }
    }

    fun injectSshKey(vmName: String, publicKey: String) {
        // Create cloud-init seed ISO for SSH key injection
        logger.info { "Injecting SSH key into VM: $vmName" }

        val metaData = """
            instance-id: $vmName
            local-hostname: $vmName
        """.trimIndent()

        val userData = """
            #cloud-config
            users:
              - name: ubuntu
                ssh-authorized-keys:
                  - $publicKey
                sudo: ['ALL=(ALL) NOPASSWD:ALL']
                groups: sudo
                shell: /bin/bash
        """.trimIndent()

        val seedIsoPath = "$storagePoolPath/$vmName-cloud-init.iso"
        createCloudInitSeed(seedIsoPath, metaData, userData)

        logger.info { "Cloud-init seed created: $seedIsoPath" }
        logger.warn { "VM must be restarted with cloud-init ISO mounted for SSH key to take effect" }
    }

    private fun generateVmXml(
        name: String,
        memory: Long,
        vcpus: Int,
        diskPath: String,
        network: String
    ): String {
        val memoryKb = memory * 1024
        return """
            <domain type='kvm'>
              <name>$name</name>
              <memory unit='KiB'>$memoryKb</memory>
              <currentMemory unit='KiB'>$memoryKb</currentMemory>
              <vcpu placement='static'>$vcpus</vcpu>
              <os>
                <type arch='x86_64' machine='pc'>hvm</type>
                <boot dev='hd'/>
              </os>
              <features>
                <acpi/>
                <apic/>
              </features>
              <cpu mode='host-passthrough'/>
              <clock offset='utc'>
                <timer name='rtc' tickpolicy='catchup'/>
                <timer name='pit' tickpolicy='delay'/>
                <timer name='hpet' present='no'/>
              </clock>
              <on_poweroff>destroy</on_poweroff>
              <on_reboot>restart</on_reboot>
              <on_crash>destroy</on_crash>
              <pm>
                <suspend-to-mem enabled='no'/>
                <suspend-to-disk enabled='no'/>
              </pm>
              <devices>
                <emulator>/usr/bin/qemu-system-x86_64</emulator>
                <disk type='file' device='disk'>
                  <driver name='qemu' type='qcow2'/>
                  <source file='$diskPath'/>
                  <target dev='vda' bus='virtio'/>
                  <address type='pci' domain='0x0000' bus='0x00' slot='0x04' function='0x0'/>
                </disk>
                <controller type='usb' index='0' model='ich9-ehci1'/>
                <controller type='pci' index='0' model='pci-root'/>
                <interface type='network'>
                  <source network='$network'/>
                  <model type='virtio'/>
                  <address type='pci' domain='0x0000' bus='0x00' slot='0x03' function='0x0'/>
                </interface>
                <serial type='pty'>
                  <target type='isa-serial' port='0'>
                    <model name='isa-serial'/>
                  </target>
                </serial>
                <console type='pty'>
                  <target type='serial' port='0'/>
                </console>
                <channel type='unix'>
                  <target type='virtio' name='org.qemu.guest_agent.0'/>
                  <address type='virtio-serial' controller='0' bus='0' port='1'/>
                </channel>
                <input type='tablet' bus='usb'>
                  <address type='usb' bus='0' port='1'/>
                </input>
                <input type='mouse' bus='ps2'/>
                <input type='keyboard' bus='ps2'/>
                <graphics type='vnc' port='-1' autoport='yes' listen='127.0.0.1'/>
                <video>
                  <model type='cirrus' vram='16384' heads='1' primary='yes'/>
                  <address type='pci' domain='0x0000' bus='0x00' slot='0x02' function='0x0'/>
                </video>
                <memballoon model='virtio'>
                  <address type='pci' domain='0x0000' bus='0x00' slot='0x05' function='0x0'/>
                </memballoon>
              </devices>
            </domain>
        """.trimIndent()
    }

    private fun createDisk(path: String, sizeGb: Long) {
        // Use qemu-img to create disk
        val pb = ProcessBuilder(
            "qemu-img", "create", "-f", "qcow2", path, "${sizeGb}G"
        )
        val process = pb.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.errorStream.bufferedReader().readText()
            throw RuntimeException("Failed to create disk: $error")
        }
        logger.info { "Created disk: $path (${sizeGb}GB)" }
    }

    private fun resizeDisk(path: String, sizeGb: Long) {
        val pb = ProcessBuilder(
            "qemu-img", "resize", path, "${sizeGb}G"
        )
        val process = pb.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.errorStream.bufferedReader().readText()
            throw RuntimeException("Failed to resize disk: $error")
        }
        logger.info { "Resized disk: $path to ${sizeGb}GB" }
    }

    private fun downloadFile(url: String, destination: String) {
        val urlObj = URL(url)
        urlObj.openStream().use { input ->
            Files.copy(input, Paths.get(destination), StandardCopyOption.REPLACE_EXISTING)
        }
        logger.info { "Downloaded: $url -> $destination" }
    }

    private fun createCloudInitSeed(isoPath: String, metaData: String, userData: String) {
        // Create temp directory for cloud-init files
        val tmpDir = Files.createTempDirectory("cloud-init-").toFile()
        try {
            File(tmpDir, "meta-data").writeText(metaData)
            File(tmpDir, "user-data").writeText(userData)

            // Create ISO using genisoimage
            val pb = ProcessBuilder(
                "genisoimage",
                "-output", isoPath,
                "-volid", "cidata",
                "-joliet",
                "-rock",
                tmpDir.absolutePath
            )
            val process = pb.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                throw RuntimeException("Failed to create cloud-init ISO: $error")
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

data class VmInfo(
    val name: String,
    val uuid: String,
    val state: String,
    val memory: Long,
    val vcpus: Int,
    val autostart: Boolean
)
