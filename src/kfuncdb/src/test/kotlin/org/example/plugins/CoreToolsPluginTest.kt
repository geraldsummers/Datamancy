package org.example.plugins

import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.host.LoadedPlugin
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.util.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreToolsPluginTest {
    private fun loaded(): LoadedPlugin {
        val manifest = PluginManifest(
            id = "core",
            version = "0.1.0",
            apiVersion = "1.0.0",
            implementation = CoreToolsPlugin::class.qualifiedName!!,
            capabilities = emptyList(),
            requires = null
        )
        return LoadedPlugin(manifest, this::class.java.classLoader, CoreToolsPlugin())
    }

    @Test
    fun `discovers core tools`() {
        val reg = ToolRegistry()
        reg.registerFrom(loaded())
        val names = reg.listTools().map { it.name }.toSet()
        assertTrue(names.contains("normalize_whitespace"))
        assertTrue(names.contains("vector_dot"))
        assertTrue(names.contains("json_select_fields"))
    }

    @Test
    fun `text utilities work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        // normalize_whitespace
        var args: ObjectNode = Json.mapper.createObjectNode().put("text", "  a  b\t c\n")
        assertEquals("a b c", reg.invoke("normalize_whitespace", args))

        // slugify
        args = Json.mapper.createObjectNode().put("text", "Hello, Wörld!")
        assertEquals("hello-world", reg.invoke("slugify", args))

        // extract_regex_group
        args = Json.mapper.createObjectNode().put("text", "abc123xyz").put("pattern", "(\\d+)").put("group", 1)
        assertEquals("123", reg.invoke("extract_regex_group", args))

        // tokenize_words
        args = Json.mapper.createObjectNode().put("text", "Hello, world! 2x fun.")
        @Suppress("UNCHECKED_CAST")
        val toks = reg.invoke("tokenize_words", args) as List<String>
        assertEquals(listOf("Hello", "world", "2x", "fun"), toks)

        // truncate_text
        args = Json.mapper.createObjectNode().put("text", "Kotlin makes testing pleasant").put("maxChars", 12)
        assertEquals("Kotlin…", reg.invoke("truncate_text", args))

        // levenshtein_distance
        args = Json.mapper.createObjectNode().put("a", "kitten").put("b", "sitting")
        assertEquals(3, reg.invoke("levenshtein_distance", args))

        // jaccard_similarity
        args = Json.mapper.createObjectNode().put("a", "a b c").put("b", "b c d")
        val j = reg.invoke("jaccard_similarity", args) as Double
        assertTrue(j > 0.0 && j < 1.0)
    }

    @Test
    fun `json utilities work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        val obj = Json.mapper.createObjectNode().put("a", 1).put("b", 2).put("c", 3)
        var args: ObjectNode = Json.mapper.createObjectNode()
            .set<ObjectNode>("json", obj) as ObjectNode
        args.set<com.fasterxml.jackson.databind.JsonNode>("fields", Json.mapper.valueToTree(listOf("a", "c")))
        val sel = reg.invoke("json_select_fields", args) as ObjectNode
        assertEquals(2, sel.size())
        assertEquals(1, sel.get("a").asInt())
        assertEquals(3, sel.get("c").asInt())

        // json_get_path
        val nested = Json.mapper.readTree("""{"a":{"b":[{"c":42}]}}""")
        args = Json.mapper.createObjectNode()
            .set<ObjectNode>("json", nested) as ObjectNode
        args.put("path", "a.b[0].c")
        assertEquals("42", reg.invoke("json_get_path", args))

        // json_flatten
        val flat = reg.invoke("json_flatten", Json.mapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("json", nested) as ObjectNode) as ObjectNode
        assertTrue(flat.has("a.b[0].c"))
        assertEquals("42", flat.get("a.b[0].c").asText())
    }

    @Test
    fun `vector and stats utilities work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        var args: ObjectNode = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("a", Json.mapper.valueToTree(listOf(1.0, 2.0, 3.0)))
        args.set<com.fasterxml.jackson.databind.JsonNode>("b", Json.mapper.valueToTree(listOf(4.0, 5.0, 6.0)))
        val dot = reg.invoke("vector_dot", args) as Double
        assertEquals(32.0, dot, 1e-9)

        val cos = reg.invoke("cosine_similarity", args) as Double
        assertEquals(0.974631846, cos, 1e-6)

        // mean
        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("values", Json.mapper.valueToTree(listOf(2.0, 4.0, 6.0)))
        assertEquals(4.0, reg.invoke("mean", args) as Double, 1e-9)

        // moving_average
        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("values", Json.mapper.valueToTree(listOf(1.0,2.0,3.0,4.0)))
        args.put("window", 2)
        @Suppress("UNCHECKED_CAST")
        val ma = reg.invoke("moving_average", args) as List<Double>
        assertEquals(listOf(1.5, 2.5, 3.5), ma)

        // argmax/argmin
        args = Json.mapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("values", Json.mapper.valueToTree(listOf(1.0, 3.0, 2.0))) as ObjectNode
        assertEquals(1, reg.invoke("argmax", args))
        assertEquals(0, reg.invoke("argmin", args))
    }

    @Test
    fun `collections helpers work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        var args: ObjectNode = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("items", Json.mapper.valueToTree(listOf(1,2,3,4,5)))
        args.put("size", 2)
        @Suppress("UNCHECKED_CAST")
        val chunks = reg.invoke("chunk_list", args) as List<List<Int>>
        assertEquals(listOf(listOf(1,2), listOf(3,4), listOf(5)), chunks)

        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("items", Json.mapper.valueToTree(listOf(1,2,3,4)))
        args.put("window", 3)
        args.put("step", 1)
        @Suppress("UNCHECKED_CAST")
        val wins = reg.invoke("sliding_window", args) as List<List<Int>>
        assertEquals(listOf(listOf(1,2,3), listOf(2,3,4)), wins)

        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("items", Json.mapper.valueToTree(listOf(listOf(1,2), listOf(3))))
        @Suppress("UNCHECKED_CAST")
        val flat = reg.invoke("flatten_list", args) as List<Int>
        assertEquals(listOf(1,2,3), flat)

        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("a", Json.mapper.valueToTree(listOf(1,3,5)))
        args.set<com.fasterxml.jackson.databind.JsonNode>("b", Json.mapper.valueToTree(listOf(2,4)))
        @Suppress("UNCHECKED_CAST")
        val inter = reg.invoke("interleave_lists", args) as List<Int>
        assertEquals(listOf(1,2,3,4,5), inter)
    }

    @Test
    fun `time and algorithms and utilities work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        // add_duration_iso
        var args: ObjectNode = Json.mapper.createObjectNode().put("isoInstant", "2020-01-01T00:00:00Z").put("duration", "P1D")
        assertEquals("2020-01-02T00:00:00Z", reg.invoke("add_duration_iso", args))

        // binary_search_value
        args = Json.mapper.createObjectNode()
        args.set<com.fasterxml.jackson.databind.JsonNode>("sorted", Json.mapper.valueToTree(listOf(1,3,5,7)))
        args.put("target", 5)
        assertEquals(2, reg.invoke("binary_search_value", args))
        args.put("target", 6)
        assertEquals(-4, reg.invoke("binary_search_value", args))

        // safe_parse and clamp/map
        assertEquals(42, reg.invoke("safe_parse_int", Json.mapper.createObjectNode().put("text","42")) )
        assertEquals(0.0, (reg.invoke("safe_parse_double", Json.mapper.createObjectNode().put("text","x").put("default", 0.0)) as Double), 1e-9)
        val clampArgs = Json.mapper.createObjectNode().put("x", 15.0).put("minVal", 0.0).put("maxVal", 10.0)
        assertEquals(10.0, reg.invoke("clamp_value", clampArgs) as Double, 1e-9)
        val mapArgs = Json.mapper.createObjectNode().put("x", 5.0).put("fromMin", 0.0).put("fromMax", 10.0).put("toMin", 0.0).put("toMax", 100.0)
        assertEquals(50.0, reg.invoke("map_range", mapArgs) as Double, 1e-9)

        // uuid and hash
        val uuid = reg.invoke("uuid_generate", Json.mapper.createObjectNode()) as String
        assertTrue(uuid.contains('-'))
        val hargs = Json.mapper.createObjectNode().put("text","abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", reg.invoke("hash_sha256", hargs))
    }

    @Test
    fun `reasoning helpers work`() {
        val reg = ToolRegistry(); reg.registerFrom(loaded())
        var args = Json.mapper.createObjectNode().put("a", "same").put("b", "same")
        val s1 = reg.invoke("compare_strings_fuzzy", args) as Double
        assertTrue(s1 > 0.99)
        args = Json.mapper.createObjectNode().put("a", "apple").put("b", "zebra")
        val s2 = reg.invoke("compare_strings_fuzzy", args) as Double
        assertTrue(s2 < s1)

        val sumArgs = Json.mapper.createObjectNode().set("values", Json.mapper.valueToTree(listOf(1.0, 2.0, 3.0))) as ObjectNode
        @Suppress("UNCHECKED_CAST")
        val summary = reg.invoke("summarize_list", sumArgs) as Map<String, Any>
        assertEquals(3, summary["count"])
        assertEquals(1.0, summary["min"])
        assertEquals(3.0, summary["max"])
        assertEquals(2.0, summary["mean"])
    }
}
