package org.datamancy.txgateway.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import org.slf4j.LoggerFactory
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

class AuthService(
    private val jwksUrl: String = System.getenv("AUTHELIA_JWKS_URL")
        ?: "http://authelia:9091/.well-known/jwks.json"
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val jwkProvider = JwkProviderBuilder(URL(jwksUrl))
        .cached(10, 24, TimeUnit.HOURS)
        .build()

    fun validateToken(token: String): DecodedJWT? {
        return try {
            val jwt = JWT.decode(token)
            val jwk = jwkProvider.get(jwt.keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)

            val verifier = JWT.require(algorithm)
                .build()

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
        // Verify Authelia JWKS URL is configured
        require(jwksUrl.isNotEmpty()) { "Authelia JWKS URL not configured" }
    }
}
