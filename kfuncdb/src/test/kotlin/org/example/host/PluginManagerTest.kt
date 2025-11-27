package org.example.host

import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import org.example.testplugins.TestPlugin
import org.example.util.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class PluginManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeJar(dir: Path, manifest: PluginManifest): File {
        val jarPath = dir.resolve("plugin-${manifest.id}.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            val entry = JarEntry("llm-plugin.json")
            jos.putNextEntry(entry)
            val json = Json.mapper.writeValueAsBytes(manifest)
            jos.write(json)
            jos.closeEntry()
        }
        return jarPath.toFile()
    }

    private fun manager(policy: CapabilityPolicy = CapabilityPolicy(allowed = setOf("network", "filesystem"))): PluginManager {
        val cfg = HostConfig(
            hostVersion = "1.0.0",
            apiVersion = "1.0.0",
            pluginsDir = tempDir.toFile().absolutePath,
            capabilityPolicy = policy
        )
        return PluginManager(cfg)
    }

    @Test
    fun `loads plugin successfully and calls init and shutdown`() {
        val manifest = PluginManifest(
            id = "ok",
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = listOf("network"),
            requires = Requires(host = ">=1.0.0 <2.0.0", api = "1.0.x")
        )
        writeJar(tempDir, manifest)

        val mgr = manager()
        val loaded = mgr.loadAll()
        assertEquals(1, loaded.size)
        val inst = loaded.first().instance as TestPlugin
        assertEquals(1, inst.initCount)
        mgr.shutdownAll()
        assertEquals(1, inst.shutdownCount)
    }

    @Test
    fun `rejects due to apiVersion mismatch`() {
        val manifest = PluginManifest(
            id = "api-mismatch",
            version = "0.1.0",
            apiVersion = "2.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = emptyList(),
            requires = Requires(host = "1.x", api = "*")
        )
        writeJar(tempDir, manifest)
        val mgr = manager()
        val loaded = mgr.loadAll()
        assertEquals(0, loaded.size)
    }

    @Test
    fun `rejects due to requires host constraint`() {
        val manifest = PluginManifest(
            id = "host-req",
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = emptyList(),
            requires = Requires(host = ">=2.0.0", api = "1.0.x")
        )
        writeJar(tempDir, manifest)
        val mgr = manager()
        val loaded = mgr.loadAll()
        assertEquals(0, loaded.size)
    }

    @Test
    fun `rejects due to disallowed capabilities`() {
        val manifest = PluginManifest(
            id = "cap-bad",
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = TestPlugin::class.qualifiedName!!,
            capabilities = listOf("filesystem"),
            requires = Requires(host = "*", api = "1.0.x")
        )
        writeJar(tempDir, manifest)
        // Only allow network, not filesystem
        val policy = CapabilityPolicy(allowed = setOf("network"))
        val mgr = manager(policy)
        val loaded = mgr.loadAll()
        assertEquals(0, loaded.size)
    }
}
