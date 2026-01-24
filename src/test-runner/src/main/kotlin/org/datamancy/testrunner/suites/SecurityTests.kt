package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.securityTests() = suite("Security Tests") {

    // VAULTWARDEN (3 tests)
    test("Vaultwarden server is healthy") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/alive")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Vaultwarden API endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/api/")
        response.status.value shouldBeOneOf listOf(200, 404)
    }

    test("Vaultwarden web vault loads") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }
}
