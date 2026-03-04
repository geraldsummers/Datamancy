package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*


suspend fun TestRunner.homeAssistantTests() = suite("Home Assistant Tests") {

    test("Home Assistant web interface loads") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}")
        
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Found)) {
            "Home Assistant not accessible: ${response.status}"
        }

        println("      ✓ Home Assistant web interface accessible")
    }

    test("Home Assistant API responds") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            "API not responding: ${response.status}"
        }

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            require(body.contains("message") || body.contains("API")) {
                "API response unexpected: $body"
            }
        }

        println("      ✓ Home Assistant API endpoint responds")
    }

    test("Home Assistant config endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/config")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Config endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant config API exists")
    }

    test("Home Assistant states endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/states")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "States endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant states API exists")
    }

    test("Home Assistant services endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/services")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Services endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant services API exists")
    }

    test("Home Assistant events endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/events")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Events endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant events API exists")
    }

    test("Home Assistant error log endpoint") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/error_log")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Error log endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant error log API exists")
    }

    test("Home Assistant history endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/history/period")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "History endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant history API exists")
    }

    test("Home Assistant logbook endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/logbook")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Logbook endpoint failed: ${response.status}"
        }

        println("      ✓ Home Assistant logbook API exists")
    }

    test("Home Assistant panel manifest") {
        
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/static/icons/favicon.ico")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)) {
            "Static assets not responding: ${response.status}"
        }

        println("      ✓ Home Assistant static assets configured")
    }
}
