package org.example.plugins

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.example.plugins.CoreToolsPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import kotlin.test.assertFailsWith

@DisplayName("CoreToolsPlugin Tests")
class CoreToolsPluginTest {
    private val tools = CoreToolsPlugin.Tools()

    @Nested
    @DisplayName("Text Processing Tools")
    inner class TextProcessingTests {

        @Test
        fun `normalize_whitespace collapses spaces`() {
            assertEquals("hello world", tools.normalize_whitespace("hello   world"))
            assertEquals("hello world", tools.normalize_whitespace("  hello   world  "))
            assertEquals("a b c", tools.normalize_whitespace("a\t\tb\n\nc"))
        }

        @Test
        fun `normalize_whitespace handles empty string`() {
            assertEquals("", tools.normalize_whitespace(""))
            assertEquals("", tools.normalize_whitespace("   "))
        }

        @Test
        fun `slugify converts to url-friendly format`() {
            assertEquals("hello-world", tools.slugify("Hello World"))
            assertEquals("cafe", tools.slugify("Café"))
            assertEquals("test-123", tools.slugify("Test 123"))
            assertEquals("hello-world", tools.slugify("hello___world"))
        }

        @Test
        fun `slugify handles special characters`() {
            assertEquals("hello-world", tools.slugify("hello@#$%world"))
            assertEquals("test", tools.slugify("!@#test!@#"))
            assertEquals("", tools.slugify("!@#$%"))
        }

        @Test
        fun `extract_regex_group finds matches`() {
            assertEquals("123", tools.extract_regex_group("abc123def", "\\d+"))
            assertEquals("abc", tools.extract_regex_group("abc123def", "([a-z]+)", 1))
            assertEquals("", tools.extract_regex_group("abc", "\\d+"))
        }

        @Test
        fun `extract_regex_group handles capture groups`() {
            val text = "email: test@example.com"
            assertEquals("test@example.com", tools.extract_regex_group(text, "([\\w.]+@[\\w.]+)", 1))
        }

        @Test
        fun `split_sentences splits on terminal punctuation`() {
            val result = tools.split_sentences("Hello world. How are you? I am fine!")
            assertEquals(listOf("Hello world.", "How are you?", "I am fine!"), result)
        }

        @Test
        fun `split_sentences handles empty and single sentence`() {
            assertEquals(emptyList<String>(), tools.split_sentences(""))
            assertEquals(listOf("Hello world"), tools.split_sentences("Hello world"))
        }

        @Test
        fun `tokenize_words extracts word tokens`() {
            val result = tools.tokenize_words("Hello, world! It's a test.")
            assertEquals(listOf("Hello", "world", "It's", "a", "test"), result)
        }

        @Test
        fun `tokenize_words handles alphanumeric tokens`() {
            val result = tools.tokenize_words("Buy 2x items")
            assertEquals(listOf("Buy", "2x", "items"), result)
        }

        @Test
        fun `truncate_text truncates at word boundary`() {
            assertEquals("Hello…", tools.truncate_text("Hello world test", 8))
            assertEquals("Hello world", tools.truncate_text("Hello world", 20))
        }

        @Test
        fun `truncate_text respects max chars`() {
            val result = tools.truncate_text("12345678901234567890", 10, "...")
            assertTrue(result.length <= 10)
        }

        @Test
        fun `truncate_text handles edge cases`() {
            assertEquals("", tools.truncate_text("test", 0))
            assertEquals("…", tools.truncate_text("test", 1))
        }

        @Test
        fun `levenshtein_distance calculates edit distance`() {
            assertEquals(0, tools.levenshtein_distance("hello", "hello"))
            assertEquals(1, tools.levenshtein_distance("hello", "hallo"))
            assertEquals(5, tools.levenshtein_distance("hello", ""))
            assertEquals(3, tools.levenshtein_distance("kitten", "sitting"))
        }

        @Test
        fun `jaccard_similarity compares token sets`() {
            assertEquals(1.0, tools.jaccard_similarity("hello world", "hello world"))
            assertEquals(0.33, tools.jaccard_similarity("hello world", "hello test"), 0.01)
            assertEquals(0.0, tools.jaccard_similarity("abc", "def"))
        }

        @Test
        fun `compare_strings_fuzzy returns similarity score`() {
            val similar = tools.compare_strings_fuzzy("hello", "hallo")
            val different = tools.compare_strings_fuzzy("hello", "xyz")
            assertTrue(similar > different)
            assertEquals(1.0, tools.compare_strings_fuzzy("test", "test"))
        }
    }

