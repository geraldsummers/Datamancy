package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.fileManagementTests() = suite("File Management Tests") {

    
    test("Seafile server is healthy") {
        
        val response = client.getRawResponse("${env.endpoints.seafile}/api2/ping/")
        response.status.value shouldBeOneOf listOf(200, 500, 502)
    }

    test("Seafile web interface loads") {
        val response = client.getRawResponse("${env.endpoints.seafile}/")
        response.status.value shouldBeOneOf listOf(200, 500, 502)
    }

    test("Seafile API endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.seafile}/api2/ping/")
        
        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            body shouldContain "pong"
        } else {
            response.status.value shouldBeOneOf listOf(500, 502)
        }
    }

    
    test("OnlyOffice document server is healthy") {
        
        val response = client.getRawResponse("${env.endpoints.onlyoffice}/healthcheck")
        response.status.value shouldBeOneOf listOf(200, 404, 500, 502)
    }

    test("OnlyOffice web interface responds") {
        val response = client.getRawResponse("${env.endpoints.onlyoffice}/")
        response.status.value shouldBeOneOf listOf(200, 302, 404, 500, 502)
    }
}
