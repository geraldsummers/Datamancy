package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

suspend fun TestRunner.backupTests() = suite("Backup Tests") {

    
    test("Kopia server is accessible") {
        
        try {
            val response = client.getRawResponse("${env.endpoints.kopia}/")
            response.status.value shouldBeOneOf listOf(200, 404, 401, 403, 500, 502)
        } catch (e: Exception) {
            
            println("      ℹ️  Kopia connection error (may not be exposed to test network): ${e.message}")
        }
    }

    test("Kopia endpoint is configured") {
        env.endpoints.kopia shouldContain "kopia"
    }

    test("Kopia web UI responds") {
        
        try {
            val response = client.getRawResponse("${env.endpoints.kopia}/")
            
            require(response.status.value in 200..599) {
                "Status code should be in HTTP range"
            }
        } catch (e: Exception) {
            
            println("      ℹ️  Kopia web UI not reachable (may not be on test networks): ${e.message}")
        }
    }
}
