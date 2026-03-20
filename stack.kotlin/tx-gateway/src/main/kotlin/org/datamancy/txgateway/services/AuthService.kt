package org.datamancy.txgateway.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class AuthService(
    private val jwksUrl: String = System.getenv("AUTHELIA_JWKS_URL")
        ?: "http://authelia:9091/jwks.json"
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val issuer: String = (
        System.getenv("AUTHELIA_JWT_ISSUER")
            ?: System.getenv("AUTHELIA_URL")
            ?: ""
        ).trim()
    private val userInfoUrl: String = (
        System.getenv("AUTHELIA_USERINFO_URL")
            ?: "http://authelia:9091/api/oidc/userinfo"
        ).trim()
    private val audiences: Set<String> = parseCsv(System.getenv("AUTHELIA_JWT_AUDIENCE") ?: "tx-gateway")
    private val jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun validateToken(token: String): DecodedJWT? {
        val jwtToken = validateJwtToken(token)
        if (jwtToken != null) {
            return jwtToken
        }
        return validateOpaqueTokenViaUserInfo(token)
    }

    private fun validateJwtToken(token: String): DecodedJWT? {
        return try {
            val jwt = JWT.decode(token)
            val keyId = jwt.keyId ?: throw IllegalArgumentException("JWT missing key id")
            val jwk = jwkProvider.get(keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)

            val verifierBuilder = JWT.require(algorithm).withIssuer(issuer)
            if (audiences.isNotEmpty()) {
                verifierBuilder.withAudience(*audiences.toTypedArray())
            }
            val verifier = verifierBuilder.build()

            verifier.verify(token)
        } catch (e: Exception) {
            logger.debug("JWT token validation failed: {}", e.message)
            null
        }
    }

    private fun validateOpaqueTokenViaUserInfo(token: String): DecodedJWT? {
        if (userInfoUrl.isBlank()) {
            return null
        }

        return try {
            val connection = URI(userInfoUrl).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val payload = json.parseToJsonElement(body).jsonObject
            syntheticJwtFromUserInfo(payload)
        } catch (e: Exception) {
            logger.debug("Opaque token validation via userinfo failed: {}", e.message)
            null
        }
    }

    private fun syntheticJwtFromUserInfo(payload: JsonObject): DecodedJWT? {
        val preferredUsername = payload["preferred_username"]?.jsonPrimitive?.contentOrNull
        val subject = payload["sub"]?.jsonPrimitive?.contentOrNull
        if (preferredUsername.isNullOrBlank() && subject.isNullOrBlank()) {
            return null
        }

        val groups = payload["groups"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        val jwtPayload = buildJsonObject {
            preferredUsername?.let { put("preferred_username", JsonPrimitive(it)) }
            subject?.let { put("sub", JsonPrimitive(it)) }
            if (groups.isNotEmpty()) {
                put("groups", JsonArray(groups.map { JsonPrimitive(it) }))
            }
            if (issuer.isNotBlank()) {
                put("iss", JsonPrimitive(issuer))
            }
            if (audiences.isNotEmpty()) {
                put("aud", JsonArray(audiences.map { JsonPrimitive(it) }))
            }
        }

        val header = """{"alg":"none","typ":"JWT"}"""
        val encodedHeader = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(header.toByteArray())
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.encodeToString(JsonObject.serializer(), jwtPayload).toByteArray())

        return JWT.decode("$encodedHeader.$encodedPayload.")
    }

    fun extractUsername(jwt: DecodedJWT): String? {
        return jwt.getClaim("preferred_username").asString()
            ?: jwt.getClaim("username").asString()
            ?: jwt.getClaim("email").asString()?.substringBefore("@")
            ?: jwt.getClaim("sub").asString()
    }

    fun extractGroups(jwt: DecodedJWT): List<String> {
        return jwt.getClaim("groups").asList(String::class.java) ?: emptyList()
    }

    fun healthCheck() {
        require(jwksUrl.isNotEmpty()) { "Authelia JWKS URL not configured" }
        require(issuer.isNotEmpty()) { "Authelia JWT issuer not configured (AUTHELIA_JWT_ISSUER)" }
        require(audiences.isNotEmpty()) { "Authelia JWT audience not configured (AUTHELIA_JWT_AUDIENCE)" }
    }

    private fun parseCsv(raw: String): Set<String> {
        return raw.split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
