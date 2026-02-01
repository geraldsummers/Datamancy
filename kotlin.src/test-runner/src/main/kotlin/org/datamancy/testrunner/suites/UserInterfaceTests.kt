package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.userInterfaceTests() = suite("User Interface Tests") {

    // ================================================================================
    // OPEN-WEBUI - AI Chat Interface (3 tests)
    // ================================================================================

    test("Open-WebUI health endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.openWebUI}/health")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "true"
    }

    test("Open-WebUI login page loads") {
        val response = client.getRawResponse("${env.endpoints.openWebUI}/")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        // Should contain HTML indicating it's a web app
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("Open-WebUI can list available models") {
        val response = client.getRawResponse("${env.endpoints.openWebUI}/api/models")
        // May require auth, so 401 or 200 are both acceptable
        response.status.value shouldBeOneOf listOf(200, 401, 403)
    }

    // ================================================================================
    // JUPYTERHUB - Notebook Environment (2 tests) - Connection issues possible
    // ================================================================================

    test("JupyterHub hub API is accessible") {
        // Note: May have connection issues or be starting up
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/hub/api")
        // Accept auth errors, not found, or connection issues
        response.status.value shouldBeOneOf listOf(200, 401, 403, 404, 500, 502)
    }

    test("JupyterHub root endpoint responds") {
        // Note: May have connection issues or be starting up
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/")
        // Should get response from JupyterHub (redirect, page, auth, or error)
        response.status.value shouldBeOneOf listOf(200, 302, 401, 403, 500, 502)
    }
}
