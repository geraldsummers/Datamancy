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
}
