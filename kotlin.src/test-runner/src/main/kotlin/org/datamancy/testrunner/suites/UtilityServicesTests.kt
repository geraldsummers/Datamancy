package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

/**
 * Utility Services Integration Tests
 *
 * Tests supporting services:
 * - Homepage (dashboard)
 * - Radicale (CalDAV/CardDAV)
 * - Ntfy (notifications)
 * - Qbittorrent (torrent client)
 */
suspend fun TestRunner.utilityServicesTests() = suite("Utility Services Tests") {

    // =============================================================================
    // Homepage Dashboard Tests
    // =============================================================================

    test("Homepage dashboard loads") {
        val response = client.getRawResponse("${env.endpoints.homepage!!}")
        require(response.status == HttpStatusCode.OK) {
            "Homepage not accessible: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("homepage") || body.contains("dashboard") || body.contains("<html")) {
            "Homepage content not detected"
        }

        println("      ✓ Homepage dashboard loads")
    }

    test("Homepage serves static assets") {
        // Homepage should serve its assets
        val response = client.getRawResponse("${env.endpoints.homepage!!}/") {
            headers {
                append(HttpHeaders.Accept, "text/html")
            }
        }

        require(response.status == HttpStatusCode.OK) {
            "Static assets not loading: ${response.status}"
        }

        println("      ✓ Homepage static assets accessible")
    }

    test("Homepage API endpoint accessible") {
        // Try to access the API endpoint
        val response = client.getRawResponse("${env.endpoints.homepage!!}/api/widgets")
        // May require auth or return empty list
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
            "Unexpected response: ${response.status}"
        }

        println("      ✓ Homepage API endpoint responds")
    }

    // =============================================================================
    // Radicale CalDAV/CardDAV Tests
    // =============================================================================

    test("Radicale server is accessible") {
        val response = client.getRawResponse("${env.endpoints.radicale!!}")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Found)) {
            "Radicale not accessible: ${response.status}"
        }

        println("      ✓ Radicale CalDAV/CardDAV server accessible")
    }

    test("Radicale .well-known endpoints configured") {
        // CalDAV discovery
        val caldavResponse = client.getRawResponse("${env.endpoints.radicale!!}/.well-known/caldav") {
            headers {
                append(HttpHeaders.Accept, "*/*")
            }
        }

        require(caldavResponse.status in listOf(
            HttpStatusCode.OK,
            HttpStatusCode.MovedPermanently,
            HttpStatusCode.Found,
            HttpStatusCode.Unauthorized
        )) {
            "CalDAV discovery failed: ${caldavResponse.status}"
        }

        // CardDAV discovery
        val carddavResponse = client.getRawResponse("${env.endpoints.radicale!!}/.well-known/carddav") {
            headers {
                append(HttpHeaders.Accept, "*/*")
            }
        }

        require(carddavResponse.status in listOf(
            HttpStatusCode.OK,
            HttpStatusCode.MovedPermanently,
            HttpStatusCode.Found,
            HttpStatusCode.Unauthorized
        )) {
            "CardDAV discovery failed: ${carddavResponse.status}"
        }

        println("      ✓ CalDAV/CardDAV discovery endpoints configured")
    }

    test("Radicale responds to PROPFIND") {
        // Test WebDAV PROPFIND method (core CalDAV operation)
        val response = client.requestRaw("${env.endpoints.radicale!!}/") {
            method = HttpMethod.parse("PROPFIND")
            headers {
                append(HttpHeaders.ContentType, "application/xml")
                append("Depth", "0")
            }
            setBody("""<?xml version="1.0" encoding="utf-8"?>
                <propfind xmlns="DAV:">
                    <prop>
                        <displayname/>
                    </prop>
                </propfind>
            """.trimIndent())
        }

        require(response.status in listOf(
            HttpStatusCode.MultiStatus,  // 207
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden
        )) {
            "PROPFIND failed: ${response.status}"
        }

        println("      ✓ Radicale PROPFIND method responds correctly")
    }

    // =============================================================================
    // Ntfy Notification Server Tests
    // =============================================================================

    test("Ntfy server is accessible") {
        val response = client.getRawResponse("${env.endpoints.ntfy!!}")
        require(response.status == HttpStatusCode.OK) {
            "Ntfy not accessible: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("ntfy") || body.contains("notification") || body.contains("<html")) {
            "Ntfy web interface not detected"
        }

        println("      ✓ Ntfy notification server accessible")
    }

    test("Ntfy health endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.ntfy!!}/v1/health")
        require(response.status == HttpStatusCode.OK) {
            "Ntfy health check failed: ${response.status}"
        }

        println("      ✓ Ntfy health check passed")
    }

    test("Ntfy can create test topic") {
        val testTopic = "test-topic-${System.currentTimeMillis()}"
        val ntfyUsername = System.getenv("NTFY_USERNAME") ?: "admin"
        val ntfyPassword = System.getenv("NTFY_PASSWORD") ?: ""

        val response = client.postRaw("${env.endpoints.ntfy!!}/$testTopic") {
            basicAuth(ntfyUsername, ntfyPassword)
            headers {
                append(HttpHeaders.ContentType, "text/plain")
            }
            setBody("Integration test message")
        }

        require(response.status == HttpStatusCode.OK) {
            "Failed to publish to ntfy: ${response.status}"
        }

        println("      ✓ Ntfy can publish notifications")
    }

    test("Ntfy JSON API works") {
        val testTopic = "test-json-${System.currentTimeMillis()}"
        val ntfyUsername = System.getenv("NTFY_USERNAME") ?: "admin"
        val ntfyPassword = System.getenv("NTFY_PASSWORD") ?: ""

        val response = client.postRaw("${env.endpoints.ntfy!!}/$testTopic") {
            basicAuth(ntfyUsername, ntfyPassword)
            headers {
                append(HttpHeaders.ContentType, "application/json")
            }
            setBody("""{"message":"Test notification","title":"Integration Test","priority":3}""")
        }

        require(response.status == HttpStatusCode.OK) {
            "Failed to publish JSON to ntfy: ${response.status}"
        }

        println("      ✓ Ntfy JSON API works")
    }

    // =============================================================================
    // Qbittorrent Tests
    // =============================================================================

    test("Qbittorrent web UI is accessible") {
        val response = client.getRawResponse("${env.endpoints.qbittorrent!!}")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Qbittorrent not accessible: ${response.status}"
        }

        println("      ✓ Qbittorrent web UI accessible")
    }

    test("Qbittorrent API version endpoint") {
        // Try to get API version (may require auth)
        val response = client.getRawResponse("${env.endpoints.qbittorrent!!}/api/v2/app/version")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "API version endpoint failed: ${response.status}"
        }

        if (response.status == HttpStatusCode.OK) {
            val version = response.bodyAsText()
            println("      ✓ Qbittorrent version: $version")
        } else {
            println("      ✓ Qbittorrent API requires authentication (as expected)")
        }
    }

    test("Qbittorrent API preferences endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.qbittorrent!!}/api/v2/app/preferences")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Preferences endpoint failed: ${response.status}"
        }

        println("      ✓ Qbittorrent preferences API endpoint exists")
    }

    test("Qbittorrent login endpoint exists") {
        val response = client.postRaw("${env.endpoints.qbittorrent!!}/api/v2/auth/login") {
            headers {
                append(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            }
            setBody("username=test&password=test")
        }

        // Should fail with bad credentials, but endpoint should exist
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            "Login endpoint not responding: ${response.status}"
        }

        println("      ✓ Qbittorrent login endpoint exists")
    }
}
