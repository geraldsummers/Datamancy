package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Manages authentication tokens for all services in the Datamancy stack
 *
 * Each service has its own authentication mechanism:
 * - BookStack: API tokens
 * - Grafana: API keys or session cookies
 * - Forgejo: Personal access tokens
 * - Mastodon: OAuth2 application tokens
 * - Open-WebUI: JWT tokens
 * - JupyterHub: API tokens
 * - Seafile: API tokens
 * - Planka: JWT tokens
 * - Vaultwarden: Client tokens
 * - Home Assistant: Long-lived access tokens
 * - Qbittorrent: Cookie-based session
 */
class TokenManager(
    private val client: HttpClient,
    private val endpoints: ServiceEndpoints
) {
    private val tokens = mutableMapOf<String, String>()
    private val cookies = mutableMapOf<String, List<Cookie>>()
    private val json = Json { ignoreUnknownKeys = true }

    // =============================================================================
    // GRAFANA
    // =============================================================================

    /**
     * Acquire Grafana API key
     * Uses admin credentials to create an API key
     */
    suspend fun acquireGrafanaToken(username: String = "admin", password: String): Result<String> {
        return try {
            // Login first to get session
            val loginResponse = client.post("${endpoints.grafana}/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"user":"$username","password":"$password"}""")
            }

            if (loginResponse.status != HttpStatusCode.OK) {
                return Result.failure(Exception("Grafana login failed: ${loginResponse.status}"))
            }

            val sessionCookies = loginResponse.setCookie()

            // Create API key using session
            val keyResponse = client.post("${endpoints.grafana}/api/auth/keys") {
                contentType(ContentType.Application.Json)
                sessionCookies.forEach { cookie(it.name, it.value) }
                setBody("""{"name":"integration-test-${System.currentTimeMillis()}","role":"Admin"}""")
            }

            if (keyResponse.status == HttpStatusCode.OK) {
                val body = json.parseToJsonElement(keyResponse.bodyAsText()).jsonObject
                val key = body["key"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No API key in response"))

                tokens["grafana"] = key
                Result.success(key)
            } else {
                Result.failure(Exception("Failed to create API key: ${keyResponse.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // BOOKSTACK
    // =============================================================================

    /**
     * Acquire BookStack API token
     * BookStack uses token_id:token_secret format
     */
    suspend fun acquireBookStackToken(email: String, password: String): Result<Pair<String, String>> {
        return try {
            // BookStack requires admin to generate tokens via web UI
            // For testing, we assume tokens are pre-generated and provided via environment
            val tokenId = System.getenv("BOOKSTACK_TOKEN_ID")
            val tokenSecret = System.getenv("BOOKSTACK_TOKEN_SECRET")

            if (tokenId != null && tokenSecret != null) {
                tokens["bookstack"] = "$tokenId:$tokenSecret"
                Result.success(Pair(tokenId, tokenSecret))
            } else {
                // Alternative: Login and scrape token from settings page
                Result.failure(Exception("BookStack tokens must be pre-generated (set BOOKSTACK_TOKEN_ID and BOOKSTACK_TOKEN_SECRET)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // FORGEJO
    // =============================================================================

    /**
     * Acquire Forgejo personal access token
     */
    suspend fun acquireForgejoToken(username: String, password: String): Result<String> {
        return try {
            // Forgejo API token creation
            val response = client.post("http://forgejo:3000/api/v1/users/$username/tokens") {
                basicAuth(username, password)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"integration-test-${System.currentTimeMillis()}"}""")
            }

            if (response.status == HttpStatusCode.Created) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = body["sha1"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No token in response"))

                tokens["forgejo"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to create token: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // MASTODON
    // =============================================================================

    /**
     * Acquire Mastodon OAuth application token
     */
    suspend fun acquireMastodonToken(email: String, password: String): Result<String> {
        return try {
            // Step 1: Register OAuth application
            val appResponse = client.post("http://mastodon-web:3000/api/v1/apps") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_name=test-client&redirect_uris=urn:ietf:wg:oauth:2.0:oob&scopes=read write")
            }

            if (appResponse.status != HttpStatusCode.OK) {
                return Result.failure(Exception("Failed to register app: ${appResponse.status}"))
            }

            val appBody = json.parseToJsonElement(appResponse.bodyAsText()).jsonObject
            val clientId = appBody["client_id"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("No client_id"))
            val clientSecret = appBody["client_secret"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("No client_secret"))

            // Step 2: Get OAuth token
            val tokenResponse = client.post("http://mastodon-web:3000/oauth/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("client_id=$clientId&client_secret=$clientSecret&grant_type=password&username=$email&password=$password&scope=read write")
            }

            if (tokenResponse.status == HttpStatusCode.OK) {
                val tokenBody = json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
                val token = tokenBody["access_token"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No access_token"))

                tokens["mastodon"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to get token: ${tokenResponse.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // SEAFILE
    // =============================================================================

    /**
     * Acquire Seafile API token
     */
    suspend fun acquireSeafileToken(username: String, password: String): Result<String> {
        return try {
            val response = client.post("http://seafile:80/api2/auth-token/") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("username=$username&password=$password")
            }

            if (response.status == HttpStatusCode.OK) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = body["token"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No token in response"))

                tokens["seafile"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to get token: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // PLANKA
    // =============================================================================

    /**
     * Acquire Planka authentication token
     */
    suspend fun acquirePlankaToken(email: String, password: String): Result<String> {
        return try {
            val response = client.post("http://planka:1337/api/access-tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"emailOrUsername":"$email","password":"$password"}""")
            }

            if (response.status == HttpStatusCode.OK) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val item = body["item"]?.jsonObject
                    ?: return Result.failure(Exception("No item in response"))
                val token = item["token"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No token in response"))

                tokens["planka"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to get token: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // HOME ASSISTANT
    // =============================================================================

    /**
     * Acquire Home Assistant long-lived access token
     * Note: Must be generated manually via UI or pre-configured
     */
    suspend fun acquireHomeAssistantToken(): Result<String> {
        return try {
            val token = System.getenv("HOME_ASSISTANT_TOKEN")
            if (token != null) {
                tokens["homeassistant"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Home Assistant token must be pre-generated (set HOME_ASSISTANT_TOKEN)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // QBITTORRENT
    // =============================================================================

    /**
     * Acquire Qbittorrent session cookie
     */
    suspend fun acquireQbittorrentSession(username: String = "admin", password: String): Result<List<Cookie>> {
        return try {
            val response = client.post("http://qbittorrent:8080/api/v2/auth/login") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("username=$username&password=$password")
            }

            if (response.status == HttpStatusCode.OK && response.bodyAsText() == "Ok.") {
                val sessionCookies = response.setCookie()
                cookies["qbittorrent"] = sessionCookies
                Result.success(sessionCookies)
            } else {
                Result.failure(Exception("Login failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // OPEN-WEBUI
    // =============================================================================

    /**
     * Acquire Open-WebUI JWT token
     */
    suspend fun acquireOpenWebUIToken(email: String, password: String): Result<String> {
        return try {
            val response = client.post("http://open-webui:8080/api/v1/auths/signin") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"$password"}""")
            }

            if (response.status == HttpStatusCode.OK) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = body["token"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No token in response"))

                tokens["open-webui"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to authenticate: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // JUPYTERHUB
    // =============================================================================

    /**
     * Acquire JupyterHub API token
     */
    suspend fun acquireJupyterHubToken(username: String, password: String): Result<String> {
        return try {
            // JupyterHub uses PAM authentication, need to get token via API
            val response = client.post("http://jupyterhub:8000/hub/api/users/$username/tokens") {
                basicAuth(username, password)
                contentType(ContentType.Application.Json)
                setBody("""{"note":"integration-test"}""")
            }

            if (response.status == HttpStatusCode.OK) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = body["token"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("No token in response"))

                tokens["jupyterhub"] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to create token: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============================================================================
    // TOKEN RETRIEVAL
    // =============================================================================

    fun getToken(service: String): String? = tokens[service]

    fun getCookies(service: String): List<Cookie>? = cookies[service]

    fun hasToken(service: String): Boolean = tokens.containsKey(service)

    fun clearToken(service: String) {
        tokens.remove(service)
        cookies.remove(service)
    }

    fun clearAll() {
        tokens.clear()
        cookies.clear()
    }

    // =============================================================================
    // AUTHENTICATED REQUESTS
    // =============================================================================

    suspend fun authenticatedGet(service: String, url: String): HttpResponse {
        val token = getToken(service)
        val serviceCookies = getCookies(service)

        return client.get(url) {
            when (service) {
                "grafana", "forgejo", "mastodon", "seafile", "jupyterhub", "homeassistant" -> {
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
                "bookstack" -> {
                    token?.let {
                        val (id, secret) = it.split(":")
                        header("Authorization", "Token $id:$secret")
                    }
                }
                "planka", "open-webui" -> {
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
                "qbittorrent" -> {
                    serviceCookies?.forEach { cookie(it.name, it.value) }
                }
            }
        }
    }

    suspend fun authenticatedPost(service: String, url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val token = getToken(service)
        val serviceCookies = getCookies(service)

        return client.post(url) {
            when (service) {
                "grafana", "forgejo", "mastodon", "seafile", "jupyterhub", "homeassistant" -> {
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
                "bookstack" -> {
                    token?.let {
                        val (id, secret) = it.split(":")
                        header("Authorization", "Token $id:$secret")
                    }
                }
                "planka", "open-webui" -> {
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
                "qbittorrent" -> {
                    serviceCookies?.forEach { cookie(it.name, it.value) }
                }
            }
            block()
        }
    }
}
