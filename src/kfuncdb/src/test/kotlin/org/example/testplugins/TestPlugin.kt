package org.example.testplugins

import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext

class TestPlugin : Plugin {
    var initCount: Int = 0
    var shutdownCount: Int = 0

    override fun init(context: PluginContext) {
        initCount++
    }

    override fun shutdown() {
        shutdownCount++
    }

    override fun tools(): List<Any> = listOf(Tools())

    class Tools {
        @LlmTool(
            name = "sum",
            shortDescription = "Add two integers",
            longDescription = "Return the arithmetic sum of two integer inputs.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"integer"},"b":{"type":"integer"}}}
            """
        )
        fun sum(a: Int, b: Int): Int = a + b

        @LlmTool(
            shortDescription = "Greet a person",
            longDescription = "Return a friendly greeting. If name is omitted, defaults to 'world'.",
            paramsSpec = """
            {"type":"object","properties":{"name":{"type":"string","default":"world"}}}
            """
        )
        fun greet(name: String = "world"): String = "Hello, $name"

        data class EchoDto(val x: Int, val ok: Boolean)

        @LlmTool(
            name = "echoDto",
            shortDescription = "Echo DTO fields",
            longDescription = "Accept a DTO with fields x and ok and return a string concatenation 'x:ok'.",
            paramsSpec = """
            {"type":"object","required":["x","ok"],"properties":{"x":{"type":"integer"},"ok":{"type":"boolean"}}}
            """
        )
        fun echo(dto: EchoDto): String = "${dto.x}:${dto.ok}"
    }
}
