package org.datamancy.controlpanel

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import org.datamancy.controlpanel.api.*
import org.datamancy.controlpanel.services.MockDatabaseService
import org.datamancy.controlpanel.services.ProxyService

fun Application.testModule() {
    install(ContentNegotiation) {
        json()
    }

    val mockDatabase = MockDatabaseService()
    val mockProxy = ProxyService(
        dataFetcherUrl = "http://localhost:8095",
        indexerUrl = "http://localhost:8096",
        searchUrl = "http://localhost:8000"
    )

    routing {
        route("/api/config") { configureConfigApi(mockDatabase) }
        route("/api/fetcher") { configureFetcherApi(mockProxy, mockDatabase) }
        route("/api/storage") { configureStorageApi(mockDatabase) }
        route("/api/logs") { configureLogsApi(mockDatabase) }
    }
}
