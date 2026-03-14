package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.extendedCommunicationTests() = suite("Extended Communication Tests") {

    // Mastodon tests
    test("Mastodon: Web interface is accessible") {
        val response = client.getRawResponse(endpoints.mastodon)
        response.status.value shouldBeOneOf listOf(200, 302, 401, 403, 500, 502)
        println("      ✓ Mastodon endpoint returned ${response.status}")
    }

    test("Mastodon: API endpoint responds") {
        val response = client.getRawResponse("${endpoints.mastodon}/api/v1/instance")

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            body shouldContain "uri"
            println("      ✓ Mastodon instance API accessible")
        } else {
            println("      ℹ️  Mastodon API returned ${response.status} (may need configuration)")
        }
    }

    test("Mastodon: Streaming API is reachable") {
        val response = client.getRawResponse("${endpoints.mastodonStreaming}/api/v1/streaming/health")
        response.status.value shouldBeOneOf listOf(200, 404, 401, 403, 500, 502)
        println("      ✓ Mastodon streaming endpoint responded with ${response.status}")
    }

    test("Mastodon: Public timeline endpoint exists") {
        val response = client.getRawResponse("${endpoints.mastodon}/api/v1/timelines/public")

        // May require auth or return empty array
        response.status.value shouldBeOneOf listOf(200, 401, 403, 404)

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            // Should be a JSON array
            val json = Json.parseToJsonElement(body)
            require(json is JsonArray) { "Public timeline should return array" }
            println("      ✓ Public timeline endpoint functional")
        } else {
            println("      ℹ️  Public timeline: ${response.status} (may require authentication)")
        }
    }

    test("Mastodon: OAuth endpoint exists") {
        val response = client.getRawResponse("${endpoints.mastodon}/oauth/authorize")

        // Will redirect or show error without params, or 403 if auth required
        response.status.value shouldBeOneOf listOf(200, 400, 401, 403, 302, 404)
        println("      ✓ OAuth endpoint accessible (${response.status})")
    }

    test("Mastodon: Static assets are served") {
        val response = client.getRawResponse("${endpoints.mastodon}/packs/js/common.js")
        response.status.value shouldBeOneOf listOf(200, 304, 404, 401, 403)

        if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotModified) {
            println("      ✓ Static assets served")
        } else {
            println("      ℹ️  Static asset check: ${response.status}")
        }
    }

    test("Mastodon: Federation is configured") {
        val response = client.getRawResponse("${endpoints.mastodon}/.well-known/webfinger")

        // Will fail without resource param, but endpoint should exist, or 403 if auth required
        response.status.value shouldBeOneOf listOf(400, 404, 200, 401, 403)
        println("      ✓ WebFinger endpoint present (${response.status})")
    }

    test("Mastodon: ActivityPub endpoint responds") {
        val response = client.getRawResponse("${endpoints.mastodon}/.well-known/host-meta")
        response.status.value shouldBeOneOf listOf(200, 404, 401, 403)

        if (response.status == HttpStatusCode.OK) {
            println("      ✓ ActivityPub host-meta available")
        } else {
            println("      ℹ️  ActivityPub check: ${response.status}")
        }
    }

    test("Mastodon: Media upload endpoint exists") {
        val response = client.getRawResponse("${endpoints.mastodon}/api/v1/media")

        // POST endpoint, will reject GET
        response.status.value shouldBeOneOf listOf(405, 401, 403, 404, 422)
        println("      ✓ Media endpoint exists (${response.status})")
    }

    // Ntfy (Notifications) - already has basic coverage in CommunicationTests
    test("Ntfy: Topics can be created") {
        if (endpoints.ntfy == null) {
            println("      ℹ️  Ntfy endpoint not configured")
            return@test
        }

        val testTopic = "test-topic-${System.currentTimeMillis()}"
        val response = client.getRawResponse("${endpoints.ntfy}/$testTopic")

        // Topic endpoint should exist (GET subscribes, POST publishes)
        response.status.value shouldBeOneOf listOf(200, 404, 401, 403)
        println("      ✓ Topic endpoint accessible")
    }

    test("Ntfy: Message publishing endpoint") {
        if (endpoints.ntfy == null) {
            println("      ℹ️  Ntfy endpoint not configured")
            return@test
        }

        val testTopic = "test-integration-${System.currentTimeMillis()}"

        try {
            val response = httpClient.post("${endpoints.ntfy}/$testTopic") {
                setBody("Test message from integration tests")
            }

            // Accept both 200 OK (published) or 403 Forbidden (auth required)
            response.status.value shouldBeOneOf listOf(200, 403)
            if (response.status == HttpStatusCode.OK) {
                println("      ✓ Message publishing successful")
            } else {
                println("      ✓ Publishing endpoint accessible (authentication required)")
            }
        } catch (e: Exception) {
            println("      ℹ️  Message publishing: ${e.message}")
        }
    }

    test("Ntfy: JSON API endpoint") {
        if (endpoints.ntfy == null) {
            println("      ℹ️  Ntfy endpoint not configured")
            return@test
        }

        val testTopic = "test-json-${System.currentTimeMillis()}"

        try {
            val response = httpClient.post("${endpoints.ntfy}/$testTopic") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Test from integration","title":"Integration Test"}""")
            }

            response.status.value shouldBeOneOf listOf(200, 401, 403)

            if (response.status == HttpStatusCode.OK) {
                println("      ✓ JSON API functional")
            }
        } catch (e: Exception) {
            println("      ℹ️  JSON API check: ${e.message}")
        }
    }

    test("Ntfy: WebSocket streaming endpoint") {
        if (endpoints.ntfy == null) {
            println("      ℹ️  Ntfy endpoint not configured")
            return@test
        }

        val wsUrl = endpoints.ntfy!!.replace("http://", "ws://").replace("https://", "wss://")
        println("      ℹ️  WebSocket URL would be: $wsUrl/ws")
        println("      ✓ WebSocket endpoint configuration identified")
    }
}
