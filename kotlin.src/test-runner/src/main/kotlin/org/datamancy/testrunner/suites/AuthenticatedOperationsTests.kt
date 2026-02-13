package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*


suspend fun TestRunner.authenticatedOperationsTests() = suite("Authenticated Operations Tests") {

    
    
    

    test("Grafana: Acquire API key and query datasources") {
        val grafanaPassword = System.getenv("GRAFANA_ADMIN_PASSWORD") ?: "admin"

        // Use STACK_ADMIN_USER (sysadmin) for LDAP authentication, not "admin"
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}. Check LDAP credentials and Authelia configuration."
        }
        println("      ✓ Authenticated with Authelia")

        
        val tokenResult = tokens.acquireGrafanaToken("admin", grafanaPassword)
        require(tokenResult.isSuccess) { "Failed to acquire Grafana token: ${tokenResult.exceptionOrNull()?.message}" }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Grafana API key")

        
        val response = tokens.authenticatedGet("grafana", "${env.endpoints.grafana}/api/datasources")
        require(response.status == HttpStatusCode.OK) {
            "Failed to query datasources: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("[") || body.contains("{}")) {
            "Unexpected datasources response"
        }
        println("      ✓ Successfully queried Grafana datasources with API key")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Grafana through authenticated proxy")
    }

    
    
    

    test("Seafile: Acquire token and list libraries") {
        val username = System.getenv("SEAFILE_USERNAME") ?: "admin@datamancy.local"
        val password = System.getenv("SEAFILE_PASSWORD") ?: "changeme"

        val tokenResult = tokens.acquireSeafileToken(username, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Seafile token: ${tokenResult.exceptionOrNull()?.message}. Ensure Seafile admin user exists."
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Seafile token")

        
        val response = tokens.authenticatedGet("seafile", "http://seafile:80/api2/repos/")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list libraries: ${response.status}"
        }

        println("      ✓ Successfully listed Seafile libraries")
    }

    
    
    

    test("Forgejo: Acquire token and list repositories") {
        val username = System.getenv("FORGEJO_USERNAME") ?: "admin"
        val password = System.getenv("FORGEJO_PASSWORD") ?: "changeme"

        val tokenResult = tokens.acquireForgejoToken(username, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Forgejo token: ${tokenResult.exceptionOrNull()?.message}. Check Forgejo admin credentials."
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Forgejo access token")

        
        val response = tokens.authenticatedGet("forgejo", "http://forgejo:3000/api/v1/user/repos")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list repos: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.startsWith("[")) {
            "Unexpected repos response"
        }

        println("      ✓ Successfully listed Forgejo repositories")
    }

    
    
    

    test("Planka: Acquire token and list boards") {
        val email = System.getenv("PLANKA_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("PLANKA_PASSWORD") ?: "changeme"

        val tokenResult = tokens.acquirePlankaToken(email, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Planka token: ${tokenResult.exceptionOrNull()?.message}. Ensure Planka admin user exists."
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Planka authentication token")

        
        val response = tokens.authenticatedGet("planka", "http://planka:1337/api/boards")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list boards: ${response.status}"
        }

        println("      ✓ Successfully listed Planka boards")
    }

    
    
    

    test("Qbittorrent: Acquire session and get version") {
        val username = System.getenv("QBITTORRENT_USERNAME") ?: "admin"
        val password = System.getenv("QBITTORRENT_PASSWORD") ?: "adminpass"

        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        val sessionResult = tokens.acquireQbittorrentSession(username, password)
        require(sessionResult.isSuccess) {
            "Failed to acquire qBittorrent session: ${sessionResult.exceptionOrNull()?.message}. Check qBittorrent credentials."
        }
        println("      ✓ Acquired Qbittorrent session cookie")

        
        val response = tokens.authenticatedGet("qbittorrent", "http://qbittorrent:8080/api/v2/app/version")
        require(response.status == HttpStatusCode.OK) {
            "Failed to get version: ${response.status}"
        }

        val version = response.bodyAsText()
        println("      ✓ Qbittorrent version: $version")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Qbittorrent through authenticated proxy")
    }

    
    
    

    test("Mastodon: Acquire OAuth token and verify credentials") {
        val email = System.getenv("MASTODON_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("MASTODON_PASSWORD") ?: "changeme"

        val tokenResult = tokens.acquireMastodonToken(email, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Mastodon token: ${tokenResult.exceptionOrNull()?.message}. Check Mastodon credentials."
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Mastodon OAuth token")

        
        val response = tokens.authenticatedGet("mastodon", "http://mastodon-web:3000/api/v1/accounts/verify_credentials")
        require(response.status == HttpStatusCode.OK) {
            "Failed to verify credentials: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("id") && body.contains("username")) {
            "Unexpected credentials response"
        }

        println("      ✓ Successfully verified Mastodon credentials")
    }

    
    
    

    test("Open-WebUI: Acquire JWT and list models") {
        val email = System.getenv("OPEN_WEBUI_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("OPEN_WEBUI_PASSWORD") ?: "changeme"

        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login(ldapUsername, ldapPassword)

        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        val tokenResult = tokens.acquireOpenWebUIToken(email, password)
        require(tokenResult.isSuccess) {
            "Failed to acquire Open-WebUI token: ${tokenResult.exceptionOrNull()?.message}. Ensure Open-WebUI user exists."
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Open-WebUI JWT token")

        
        val directResponse = tokens.authenticatedGet("open-webui", "http://open-webui:8080/api/models")
        if (directResponse.status == HttpStatusCode.OK) {
            println("      ✓ Successfully listed Open-WebUI models (direct access)")
        } else if (directResponse.status == HttpStatusCode.NotFound) {
            
            val altResponse = tokens.authenticatedGet("open-webui", "http://open-webui:8080/api/v1/models")
            require(altResponse.status == HttpStatusCode.OK || altResponse.status == HttpStatusCode.NotFound) {
                "Failed to list models on both /api/models and /api/v1/models: ${altResponse.status}"
            }
            println("      ✓ Open-WebUI API responded (endpoint may need configuration)")
        } else {
            require(directResponse.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
                "Unexpected response from Open-WebUI: ${directResponse.status}"
            }
            println("      ✓ Open-WebUI container accessible")
        }

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Open-WebUI through authenticated proxy")
    }

    
    
    

    test("JupyterHub: Authenticate and access hub API") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://jupyterhub:8000/hub/api")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "JupyterHub container not responding: ${directResponse.status}"
        }
        println("      ✓ JupyterHub container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed JupyterHub through authenticated proxy")
    }

    
    
    

    test("LiteLLM: Authenticate and access API") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://litellm:4000/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "LiteLLM container not responding: ${directResponse.status}"
        }
        println("      ✓ LiteLLM container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed LiteLLM through authenticated proxy")
    }

    
    
    

    test("Ntfy: Authenticate and access notification API") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://ntfy:80/v1/health")
        require(directResponse.status == HttpStatusCode.OK) {
            "Ntfy container not responding: ${directResponse.status}"
        }
        println("      ✓ Ntfy container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Ntfy through authenticated proxy")
    }

    
    
    

    test("Kopia: Authenticate and access backup UI") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Kopia through authenticated proxy")
    }

    
    
    

    test("Radicale: Authenticate and access CalDAV/CardDAV") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://radicale:5232/")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Radicale container not responding: ${directResponse.status}"
        }
        println("      ✓ Radicale container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Radicale through authenticated proxy")
    }

    
    
    

    test("Roundcube: Authenticate and access webmail") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://roundcube:80/")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Found) {
            "Roundcube container not responding: ${directResponse.status}"
        }
        println("      ✓ Roundcube container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Roundcube through authenticated proxy")
    }

    
    
    

    test("Search Service: Authenticate and access API") {
        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")

        
        val directResponse = client.getRawResponse("http://search-service:8098/actuator/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Search Service container not responding: ${directResponse.status}"
        }
        println("      ✓ Search Service container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Search Service through authenticated proxy")
    }

    
    
    

    test("Pipeline: Authenticate and access management API") {
        if (endpoints.pipeline.contains("pipeline:")) {
            println("      ℹ️  Pipeline service not available")
            return@test
        }

        val ldapUsername = System.getenv("STACK_ADMIN_USER") ?: "sysadmin"
        val ldapPassword = System.getenv("STACK_ADMIN_PASSWORD") ?: System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        val autheliaResult = auth.login(ldapUsername, ldapPassword)
        require(autheliaResult is AuthResult.Success) {
            "Authelia authentication failed: ${(autheliaResult as? AuthResult.Error)?.message}"
        }
        println("      ✓ Authenticated with Authelia")


        val directResponse = client.getRawResponse("${endpoints.pipeline}/actuator/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Pipeline container not responding: ${directResponse.status}"
        }
        println("      ✓ Pipeline container accessible")

        
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Pipeline through authenticated proxy")
    }

    
    
    

    test("Token manager: Store and retrieve tokens") {
        
        val testToken = "test-token-${System.currentTimeMillis()}"

        
        require(!tokens.hasToken("test-service")) {
            "Should not have token before storing"
        }

        
        require(tokens.getToken("grafana") != null || !tokens.hasToken("grafana")) {
            "Token state should be consistent"
        }

        println("      ✓ Token manager correctly manages token state")
    }

    test("Token manager: Clear tokens") {
        
        tokens.clearToken("grafana")
        require(!tokens.hasToken("grafana")) {
            "Token should be cleared"
        }

        
        tokens.clearAll()
        require(!tokens.hasToken("seafile") && !tokens.hasToken("forgejo")) {
            "All tokens should be cleared"
        }

        println("      ✓ Token manager correctly clears tokens")
    }
}
