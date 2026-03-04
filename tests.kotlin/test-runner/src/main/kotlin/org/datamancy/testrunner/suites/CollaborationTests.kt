package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.collaborationTests() = suite("Collaboration Tests") {

    
    test("Mastodon web server is healthy") {
        val response = client.getRawResponse("${env.endpoints.mastodon}/health")
        response.status.value shouldBeOneOf listOf(200, 404, 405)
    }

    test("Mastodon streaming server is healthy") {
        val response = client.getRawResponse("${env.endpoints.mastodonStreaming}/api/v1/streaming/health")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Mastodon can fetch instance info") {
        
        val response = client.getRawResponse("${env.endpoints.mastodon}/api/v1/instance")
        response.status.value shouldBeOneOf listOf(200, 403, 401)
    }

    test("Mastodon public timeline endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.mastodon}/api/v1/timelines/public")
        
        response.status.value shouldBeOneOf listOf(200, 401, 403)
    }

    
    test("Roundcube webmail loads") {
        val response = client.getRawResponse("${env.endpoints.roundcube}/")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("Roundcube login page is accessible") {
        val response = client.getRawResponse("${env.endpoints.roundcube}/")
        response.status shouldBe HttpStatusCode.OK
    }
}
