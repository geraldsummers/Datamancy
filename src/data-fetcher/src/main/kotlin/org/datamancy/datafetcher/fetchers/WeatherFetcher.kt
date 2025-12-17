package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.config.WeatherConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}
private val gson = Gson()

data class GeocodingCache(val latitude: Double, val longitude: Double, val expiresAt: Instant)

class WeatherFetcher(private val config: WeatherConfig) : Fetcher {
    private val pgStore = PostgresStore()
    private val clickHouseStore = ClickHouseStore()
    private val geocodingCacheDays = 30 // Cache geocoding results for 30 days

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("weather", version = "2.0.0") { ctx ->
            logger.info { "Fetching weather for ${config.locations.size} locations..." }

            config.locations.forEach { location ->
                ctx.markAttempted()
                try {
                    fetchWeatherForLocation(ctx, location)
                    ctx.markFetched()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch weather for $location" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", location)
                }
            }

            "Processed ${ctx.metrics.attempted} locations: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun fetchWeatherForLocation(ctx: FetchExecutionContext, location: String) {
        // Check geocoding cache first
        val cacheKey = "geocode_$location"
        val cachedGeocode = ctx.checkpoint.get(cacheKey)

        val (lat, lon) = if (cachedGeocode != null) {
            try {
                val cache = gson.fromJson(cachedGeocode, GeocodingCache::class.java)
                if (Clock.System.now() < cache.expiresAt) {
                    logger.debug { "Using cached geocoding for $location" }
                    Pair(cache.latitude, cache.longitude)
                } else {
                    logger.debug { "Geocoding cache expired for $location" }
                    geocodeLocation(ctx, location, cacheKey)
                }
            } catch (e: Exception) {
                logger.warn { "Failed to parse geocoding cache for $location: ${e.message}" }
                geocodeLocation(ctx, location, cacheKey)
            }
        } else {
            geocodeLocation(ctx, location, cacheKey)
        }

        // Fetch weather data using standardized HTTP client
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&timezone=auto"
        val response = ctx.http.get(weatherUrl)

        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body?.string()
        response.close()

        if (body == null) {
            throw Exception("Empty weather response")
        }

        val json = JsonParser.parseString(body).asJsonObject
        val current = json.getAsJsonObject("current")
        val timestamp = current?.get("time")?.asString ?: Clock.System.now().toString()
        val temp = current?.get("temperature_2m")?.asDouble ?: 0.0
        val humidity = current?.get("relative_humidity_2m")?.asInt ?: 0
        val windSpeed = current?.get("wind_speed_10m")?.asDouble ?: 0.0
        val weatherCode = current?.get("weather_code")?.asInt ?: 0

        // Create normalized weather data
        val weatherData = mapOf(
            "location" to location,
            "latitude" to lat,
            "longitude" to lon,
            "timestamp" to timestamp,
            "temperature" to temp,
            "humidity" to humidity,
            "windSpeed" to windSpeed,
            "weatherCode" to weatherCode
        )

        val dataJson = gson.toJson(weatherData)
        val contentHash = ContentHasher.hashJson(dataJson)

        // Dedupe check
        val itemId = "${location}_${timestamp.take(16)}" // Group by location and hour
        when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
            DedupeResult.NEW -> {
                // Store to time-series database
                clickHouseStore.storeMarketData(
                    symbol = location,
                    price = temp,
                    volume = windSpeed,
                    timestamp = Instant.parse(timestamp),
                    source = "open-meteo",
                    metadata = mapOf(
                        "humidity" to humidity,
                        "weatherCode" to weatherCode,
                        "latitude" to lat,
                        "longitude" to lon
                    )
                )

                // Store raw JSON
                ctx.storage.storeRawText(itemId, body, "json")

                pgStore.storeFetchMetadata(
                    source = "weather",
                    category = "current",
                    itemCount = 1,
                    fetchedAt = Clock.System.now(),
                    metadata = mapOf(
                        "location" to location,
                        "temperature" to temp
                    )
                )

                ctx.markNew()
                logger.info { "Weather: $location = ${temp}°C (humidity: $humidity%, wind: $windSpeed km/h)" }
            }
            DedupeResult.UPDATED -> {
                ctx.storage.storeRawText(itemId, body, "json")
                ctx.markUpdated()
            }
            DedupeResult.UNCHANGED -> {
                ctx.markSkipped()
            }
        }
    }

    private suspend fun geocodeLocation(ctx: FetchExecutionContext, location: String, cacheKey: String): Pair<Double, Double> {
        val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$location&count=1&language=en&format=json"
        val response = ctx.http.get(geocodeUrl)

        if (!response.isSuccessful) {
            response.close()
            throw Exception("Geocoding failed: ${response.code}")
        }

        val body = response.body?.string()
        response.close()

        if (body == null) {
            throw Exception("Empty geocoding response")
        }

        val json = JsonParser.parseString(body).asJsonObject
        val results = json.getAsJsonArray("results")
        if (results == null || results.size() == 0) {
            throw Exception("Location not found: $location")
        }

        val result = results[0].asJsonObject
        val lat = result.get("latitude").asDouble
        val lon = result.get("longitude").asDouble

        // Cache the geocoding result
        val cache = GeocodingCache(
            latitude = lat,
            longitude = lon,
            expiresAt = Clock.System.now().plus(geocodingCacheDays.days)
        )
        ctx.checkpoint.set(cacheKey, gson.toJson(cache))

        logger.debug { "Geocoded $location: ($lat, $lon)" }
        return Pair(lat, lon)
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying weather data sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/weather", "Weather data directory"))

        // Check Open-Meteo API (no key needed!)
        checks.add(
            DryRunUtils.checkUrl(
                "https://api.open-meteo.com/v1/forecast?latitude=-33.87&longitude=151.21&current=temperature_2m",
                "Open-Meteo Weather API"
            )
        )

        // Check Open-Meteo Geocoding API
        checks.add(
            DryRunUtils.checkUrl(
                "https://geocoding-api.open-meteo.com/v1/search?name=Sydney&count=1&format=json",
                "Open-Meteo Geocoding API"
            )
        )

        // Verify at least one location configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.locations.isEmpty()) null else config.locations.joinToString(","),
                "Weather locations",
                required = true
            )
        )

        return DryRunResult(checks)
    }
}