    @Nested
    @DisplayName("JSON Operations")
    inner class JsonOperationsTests {

        @Test
        fun `json_select_fields extracts specified fields`() {
            val obj = JsonNodeFactory.instance.objectNode()
            obj.put("name", "Alice")
            obj.put("age", 30)
            obj.put("email", "alice@test.com")

            val result = tools.json_select_fields(obj, listOf("name", "age"))
            assertEquals(2, result.size())
            assertEquals("Alice", result.get("name").asText())
            assertEquals(30, result.get("age").asInt())
            assertNull(result.get("email"))
        }

        @Test
        fun `json_get_path retrieves nested values`() {
            val json = """{"user":{"name":"Bob","age":25}}"""
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)

            assertEquals("Bob", tools.json_get_path(node, "user.name"))
            assertEquals("25", tools.json_get_path(node, "user.age"))
            assertEquals("", tools.json_get_path(node, "user.missing"))
        }

        @Test
        fun `json_flatten flattens nested structure`() {
            val json = """{"a":1,"b":{"c":2,"d":3}}"""
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)

            val result = tools.json_flatten(node)
            assertTrue(result.has("a"))
            assertTrue(result.has("b.c"))
            assertTrue(result.has("b.d"))
        }
    }

    @Nested
    @DisplayName("Math and Vector Operations")
    inner class MathOperationsTests {

        @Test
        fun `vector_dot calculates dot product`() {
            assertEquals(32.0, tools.vector_dot(listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0)))
            assertEquals(0.0, tools.vector_dot(listOf(1.0, 0.0), listOf(0.0, 1.0)))
        }

        @Test
        fun `vector_dot throws on mismatched lengths`() {
            assertFailsWith<IllegalArgumentException> {
                tools.vector_dot(listOf(1.0, 2.0), listOf(1.0, 2.0, 3.0))
            }
        }

        @Test
        fun `cosine_similarity calculates similarity`() {
            val result = tools.cosine_similarity(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0))
            assertEquals(1.0, result, 0.001)
        }

        @Test
        fun `cosine_similarity handles orthogonal vectors`() {
            val result = tools.cosine_similarity(listOf(1.0, 0.0), listOf(0.0, 1.0))
            assertEquals(0.0, result, 0.001)
        }

        @Test
        fun `mean calculates average`() {
            assertEquals(3.0, tools.mean(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
            assertEquals(0.0, tools.mean(listOf(-1.0, 0.0, 1.0)))
        }

        @Test
        fun `mean returns NaN on empty list`() {
            val result = tools.mean(emptyList())
            assertTrue(result.isNaN())
        }

        @Test
        fun `moving_average calculates windowed averages`() {
            val result = tools.moving_average(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
            assertEquals(listOf(2.0, 3.0, 4.0), result)
        }

        @Test
        fun `moving_average handles edge cases`() {
            val result = tools.moving_average(listOf(1.0, 2.0), 5)
            assertEquals(emptyList<Double>(), result)
        }

        @Test
        fun `argmax finds maximum index`() {
            assertEquals(2, tools.argmax(listOf(1.0, 2.0, 5.0, 3.0)))
            assertEquals(0, tools.argmax(listOf(10.0, 1.0, 2.0)))
        }

        @Test
        fun `argmax returns -1 on empty list`() {
            assertEquals(-1, tools.argmax(emptyList()))
        }

        @Test
        fun `argmin finds minimum index`() {
            assertEquals(1, tools.argmin(listOf(5.0, 1.0, 3.0)))
            assertEquals(2, tools.argmin(listOf(10.0, 5.0, -1.0)))
        }

        @Test
        fun `clamp_value constrains to range`() {
            assertEquals(5.0, tools.clamp_value(3.0, 5.0, 10.0))
            assertEquals(10.0, tools.clamp_value(15.0, 5.0, 10.0))
            assertEquals(7.0, tools.clamp_value(7.0, 5.0, 10.0))
        }

        @Test
        fun `map_range maps value between ranges`() {
            assertEquals(5.0, tools.map_range(5.0, 0.0, 10.0, 0.0, 10.0))
            assertEquals(50.0, tools.map_range(5.0, 0.0, 10.0, 0.0, 100.0))
            assertEquals(0.5, tools.map_range(5.0, 0.0, 10.0, 0.0, 1.0))
        }

        @Test
        fun `summarize_list provides statistics`() {
            val result = tools.summarize_list(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
            assertEquals(5, result["count"])
            assertEquals(1.0, result["min"])
            assertEquals(5.0, result["max"])
            assertEquals(3.0, result["mean"])
        }
    }

    @Nested
    @DisplayName("List Operations")
    inner class ListOperationsTests {

        @Test
        fun `chunk_list splits into chunks`() {
            val result = tools.chunk_list(listOf(1, 2, 3, 4, 5), 2)
            assertEquals(3, result.size)
            assertEquals(listOf(1, 2), result[0])
            assertEquals(listOf(3, 4), result[1])
            assertEquals(listOf(5), result[2])
        }

        @Test
        fun `chunk_list handles exact division`() {
            val result = tools.chunk_list(listOf(1, 2, 3, 4), 2)
            assertEquals(2, result.size)
        }

        @Test
        fun `chunk_list throws on invalid size`() {
            assertFailsWith<IllegalArgumentException> {
                tools.chunk_list(listOf(1, 2, 3), 0)
            }
        }

        @Test
        fun `sliding_window creates overlapping windows`() {
            val result = tools.sliding_window(listOf(1, 2, 3, 4, 5), 3, 1)
            assertEquals(3, result.size)
            assertEquals(listOf(1, 2, 3), result[0])
            assertEquals(listOf(2, 3, 4), result[1])
            assertEquals(listOf(3, 4, 5), result[2])
        }

        @Test
        fun `sliding_window respects step size`() {
            val result = tools.sliding_window(listOf(1, 2, 3, 4, 5), 2, 2)
            assertEquals(2, result.size)
            assertEquals(listOf(1, 2), result[0])
            assertEquals(listOf(3, 4), result[1])
        }

        @Test
        fun `flatten_list flattens nested lists`() {
            val result = tools.flatten_list(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
            assertEquals(listOf(1, 2, 3, 4, 5), result)
        }

        @Test
        fun `interleave_lists alternates elements`() {
            val result = tools.interleave_lists(listOf("a", "b", "c"), listOf(1, 2, 3))
            assertEquals(listOf("a", 1, "b", 2, "c", 3), result)
        }

        @Test
        fun `interleave_lists handles uneven lengths`() {
            val result = tools.interleave_lists(listOf("a", "b"), listOf(1, 2, 3, 4))
            assertEquals(listOf("a", 1, "b", 2, 3, 4), result)
        }
    }

    @Nested
    @DisplayName("Date and Time Operations")
    inner class DateTimeOperationsTests {

        @Test
        fun `now_utc_iso returns valid ISO format`() {
            val result = tools.now_utc_iso()
            assertTrue(result.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z")))
        }

        @Test
        fun `parse_date parses custom formats`() {
            val result = tools.parse_date("2024-01-15 00:00:00", "yyyy-MM-dd HH:mm:ss")
            assertTrue(result.contains("2024-01-15"))
        }

        @Test
        fun `add_duration_iso adds duration`() {
            val base = "2024-01-15T10:00:00Z"
            val result = tools.add_duration_iso(base, "PT1H")
            assertTrue(result.contains("11:00:00"))
        }

        @Test
        fun `format_date formats with pattern`() {
            val iso = "2024-01-15T10:00:00Z"
            val result = tools.format_date(iso, "yyyy-MM-dd")
            assertEquals("2024-01-15", result)
        }

        @Test
        fun `days_between calculates day difference`() {
            val start = "2024-01-01T00:00:00Z"
            val end = "2024-01-11T00:00:00Z"
            assertEquals(10, tools.days_between(start, end))
        }

        @Test
        fun `hours_between calculates hour difference`() {
            val start = "2024-01-01T00:00:00Z"
            val end = "2024-01-01T05:00:00Z"
            assertEquals(5, tools.hours_between(start, end))
        }
    }

    @Nested
    @DisplayName("Utility Functions")
    inner class UtilityTests {

        @Test
        fun `uuid_generate creates valid UUID`() {
            val uuid = tools.uuid_generate()
            assertTrue(uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        }

        @Test
        fun `uuid_generate creates unique UUIDs`() {
            val uuid1 = tools.uuid_generate()
            val uuid2 = tools.uuid_generate()
            assertNotEquals(uuid1, uuid2)
        }

        @Test
        fun `safe_parse_int parses valid integers`() {
            assertEquals(42, tools.safe_parse_int("42"))
            assertEquals(-10, tools.safe_parse_int("-10"))
        }

        @Test
        fun `safe_parse_int returns default on invalid input`() {
            assertEquals(0, tools.safe_parse_int("invalid"))
            assertEquals(99, tools.safe_parse_int("invalid", 99))
        }

        @Test
        fun `safe_parse_double parses valid doubles`() {
            assertEquals(3.14, tools.safe_parse_double("3.14"), 0.001)
            assertEquals(-2.5, tools.safe_parse_double("-2.5"), 0.001)
        }

        @Test
        fun `safe_parse_double returns default on invalid input`() {
            assertEquals(0.0, tools.safe_parse_double("invalid"), 0.001)
            assertEquals(1.5, tools.safe_parse_double("invalid", 1.5), 0.001)
        }

        @Test
        fun `binary_search_value finds existing values`() {
            val sorted = listOf(1, 3, 5, 7, 9, 11)
            assertEquals(2, tools.binary_search_value(sorted, 5))
            assertEquals(0, tools.binary_search_value(sorted, 1))
            assertEquals(5, tools.binary_search_value(sorted, 11))
        }

        @Test
        fun `binary_search_value returns negative for missing values`() {
            val sorted = listOf(1, 3, 5, 7, 9)
            assertTrue(tools.binary_search_value(sorted, 4) < 0)
        }
    }

    @Nested
    @DisplayName("Plugin Lifecycle")
    inner class PluginLifecycleTests {

        @Test
        fun `plugin has valid manifest`() {
            val plugin = CoreToolsPlugin()
            val manifest = plugin.manifest()

            assertEquals("org.example.plugins.core", manifest.id)
            assertEquals("1.0.0", manifest.version)
            assertTrue(manifest.capabilities.isEmpty())
        }

        @Test
        fun `plugin init does not throw`() {
            val plugin = CoreToolsPlugin()
            val context = org.example.api.PluginContext("1.0.0", "1.0.0")
            assertDoesNotThrow {
                plugin.init(context)
            }
        }

        @Test
        fun `plugin provides tools list`() {
            val plugin = CoreToolsPlugin()
            val toolsList = plugin.tools()
            assertEquals(1, toolsList.size)
            assertTrue(toolsList[0] is CoreToolsPlugin.Tools)
        }
    }
}
