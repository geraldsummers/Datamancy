package org.example.http

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.example.host.ToolRegistry
import org.example.util.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LlmHttpServer(private val port: Int, private val tools: ToolRegistry) {
    private var server: HttpServer? = null

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/tools", ToolsHandler(tools))
        srv.createContext("/call-tool", CallToolHandler(tools))
        srv.createContext("/healthz", HealthHandler())
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        println("HTTP server started on http://localhost:${'$'}port")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** Returns the actual bound TCP port once started (useful when starting on port 0). */
    fun boundPort(): Int? = server?.address?.port
}

private class ToolsHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            respond(exchange, 200, tools.listTools())
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private data class CallRequest(val name: String, val args: JsonNode?)

private class CallToolHandler(private val tools: ToolRegistry) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val req = Json.mapper.readValue(body, CallRequest::class.java)
            val args = req.args ?: Json.mapper.createObjectNode()
            val result = tools.invoke(req.name, args)
            respond(exchange, 200, mapOf("result" to result))
        } catch (nf: NoSuchElementException) {
            respond(exchange, 404, mapOf("error" to nf.message))
        } catch (iae: IllegalArgumentException) {
            respond(exchange, 400, mapOf("error" to iae.message))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: "internal error")))
        }
    }
}

private fun respond(exchange: HttpExchange, status: Int, payload: Any) {
    val bytes = Json.mapper.writeValueAsBytes(payload)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private class HealthHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return
            }
            respond(exchange, 200, mapOf("status" to "ok"))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("status" to "error", "message" to (e.message ?: "internal error")))
        }
    }
}
