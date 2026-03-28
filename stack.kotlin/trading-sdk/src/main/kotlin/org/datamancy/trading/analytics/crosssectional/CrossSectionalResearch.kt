package org.datamancy.trading.analytics.crosssectional

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.CoverageContractPolicy
import org.datamancy.trading.policy.TradingPolicy
import org.datamancy.trading.policy.UniversePolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.datamancy.trading.policy.VenuePolicy
import org.datamancy.trading.storage.verifyCanonicalMarketDataDatabase
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

fun env(name: String, default: String = ""): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull() ?: default

fun envDouble(name: String, default: Double): Double =
    env(name, default.toString()).toDoubleOrNull() ?: default

fun envBoolean(name: String, default: Boolean): Boolean {
    val raw = env(name, if (default) "true" else "false").lowercase()
    return raw in setOf("1", "true", "yes", "on")
}

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L
private const val DEFAULT_RESEARCH_QUERY_PARALLELISM = 4


fun clamp(value: Double, lower: Double, upper: Double): Double =
    max(lower, min(upper, value))

fun direction(value: Double): Double =
    when {
        value > 1e-9 -> 1.0
        value < -1e-9 -> -1.0
        else -> 0.0
    }

fun Double.round(decimals: Int = 4): Double {
    val scale = 10.0.pow(decimals.toDouble())
    return round(this * scale) / scale
}

fun sqlList(values: List<String>): String =
    values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

internal fun resolveResearchQueryParallelism(
    workItems: Int,
    configuredMax: Int = envInt("ALPHA_RESEARCH_QUERY_PARALLELISM", DEFAULT_RESEARCH_QUERY_PARALLELISM)
): Int =
    workItems.coerceAtLeast(1).coerceAtMost(configuredMax.coerceAtLeast(1))

internal fun <T, R> parallelMapBlocking(
    items: List<T>,
    maxParallelism: Int,
    block: (T) -> R
): List<R> {
    if (items.isEmpty()) return emptyList()
    val parallelism = maxParallelism.coerceIn(1, items.size)
    if (parallelism == 1) {
        return items.map(block)
    }

    return runBlocking {
        val semaphore = Semaphore(parallelism)
        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        block(item)
                    }
                }
            }.awaitAll()
        }
    }
}

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun percentile(values: List<Double>, quantile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = clamp(quantile, 0.0, 1.0) * (sorted.lastIndex.toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower.toDouble()
    return (sorted[lower] * (1.0 - weight)) + (sorted[upper] * weight)
}

fun median(values: List<Double>): Double = percentile(values, 0.5)

fun medianAbsoluteDeviation(values: List<Double>, center: Double = median(values)): Double {
    if (values.isEmpty()) return 0.0
    return median(values.map { abs(it - center) })
}

fun robustZScore(value: Double, values: List<Double>, fallbackScale: Double = 1.0): Double {
    if (values.isEmpty()) return 0.0
    val center = median(values)
    val mad = medianAbsoluteDeviation(values, center)
    val scale = max(mad * 1.4826, fallbackScale)
    return clamp((value - center) / scale, -6.0, 6.0)
}

fun stdev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mu = mean(values)
    val variance = values.sumOf { (it - mu).pow(2.0) } / values.size.toDouble()
    return sqrt(max(variance, 0.0))
}

fun rollingSum(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return values.subList(start, endIndex + 1).sum()
}

fun rollingMean(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return mean(values.subList(start, endIndex + 1))
}

fun deterministicJitter(time: Instant, salt: Int): Double {
    val bucket = (time.epochSecond / 60L) + salt.toLong() * 13L
    return abs((bucket % 37L) - 18L).toDouble()
}

fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean

fun JsonObject.double(name: String): Double? =
    get(name)?.takeIf { !it.isJsonNull }?.asDouble

fun JsonObject.obj(name: String): JsonObject? =
    getAsJsonObject(name)

fun JsonObject.array(name: String): JsonArray? =
    getAsJsonArray(name)

internal fun ResultSet.doubleOrNull(name: String): Double? {
    val value = getDouble(name)
    return if (wasNull()) null else value
}


val gson: Gson = GsonBuilder().setPrettyPrinting().create()
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val postgresDriverLoaded: Boolean = run {
    Class.forName("org.postgresql.Driver")
    true
}
val pgHost = env("POSTGRES_HOST", "market-postgres")
val pgPort = env("POSTGRES_PORT", "5432")
val pgDb = env("POSTGRES_DB", "datamancy")
val pgUser = env("POSTGRES_USER", "pipeline_user")
val pgPassword = env("POSTGRES_PASSWORD", "")
val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

fun pgConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, pgUser, pgPassword)
        .also { connection ->
            verifyCanonicalMarketDataDatabase(
                connection = connection,
                verificationKey = "trading-sdk:$jdbcUrl:$pgUser",
                descriptor = "trading-sdk market-data connection $jdbcUrl as $pgUser"
            )
        }

inline fun <T> timedMillis(block: () -> T): Pair<T, Long> {
    val started = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    return result to elapsedMs
}

fun twoFactorBetas(window: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
    if (window.size < 30) return 0.0 to 0.0
    val yMean = mean(window.map { it.first })
    val x1Mean = mean(window.map { it.second })
    val x2Mean = mean(window.map { it.third })

    var s11 = 0.0
    var s22 = 0.0
    var s12 = 0.0
    var sy1 = 0.0
    var sy2 = 0.0

    for ((yRaw, x1Raw, x2Raw) in window) {
        val y = yRaw - yMean
        val x1 = x1Raw - x1Mean
        val x2 = x2Raw - x2Mean
        s11 += x1 * x1
        s22 += x2 * x2
        s12 += x1 * x2
        sy1 += y * x1
        sy2 += y * x2
    }

    val determinant = (s11 * s22) - (s12 * s12)
    if (abs(determinant) < 1e-9) {
        val beta1 = if (abs(s11) < 1e-9) 0.0 else sy1 / s11
        val beta2 = if (abs(s22) < 1e-9) 0.0 else sy2 / s22
        return beta1 to beta2
    }

    val betaBtc = ((sy1 * s22) - (sy2 * s12)) / determinant
    val betaEth = ((sy2 * s11) - (sy1 * s12)) / determinant
    return betaBtc to betaEth
}

