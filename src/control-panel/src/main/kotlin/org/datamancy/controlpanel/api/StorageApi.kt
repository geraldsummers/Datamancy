package org.datamancy.controlpanel.api

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.configureStorageApi() {
    get("/overview") {
        call.respond(
            mapOf(
                "postgres" to mapOf(
                    "sizeGB" to 0.0,
                    "tables" to emptyMap<String, Any>()
                ),
                "clickhouse" to mapOf(
                    "sizeGB" to 0.0,
                    "tables" to emptyMap<String, Any>()
                ),
                "qdrant" to mapOf(
                    "sizeGB" to 0.0,
                    "collections" to emptyMap<String, Any>()
                )
            )
        )
    }

    get("/timeseries") {
        call.respond(emptyList<Map<String, Any>>())
    }
}
