package org.datamancy.datafetcher.fetchers

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.datafetcher.config.WeatherConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class WeatherFetcher(private val config: WeatherConfig) : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): FetchResult {
        logger.info { "Fetching weather for ${config.locations.size} locations..." }
        var totalFetched = 0
        val errors = mutableListOf<String>()

        config.locations.forEach { location ->
            try {
                fetchWeatherForLocation(location)
                totalFetched++
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch weather for $location" }
                errors.add("$location: ${e.message}")
            }
        }

        pgStore.storeFetchMetadata(
            source = "weather",
            category = "current",
            itemCount = totalFetched,
            fetchedAt = Clock.System.now()
        )

        return if (errors.isEmpty()) {
            FetchResult.Success("Fetched weather for $totalFetched locations", totalFetched)
        } else {
            FetchResult.Error("Fetched $totalFetched with ${errors.size} errors")
        }
    }

    private fun fetchWeatherForLocation(location: String) {
        // Get coordinates for the location using Open-Meteo geocoding
        val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$location&count=1&language=en&format=json"
        val geocodeRequest = Request.Builder()
            .url(geocodeUrl)
            .get()
            .build()

        val (lat, lon) = client.newCall(geocodeRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Geocoding failed: ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty geocoding response")
            val json = JsonParser.parseString(body).asJsonObject
            val results = json.getAsJsonArray("results")
            if (results == null || results.size() == 0) throw Exception("Location not found")
            val result = results[0].asJsonObject
            Pair(result.get("latitude").asDouble, result.get("longitude").asDouble)
        }

        // Fetch weather data from Open-Meteo (no API key needed!)
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&timezone=auto"
        val request = Request.Builder()
            .url(weatherUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val json = JsonParser.parseString(body).asJsonObject

                val current = json.getAsJsonObject("current")
                val temp = current?.get("temperature_2m")?.asDouble
                val humidity = current?.get("relative_humidity_2m")?.asInt
                val windSpeed = current?.get("wind_speed_10m")?.asDouble

                // Store raw JSON
                val filename = "${location.replace(" ", "_")}_${Clock.System.now().epochSeconds}.json"
                fsStore.storeRawText("weather", filename, body)

                logger.info { "Fetched weather: $location = ${temp}°C (humidity: $humidity%, wind: $windSpeed km/h)" }
            } else {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
        }
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
