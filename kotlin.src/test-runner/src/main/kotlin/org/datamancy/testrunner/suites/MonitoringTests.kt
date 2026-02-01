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
}
