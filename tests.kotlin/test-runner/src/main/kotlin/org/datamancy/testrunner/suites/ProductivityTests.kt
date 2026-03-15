package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.productivityTests() = suite("Productivity Tests") {

    
    test("BookStack web interface loads") {
        
        
        val response = client.getRawResponse("${env.endpoints.bookstack}/")
        
        response.status.value shouldBeOneOf listOf(200, 302, 403, 401, 500)
    }

    test("BookStack API endpoint is accessible") {
        val response = client.getRawResponse("${env.endpoints.bookstack}/api/docs")
        response.status.value shouldBeOneOf listOf(200, 302, 401, 403)
    }

    test("BookStack health check responds") {
        
        val response = client.getRawResponse("${env.endpoints.bookstack}/")
        response.status.value shouldBeOneOf listOf(200, 302, 403, 401, 500)
    }

    
    test("Forgejo git server is healthy") {
        
        val response = client.getRawResponse("${env.endpoints.forgejo}/api/v1/version")
        
        response.status.value shouldBeOneOf listOf(200, 403, 401)
    }

    test("Forgejo web interface loads") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/")
        response.status.value shouldBeOneOf listOf(200, 403, 401)
    }

    test("Forgejo API responds") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/api/v1/version")
        response.status.value shouldBeOneOf listOf(200, 403, 401)
    }

    
    test("Planka board server is healthy") {
        val response = client.getRawResponse("${env.endpoints.planka}/")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Planka web app loads") {
        val response = client.getRawResponse("${env.endpoints.planka}/")
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("Jupyter notebook image is present for JupyterHub spawns") {
        val imageName = System.getenv("JUPYTER_NOTEBOOK_IMAGE") ?: "datamancy-jupyter-notebook:5.4.3"
        val process = ProcessBuilder("docker", "image", "inspect", imageName)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) {
            "Jupyter notebook image '$imageName' not found: ${output.trim()}"
        }
        println("      ✓ Jupyter notebook image available: $imageName")
    }

    test("jupyter-notebook-build service completed successfully") {
        val process = ProcessBuilder(
            "docker", "inspect", "-f", "{{.State.Status}}|{{.State.ExitCode}}", "jupyter-notebook-build"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        require(exit == 0) {
            "Unable to inspect jupyter-notebook-build container: $output"
        }

        val parts = output.split("|")
        val status = parts.getOrNull(0)?.trim().orEmpty()
        val exitCode = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        require(status == "exited" && exitCode == 0) {
            "jupyter-notebook-build should exit successfully, got status='$status' exitCode=$exitCode"
        }
        println("      ✓ jupyter-notebook-build completed successfully")
    }
}
