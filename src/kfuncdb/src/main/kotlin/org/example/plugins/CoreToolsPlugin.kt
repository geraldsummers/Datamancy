package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Core LM-friendly tools plugin. All functions are pure and deterministic.
 */
class CoreToolsPlugin : Plugin {
    override fun manifest() = PluginManifest(
        id = "org.example.plugins.core",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.CoreToolsPlugin",
        capabilities = emptyList(),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) { /* no-op */ }

    override fun tools(): List<Any> = listOf(Tools())

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools()

        // Register a representative subset non-reflectively
        registry.register(
            ToolDefinition(
                name = "normalize_whitespace",
                description = "Collapse repeated whitespace and trim",
                shortDescription = "Collapse repeated whitespace and trim",
                longDescription = "Collapse runs of any whitespace (spaces, tabs, newlines) into single spaces and trim leading/trailing whitespace.",
                parameters = listOf(ToolParam("text", "string", true, "Input text to normalize")),
                paramsSpec = "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                pluginId = pluginId
            ),
            ToolHandler { args ->
                val text = args.get("text")?.asText() ?: throw IllegalArgumentException("text required")
                tools.normalize_whitespace(text)
            }
        )

        registry.register(
            ToolDefinition(
                name = "uuid_generate",
                description = "Generate a random UUIDv4",
                shortDescription = "Generate a random UUIDv4",
                longDescription = "Returns a random UUID (version 4) string.",
                parameters = emptyList(),
                paramsSpec = "{\"type\":\"object\",\"properties\":{}}",
                pluginId = pluginId
            ),
            ToolHandler { _ -> tools.uuid_generate() }
        )
    }

    class Tools {
        // -------------------- Text / String --------------------
        @LlmTool(
            shortDescription = "Collapse repeated whitespace and trim",
            longDescription = "Collapse runs of any whitespace (spaces, tabs, newlines) into single spaces and trim leading/trailing whitespace.",
            paramsSpec = """
            {
              "type": "object",
              "required": ["text"],
              "properties": {
                "text": {"type": "string", "description": "Input text to normalize"}
              }
            }
            """
        )
        fun normalize_whitespace(text: String): String = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.joinToString(" ")

        @LlmTool(
            shortDescription = "Slugify text (lowercase-hyphen, no accents)",
            longDescription = "Normalize text to a URL-friendly slug: lowercase, accents removed, non-alphanumerics converted to hyphens, and repeated hyphens collapsed.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string","description":"Text to slugify"}}}
            """
        )
        fun slugify(text: String): String {
            val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            val noAccents = normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            val replaced = noAccents.replace("[^a-z0-9]+".toRegex(), "-")
            return replaced.trim('-').replace("-+".toRegex(), "-")
        }

        @LlmTool(
            shortDescription = "Extract regex match/capture group",
            longDescription = "Apply a regular expression to the input and return the full match or the specified capture group. Returns an empty string if no match is found.",
            paramsSpec = """
            {
              "type": "object",
              "required": ["text", "pattern"],
              "properties": {
                "text": {"type": "string"},
                "pattern": {"type": "string", "description": "ECMAScript-style regex pattern"},
                "group": {"type": "integer", "minimum": 0, "default": 0}
              }
            }
            """
        )
        fun extract_regex_group(text: String, pattern: String, group: Int = 0): String {
            val m = Regex(pattern).find(text) ?: return ""
            return try { m.groups[group]?.value ?: "" } catch (_: Exception) { "" }
        }

        @LlmTool(
            shortDescription = "Split text into sentences",
            longDescription = "Split text into sentences based on terminal punctuation characters (., !, ?) followed by whitespace.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}
            """
        )
        fun split_sentences(text: String): List<String> {
            val parts = text.trim().split("(?<=[.!?])\\s+".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
            return parts
        }

        @LlmTool(
            shortDescription = "Tokenize text into words",
            longDescription = "Tokenize the input into word tokens: alphanumeric with optional internal hyphens or apostrophes (e.g., 2x, can't). Punctuation is dropped.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}
            """
        )
        fun tokenize_words(text: String): List<String> {
            // Allow alpha-numeric combos like "2x" as a single token
            val regex = "[A-Za-z0-9][A-Za-z0-9'-]*".toRegex()
            return regex.findAll(text).map { it.value }.toList()
        }

        @LlmTool(
            shortDescription = "Truncate text with ellipsis",
            longDescription = "Truncate text to a maximum character count, preferring to cut at the last word boundary, and append an ellipsis if truncated.",
            paramsSpec = """
            {
              "type": "object",
              "required": ["text", "maxChars"],
              "properties": {
                "text": {"type": "string"},
                "maxChars": {"type": "integer", "minimum": 1},
                "ellipsis": {"type": "string", "default": "…"}
              }
            }
            """
        )
        fun truncate_text(text: String, maxChars: Int, ellipsis: String = "…"): String {
            if (maxChars <= 0) return ""
            if (text.length <= maxChars) return text
            val budget = maxChars - ellipsis.length
            if (budget <= 0) return ellipsis.take(maxChars)
            val cut = text.substring(0, budget)
            val lastSpace = cut.lastIndexOf(' ')
            val base = if (lastSpace > 0) cut.substring(0, lastSpace) else cut
            return base + ellipsis
        }

        @LlmTool(
            shortDescription = "Levenshtein distance",
            longDescription = "Compute the Levenshtein edit distance (insertions, deletions, substitutions) between two strings.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"string"},"b":{"type":"string"}}}
            """
        )
        fun levenshtein_distance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[j] = minOf(
                        dp[j] + 1,          // deletion
                        dp[j - 1] + 1,      // insertion
                        prev + cost         // substitution
                    )
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        @LlmTool(
            shortDescription = "Jaccard similarity of token sets",
            longDescription = "Compute the Jaccard similarity between two strings by tokenizing into lowercase word sets and comparing intersection over union.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"string"},"b":{"type":"string"}}}
            """
        )
        fun jaccard_similarity(a: String, b: String): Double {
            val sa = tokenize_words(a).map { it.lowercase() }.toSet()
            val sb = tokenize_words(b).map { it.lowercase() }.toSet()
            if (sa.isEmpty() && sb.isEmpty()) return 1.0
            val inter = sa.intersect(sb).size.toDouble()
            val union = sa.union(sb).size.toDouble()
            return if (union == 0.0) 0.0 else inter / union
        }

        // -------------------- JSON --------------------
        @LlmTool(
            shortDescription = "Select fields from JSON object",
            longDescription = "Create a new JSON object that includes only the specified top-level fields from the input object.",
            paramsSpec = """
            {
              "type":"object",
              "required":["json","fields"],
              "properties":{
                "json":{"type":"object"},
                "fields":{"type":"array","items":{"type":"string"}}
              }
            }
            """
        )
        fun json_select_fields(json: ObjectNode, fields: List<String>): ObjectNode {
            val out = JsonNodeFactory.instance.objectNode()
            for (f in fields) {
                val node = json.get(f)
                if (node != null) out.set<JsonNode>(f, node)
            }
            return out
        }

        @LlmTool(
            shortDescription = "Get JSON value by dot-path",
            longDescription = "Navigate a JSON tree using a dot-path with optional array indices (e.g., a.b[0].c) and return the value as a string; non-scalars are serialized.",
            paramsSpec = """
            {"type":"object","required":["json","path"],"properties":{"json":{},"path":{"type":"string"}}}
            """
        )
        fun json_get_path(json: JsonNode, path: String): String {
            fun step(node: JsonNode?, token: String): JsonNode? {
                if (node == null) return null
                val m = "(.+?)\\[(\\d+)]$".toRegex().matchEntire(token)
                return if (m != null) {
                    val key = m.groupValues[1]
                    val idx = m.groupValues[2].toInt()
                    val arr = node.get(key)
                    if (arr != null && arr.isArray && idx in 0 until arr.size()) arr.get(idx) else null
                } else node.get(token)
            }
            var cur: JsonNode? = json
            val tokens = path.split('.')
            for (t in tokens) cur = step(cur, t)
            val n = cur ?: return ""
            return when {
                n.isTextual -> n.asText()
                n.isNumber -> n.asText()
                n.isBoolean -> n.asBoolean().toString()
                n.isNull -> ""
                else -> n.toString()
            }
        }

        @LlmTool(
            shortDescription = "Flatten JSON to dot-path map",
            longDescription = "Produce a flat object mapping dot-paths to string values. Arrays are represented with [i] indices; non-scalar values are serialized.",
            paramsSpec = """
            {"type":"object","required":["json"],"properties":{"json":{}}}
            """
        )
        fun json_flatten(json: JsonNode): ObjectNode {
            val out = JsonNodeFactory.instance.objectNode()
            fun rec(node: JsonNode, prefix: String) {
                when {
                    node.isObject -> node.fields().forEachRemaining { (k, v) ->
                        val p = if (prefix.isEmpty()) k else "$prefix.$k"
                        rec(v, p)
                    }
                    node.isArray -> {
                        for (i in 0 until node.size()) {
                            rec(node.get(i), "$prefix[${i}]")
                        }
                    }
                    else -> out.put(prefix, when {
                        node.isTextual -> node.asText()
                        node.isNumber || node.isBoolean -> node.asText()
                        node.isNull -> ""
                        else -> node.toString()
                    })
                }
            }
            rec(json, "")
            return out
        }

        // -------------------- Math / Vectors / Statistics --------------------
        @LlmTool(
            shortDescription = "Vector dot product",
            longDescription = "Compute the dot product of two equal-length vectors of doubles.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"array","items":{"type":"number"}},"b":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun vector_dot(a: List<Double>, b: List<Double>): Double {
            require(a.size == b.size) { "Vectors must have same length" }
            return a.indices.sumOf { a[it] * b[it] }
        }

        @LlmTool(
            shortDescription = "Cosine similarity",
            longDescription = "Compute cosine similarity between two equal-length vectors of doubles.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"array","items":{"type":"number"}},"b":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun cosine_similarity(a: List<Double>, b: List<Double>): Double {
            require(a.size == b.size) { "Vectors must have same length" }
            val dot = vector_dot(a, b)
            val na = sqrt(a.sumOf { it * it })
            val nb = sqrt(b.sumOf { it * it })
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
        }

        @LlmTool(
            shortDescription = "Mean of numbers",
            longDescription = "Compute the arithmetic mean (average) of a list of doubles.",
            paramsSpec = """
            {"type":"object","required":["values"],"properties":{"values":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun mean(values: List<Double>): Double {
            if (values.isEmpty()) return Double.NaN
            return values.sum() / values.size
        }

        @LlmTool(
            shortDescription = "Simple moving average",
            longDescription = "Compute a simple moving average over a list with the given window size; returns a list of windowed averages.",
            paramsSpec = """
            {"type":"object","required":["values","window"],"properties":{"values":{"type":"array","items":{"type":"number"}},"window":{"type":"integer","minimum":1}}}
            """
        )
        fun moving_average(values: List<Double>, window: Int): List<Double> {
            require(window > 0) { "Window must be > 0" }
            if (values.size < window) return emptyList()
            val out = ArrayList<Double>(values.size - window + 1)
            var sum = 0.0
            for (i in values.indices) {
                sum += values[i]
                if (i >= window) sum -= values[i - window]
                if (i >= window - 1) out.add(sum / window)
            }
            return out
        }

        @LlmTool(
            shortDescription = "Argmax index",
            longDescription = "Return the index of the largest element in the list, or -1 if the list is empty.",
            paramsSpec = """
            {"type":"object","required":["values"],"properties":{"values":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun argmax(values: List<Double>): Int {
            if (values.isEmpty()) return -1
            var bestI = 0
            var bestV = values[0]
            for (i in 1 until values.size) if (values[i] > bestV) { bestV = values[i]; bestI = i }
            return bestI
        }

        @LlmTool(
            shortDescription = "Argmin index",
            longDescription = "Return the index of the smallest element in the list, or -1 if the list is empty.",
            paramsSpec = """
            {"type":"object","required":["values"],"properties":{"values":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun argmin(values: List<Double>): Int {
            if (values.isEmpty()) return -1
            var bestI = 0
            var bestV = values[0]
            for (i in 1 until values.size) if (values[i] < bestV) { bestV = values[i]; bestI = i }
            return bestI
        }

        // -------------------- Collections / Helpers --------------------
        @LlmTool(
            shortDescription = "Chunk list",
            longDescription = "Split a list into contiguous chunks each of the given size (last chunk may be smaller).",
            paramsSpec = """
            {"type":"object","required":["items","size"],"properties":{"items":{"type":"array"},"size":{"type":"integer","minimum":1}}}
            """
        )
        fun chunk_list(items: List<Any?>, size: Int): List<List<Any?>> {
            require(size > 0) { "size must be > 0" }
            val out = mutableListOf<List<Any?>>()
            var i = 0
            while (i < items.size) {
                out += items.subList(i, min(items.size, i + size))
                i += size
            }
            return out
        }

        @LlmTool(
            shortDescription = "Sliding window over list",
            longDescription = "Create overlapping windows from a list with a specified window size and step.",
            paramsSpec = """
            {"type":"object","required":["items","window"],"properties":{"items":{"type":"array"},"window":{"type":"integer","minimum":1},"step":{"type":"integer","minimum":1,"default":1}}}
            """
        )
        fun sliding_window(items: List<Any?>, window: Int, step: Int = 1): List<List<Any?>> {
            require(window > 0 && step > 0) { "window and step must be > 0" }
            if (items.size < window) return emptyList()
            val out = mutableListOf<List<Any?>>()
            var i = 0
            while (i + window <= items.size) {
                out += items.subList(i, i + window)
                i += step
            }
            return out
        }

        @LlmTool(
            shortDescription = "Flatten nested list",
            longDescription = "Flatten a list of lists into a single list by concatenation.",
            paramsSpec = """
            {"type":"object","required":["items"],"properties":{"items":{"type":"array","items":{"type":"array"}}}}
            """
        )
        fun flatten_list(items: List<List<Any?>>): List<Any?> = items.flatten()

        @LlmTool(
            shortDescription = "Interleave lists",
            longDescription = "Interleave two lists by alternating elements from each; if lengths differ, append the remainder.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"array"},"b":{"type":"array"}}}
            """
        )
        fun interleave_lists(a: List<Any?>, b: List<Any?>): List<Any?> {
            val out = ArrayList<Any?>(a.size + b.size)
            val n = max(a.size, b.size)
            for (i in 0 until n) {
                if (i < a.size) out += a[i]
                if (i < b.size) out += b[i]
            }
            return out
        }

        // -------------------- Date / Time --------------------
        @LlmTool(
            shortDescription = "Current time (UTC, ISO)",
            longDescription = "Return the current UTC time formatted as an ISO-8601 instant.",
            paramsSpec = """
            {"type":"object","properties":{},"additionalProperties":false}
            """
        )
        fun now_utc_iso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        @LlmTool(
            shortDescription = "Parse date to ISO (UTC)",
            longDescription = "Parse a date/time string using a provided pattern, interpret in UTC, and output ISO-8601 instant.",
            paramsSpec = """
            {"type":"object","required":["value","pattern"],"properties":{"value":{"type":"string"},"pattern":{"type":"string"}}}
            """
        )
        fun parse_date(value: String, pattern: String): String {
            val fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC)
            val inst = fmt.parse(value, Instant::from)
            return DateTimeFormatter.ISO_INSTANT.format(inst)
        }

        @LlmTool(
            shortDescription = "Add ISO duration to instant",
            longDescription = "Add an ISO-8601 duration (e.g., P1D, PT3H) to an ISO-8601 instant and return the resulting instant.",
            paramsSpec = """
            {"type":"object","required":["isoInstant","duration"],"properties":{"isoInstant":{"type":"string"},"duration":{"type":"string"}}}
            """
        )
        fun add_duration_iso(isoInstant: String, duration: String): String {
            val inst = Instant.parse(isoInstant)
            val d = Duration.parse(duration)
            return DateTimeFormatter.ISO_INSTANT.format(inst.plus(d))
        }

        @LlmTool(
            shortDescription = "Format instant with pattern",
            longDescription = "Format an ISO-8601 instant using a custom pattern (UTC timezone).",
            paramsSpec = """
            {"type":"object","required":["isoInstant","pattern"],"properties":{"isoInstant":{"type":"string"},"pattern":{"type":"string"}}}
            """
        )
        fun format_date(isoInstant: String, pattern: String): String {
            val inst = Instant.parse(isoInstant)
            val fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC)
            return fmt.format(inst)
        }

        @LlmTool(
            shortDescription = "Days between instants",
            longDescription = "Compute the number of whole days between two ISO-8601 instants.",
            paramsSpec = """
            {"type":"object","required":["startIso","endIso"],"properties":{"startIso":{"type":"string"},"endIso":{"type":"string"}}}
            """
        )
        fun days_between(startIso: String, endIso: String): Long {
            val a = Instant.parse(startIso)
            val b = Instant.parse(endIso)
            val d = Duration.between(a, b)
            return d.toDays()
        }

        @LlmTool(
            shortDescription = "Hours between instants",
            longDescription = "Compute the number of whole hours between two ISO-8601 instants.",
            paramsSpec = """
            {"type":"object","required":["startIso","endIso"],"properties":{"startIso":{"type":"string"},"endIso":{"type":"string"}}}
            """
        )
        fun hours_between(startIso: String, endIso: String): Long {
            val a = Instant.parse(startIso)
            val b = Instant.parse(endIso)
            val d = Duration.between(a, b)
            return d.toHours()
        }

        // -------------------- Algorithms --------------------
        @LlmTool(
            shortDescription = "Binary search index",
            longDescription = "Perform binary search for target in a sorted list of integers; return index if found else -(insertionPoint+1).",
            paramsSpec = """
            {"type":"object","required":["sorted","target"],"properties":{"sorted":{"type":"array","items":{"type":"integer"}},"target":{"type":"integer"}}}
            """
        )
        fun binary_search_value(sorted: List<Int>, target: Int): Int {
            var lo = 0
            var hi = sorted.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = sorted[mid]
                if (v < target) lo = mid + 1 else if (v > target) hi = mid - 1 else return mid
            }
            return -(lo + 1)
        }

        // -------------------- General Utilities --------------------
        @LlmTool(
            shortDescription = "Safe parse int",
            longDescription = "Parse an integer from a string; return the provided default if parsing fails.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string"},"default":{"type":"integer","default":0}}}
            """
        )
        fun safe_parse_int(text: String, default: Int = 0): Int = text.toIntOrNull() ?: default

        @LlmTool(
            shortDescription = "Safe parse double",
            longDescription = "Parse a double from a string; return the provided default if parsing fails.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string"},"default":{"type":"number","default":0.0}}}
            """
        )
        fun safe_parse_double(text: String, default: Double = 0.0): Double = text.toDoubleOrNull() ?: default

        @LlmTool(
            shortDescription = "Clamp value",
            longDescription = "Clamp a numeric value to the inclusive range [min, max].",
            paramsSpec = """
            {"type":"object","required":["x","minVal","maxVal"],"properties":{"x":{"type":"number"},"minVal":{"type":"number"},"maxVal":{"type":"number"}}}
            """
        )
        fun clamp_value(x: Double, minVal: Double, maxVal: Double): Double = max(minVal, min(maxVal, x))

        @LlmTool(
            shortDescription = "Map number between ranges",
            longDescription = "Linearly map a number from the source range [fromMin, fromMax] to the target range [toMin, toMax].",
            paramsSpec = """
            {"type":"object","required":["x","fromMin","fromMax","toMin","toMax"],"properties":{"x":{"type":"number"},"fromMin":{"type":"number"},"fromMax":{"type":"number"},"toMin":{"type":"number"},"toMax":{"type":"number"}}}
            """
        )
        fun map_range(x: Double, fromMin: Double, fromMax: Double, toMin: Double, toMax: Double): Double {
            require(fromMax != fromMin) { "from range must be non-zero" }
            val t = (x - fromMin) / (fromMax - fromMin)
            return toMin + t * (toMax - toMin)
        }

        @LlmTool(
            shortDescription = "Generate UUID",
            longDescription = "Generate a random RFC 4122 version 4 UUID as a string.",
            paramsSpec = """
            {"type":"object","properties":{},"additionalProperties":false}
            """
        )
        fun uuid_generate(): String = UUID.randomUUID().toString()

        @LlmTool(
            shortDescription = "SHA-256 hash",
            longDescription = "Compute the SHA-256 digest of the input string and return it as lowercase hexadecimal.",
            paramsSpec = """
            {"type":"object","required":["text"],"properties":{"text":{"type":"string"}}}
            """
        )
        fun hash_sha256(text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            return sb.toString()
        }

        // -------------------- Reasoning-friendly helpers --------------------
        @LlmTool(
            shortDescription = "Fuzzy string similarity",
            longDescription = "Compare two strings using a weighted combination of normalized Levenshtein distance and Jaccard token similarity; returns a 0..1 score.",
            paramsSpec = """
            {"type":"object","required":["a","b"],"properties":{"a":{"type":"string"},"b":{"type":"string"}}}
            """
        )
        fun compare_strings_fuzzy(a: String, b: String): Double {
            val an = normalize_whitespace(a).lowercase()
            val bn = normalize_whitespace(b).lowercase()
            if (an.isEmpty() && bn.isEmpty()) return 1.0
            val maxLen = max(an.length, bn.length).toDouble()
            val lev = levenshtein_distance(an, bn)
            val levScore = 1.0 - (lev / maxLen)
            val jac = jaccard_similarity(an, bn)
            return (levScore * 0.6 + jac * 0.4).coerceIn(0.0, 1.0)
        }

        @LlmTool(
            shortDescription = "Summarize list statistics",
            longDescription = "Return basic statistics for a list of numbers: count, min, max, and mean.",
            paramsSpec = """
            {"type":"object","required":["values"],"properties":{"values":{"type":"array","items":{"type":"number"}}}}
            """
        )
        fun summarize_list(values: List<Double>): Map<String, Any> {
            if (values.isEmpty()) return mapOf("count" to 0)
            val mn = values.minOrNull()!!
            val mx = values.maxOrNull()!!
            val avg = mean(values)
            return mapOf("count" to values.size, "min" to mn, "max" to mx, "mean" to avg)
        }
    }
}
