package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JupyterHubConfigTest {

    @Test
    fun `jupyterhub spawner uses configured notebook image`() {
        val text = jupyterHubConfigText()

        assertTrue(
            text.contains("c.DockerSpawner.image = os.environ.get('JUPYTER_NOTEBOOK_IMAGE', 'datamancy-jupyter-notebook:5.4.3')"),
            "JupyterHub should spawn the configured notebook image so notebook runtime matches the built stack image"
        )
        assertFalse(
            text.contains("c.DockerSpawner.image = 'datamancy-jupyter-notebook:latest'"),
            "JupyterHub should not hardcode the notebook image to :latest because that breaks notebook/runtime parity"
        )
    }

    private fun jupyterHubConfigText(): String {
        val config = findRepoRoot().resolve("stack.containers/jupyterhub/jupyterhub_config.py")
        return Files.readString(config)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.containers/jupyterhub/jupyterhub_config.py"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
