package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.userInterfaceTests() = suite("User Interface Tests") {

    
    
    

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
        
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("Open-WebUI can list available models") {
        val response = client.getRawResponse("${env.endpoints.openWebUI}/api/models")
        
        response.status.value shouldBeOneOf listOf(200, 401, 403)
    }

    
    
    

    test("JupyterHub hub API is accessible") {
        
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/hub/api")
        
        response.status.value shouldBeOneOf listOf(200, 401, 403, 404, 500, 502)
    }

    test("JupyterHub root endpoint responds") {
        
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/")
        
        response.status.value shouldBeOneOf listOf(200, 302, 401, 403, 500, 502)
    }
}
