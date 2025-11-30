package org.datamancy.speech

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val WHISPER_BASE_URL = System.getenv("WHISPER_BASE_URL") ?: "http://whisper:9000"
private val PIPER_BASE_URL = System.getenv("PIPER_BASE_URL") ?: "http://piper:8080"

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    explicitNulls = false
}

fun main() {
    val port = (System.getenv("PORT") ?: "8091").toInt()
    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(json) }
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(json) }
        routing {
            get("/healthz") { call.respond(mapOf("ok" to true)) }

            // OpenAI-style: POST /v1/audio/transcriptions (Whisper)
            post("/v1/audio/transcriptions") {
                try {
                    val mp = call.receiveMultipart()
                    var audioBytes: ByteArray? = null
                    var fileName: String = "audio.wav"
                    mp.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                val name = part.name ?: "file"
                                if (name == "file" || name == "audio" || name == "audio_file") {
                                    fileName = part.originalFileName ?: fileName
                                    audioBytes = part.streamProvider().readBytes()
                                }
                            }
                            else -> {}
                        }
                        try { part.dispose() } catch (_: Throwable) {}
                    }

                    if (audioBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing audio file"))
                        return@post
                    }

                    val formData = formData {
                        append(
                            key = "audio_file",
                            value = audioBytes!!,
                            headers = io.ktor.http.Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"audio_file\"; filename=\"$fileName\"")
                            }
                        )
                    }

                    val respText = client.submitFormWithBinaryData(
                        url = "$WHISPER_BASE_URL/asr?task=transcribe",
                        formData = formData
                    ).bodyAsText()

                    val txt = try {
                        json.parseToJsonElement(respText).jsonObject["text"]?.jsonPrimitive?.content
                    } catch (_: Exception) { null } ?: ""

                    call.respond(mapOf("text" to txt))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed")))
                }
            }

            // Simple TTS endpoint: POST /v1/audio/speech (Piper)
            post("/v1/audio/speech") {
                try {
                    val body = call.receiveText()
                    val jobj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
                    val text = jobj["input"]?.jsonPrimitive?.content
                        ?: jobj["text"]?.jsonPrimitive?.content
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing input text"))
                    val voice = jobj["voice"]?.jsonPrimitive?.content ?: System.getenv("PIPER_VOICE") ?: "en_US-lessac-medium"

                    val upstreamResp: HttpResponse = client.post("$PIPER_BASE_URL/api/tts") {
                        url { parameters.append("voice", voice) }
                        contentType(ContentType.Application.Json)
                        setBody(kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) })
                    }
                    val audio = upstreamResp.bodyAsBytes()
                    call.respondBytes(bytes = audio, contentType = ContentType.parse("audio/wav"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed")))
                }
            }
        }
    }.start(wait = true)
}
