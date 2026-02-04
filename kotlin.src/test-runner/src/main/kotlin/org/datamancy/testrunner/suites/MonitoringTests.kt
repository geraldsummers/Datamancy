package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.monitoringTests() = suite("Monitoring Tests") {

    // PROMETHEUS (3 tests)
    test("Prometheus server is healthy") {
        val response = client.getRawResponse("${env.endpoints.prometheus}/-/healthy")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Prometheus can execute PromQL query") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "success"
    }

    test("Prometheus targets endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.prometheus}/api/v1/targets")
        response.status shouldBe HttpStatusCode.OK
    }

    // GRAFANA (2 tests)
    test("Grafana server is healthy") {
        val response = client.getRawResponse("${env.endpoints.grafana}/api/health")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "ok"
    }

    test("Grafana login page loads") {
        val response = client.getRawResponse("${env.endpoints.grafana}/login")
        response.status shouldBe HttpStatusCode.OK
    }

    // METRICS EXPORTERS
    test("Node Exporter metrics endpoint") {
        val response = client.getRawResponse("http://node-exporter:9100/metrics")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "node_"  // Node exporter metrics prefix
        println("      ✓ Node Exporter providing system metrics")
    }

    test("cAdvisor metrics endpoint") {
        val response = client.getRawResponse("http://cadvisor:8080/metrics")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "container_"  // cAdvisor metrics prefix
        println("      ✓ cAdvisor providing container metrics")
    }

    test("Prometheus scraping node-exporter") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up{job=\"node-exporter\"}")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        // Should show node-exporter is being scraped
        body shouldContain "node-exporter"
        println("      ✓ Prometheus scraping node-exporter")
    }

    test("Prometheus scraping cadvisor") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up{job=\"cadvisor\"}")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        // Should show cadvisor is being scraped
        body shouldContain "cadvisor"
        println("      ✓ Prometheus scraping cadvisor")
    }

    // DOZZLE LOG VIEWER
    test("Dozzle web interface accessible") {
        val response = client.getRawResponse("http://dozzle:8080")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        require(body.contains("dozzle") || body.contains("log") || body.contains("<html")) {
            "Dozzle interface not detected"
        }
        println("      ✓ Dozzle log viewer accessible")
    }

    test("Dozzle healthcheck endpoint") {
        val response = client.getRawResponse("http://dozzle:8080/healthcheck")
        // Dozzle may or may not have a dedicated health endpoint
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
            "Dozzle not responding: ${response.status}"
        }
        println("      ✓ Dozzle server responding")
    }

    // ALERTMANAGER
    test("AlertManager status endpoint") {
        // AlertManager v0.28.1 uses /-/ready instead of deprecated /api/v2/status
        val response = client.getRawResponse("http://alertmanager:9093/-/ready")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ AlertManager ready endpoint accessible")
    }

    test("AlertManager alerts endpoint") {
        // Use v1 API which is stable, v2 endpoints were deprecated
        val response = client.getRawResponse("http://alertmanager:9093/api/v1/alerts")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ AlertManager alerts API accessible")
    }
}
