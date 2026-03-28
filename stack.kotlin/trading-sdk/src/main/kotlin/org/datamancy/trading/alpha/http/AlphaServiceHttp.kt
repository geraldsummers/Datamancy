package org.datamancy.trading.alpha.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.Instant

data class AlphaServiceError(
    val error: String,
    val code: String? = null
)

data class AlphaServiceRoot(
    val service: String,
    val version: String,
    val endpoints: List<String>
)

object AlphaServiceJson {
    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(
            Instant::class.java,
            JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value?.toString()) }
        )
        .create()

    fun root(service: String, endpoints: List<String>): AlphaServiceRoot =
        AlphaServiceRoot(service = service, version = "1.0.0", endpoints = endpoints)
}
