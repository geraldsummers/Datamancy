package org.datamancy.txgateway.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

class AuthService(
    private val jwksUrl: String = System.getenv("AUTHELIA_JWKS_URL")
        ?: "http://authelia:9091/.well-known/jwks.json"
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val issuer: String = (
        System.getenv("AUTHELIA_JWT_ISSUER")
            ?: System.getenv("AUTHELIA_URL")
            ?: ""
        ).trim()
    private val audiences: Set<String> = parseCsv(System.getenv("AUTHELIA_JWT_AUDIENCE") ?: "tx-gateway")
    private val jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .build()

    fun validateToken(token: String): DecodedJWT? {
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
            logger.error("Token validation failed", e)
            null
        }
    }

    fun extractUsername(jwt: DecodedJWT): String? {
        return jwt.getClaim("preferred_username").asString()
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
