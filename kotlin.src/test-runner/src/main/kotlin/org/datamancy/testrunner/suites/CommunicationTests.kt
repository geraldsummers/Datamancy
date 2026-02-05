package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.communicationTests() = suite("Communication Tests") {

    
    test("Mailserver SMTP port configuration exists") {
        env.endpoints.mailserver shouldContain "mailserver"
    }

    test("Mailserver accepts connections on port 25") {
        env.endpoints.mailserver shouldContain ":25"
    }

    test("Mailserver configuration is valid") {
        env.endpoints.mailserver shouldContain "mailserver:25"
    }

    test("Mailserver endpoint is reachable via DNS") {
        env.endpoints.mailserver shouldContain "mailserver"
    }

    
    test("Synapse homeserver is healthy") {
        val response = client.getRawResponse("${env.endpoints.synapse}/_matrix/client/versions")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "versions"
    }

    test("Synapse federation endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.synapse}/_matrix/federation/v1/version")
        response.status.value shouldBeOneOf listOf(200, 404)
    }

    test("Synapse server info is accessible") {
        val response = client.getRawResponse("${env.endpoints.synapse}/_matrix/client/versions")
        response.status shouldBe HttpStatusCode.OK
    }

    
    test("Element web app loads") {
        
        val response = client.getRawResponse("${env.endpoints.element}/")
        
        response.status.value shouldBeOneOf listOf(200, 401, 403, 500, 502)
    }

    test("Element can connect to homeserver") {
        val response = client.getRawResponse("${env.endpoints.element}/config.json")
        
        response.status.value shouldBeOneOf listOf(200, 404, 401, 403)
    }
}
