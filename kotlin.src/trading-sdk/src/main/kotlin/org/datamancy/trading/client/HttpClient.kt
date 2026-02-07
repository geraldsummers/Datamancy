package org.datamancy.trading.client

import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Type
import java.time.Instant
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.datamancy.trading.models.ApiResult
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class InstantTypeAdapter : JsonSerializer<Instant>, JsonDeserializer<Instant> {
    override fun serialize(src: Instant, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.toString())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Instant {
        return Instant.parse(json.asString)
    }
}

internal class TradingHttpClient(
    val baseUrl: String,
    val token: String,
    private val timeoutSeconds: Long = 30
) {
    val logger = LoggerFactory.getLogger(TradingHttpClient::class.java)
    val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
        .create()
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    suspend inline fun <reified T> get(path: String): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            logger.debug("GET $baseUrl$path")

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                logger.debug("Response ${response.code}: $body")

                if (response.isSuccessful) {
                    val data = gson.fromJson(body, T::class.java)
                    ApiResult.Success(data)
                } else {
                    ApiResult.Error(
                        message = body.ifEmpty { "HTTP ${response.code}" },
                        code = response.code
                    )
                }
            }
        } catch (e: IOException) {
            logger.error("Network error: ${e.message}", e)
            ApiResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            logger.error("Unexpected error: ${e.message}", e)
            ApiResult.Error("Unexpected error: ${e.message}")
        }
    }

    suspend inline fun <reified T, reified R> post(path: String, body: T): ApiResult<R> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = gson.toJson(body)
            val requestBody = jsonBody.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            logger.debug("POST $baseUrl$path: $jsonBody")

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                logger.debug("Response ${response.code}: $responseBody")

                if (response.isSuccessful) {
                    val data = gson.fromJson(responseBody, R::class.java)
                    ApiResult.Success(data)
                } else {
                    ApiResult.Error(
                        message = responseBody.ifEmpty { "HTTP ${response.code}" },
                        code = response.code
                    )
                }
            }
        } catch (e: IOException) {
            logger.error("Network error: ${e.message}", e)
            ApiResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            logger.error("Unexpected error: ${e.message}", e)
            ApiResult.Error("Unexpected error: ${e.message}")
        }
    }

    suspend inline fun <reified R> post(path: String): ApiResult<R> =
        post<Map<String, Any>, R>(path, emptyMap())
}
