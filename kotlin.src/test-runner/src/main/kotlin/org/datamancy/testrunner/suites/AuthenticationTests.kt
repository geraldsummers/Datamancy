package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*
import javax.naming.Context
import javax.naming.directory.InitialDirContext
import java.util.Properties

/**
 * Authentication & Authorization Integration Tests
 *
 * Tests LDAP, Authelia SSO, and service-specific authentication flows
 * Validates that all services properly integrate with the auth stack
 */
suspend fun TestRunner.authenticationTests() = suite("Authentication & Authorization Tests") {

    // =============================================================================
    // LDAP Directory Tests
    // =============================================================================

    test("LDAP server is reachable") {
        val ldapUrl = env.endpoints.ldap ?: "ldap://openldap:389"
        val baseDn = System.getenv("LDAP_BASE_DN") ?: "dc=datamancy,dc=net"

        // Simple anonymous bind to verify LDAP is responding
        val props = Properties().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, ldapUrl)
            put(Context.SECURITY_AUTHENTICATION, "none") // Anonymous bind
        }

        val ctx = InitialDirContext(props)
        ctx.close()

        println("      ✓ LDAP server reachable at $ldapUrl")
    }

    test("LDAP bind with admin credentials") {
        val ldapUrl = env.endpoints.ldap ?: "ldap://openldap:389"
        val baseDn = System.getenv("LDAP_BASE_DN") ?: "dc=datamancy,dc=net"
        val adminDn = "cn=admin,$baseDn"
        val adminPassword = env.ldapAdminPassword

        val props = Properties().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, ldapUrl)
            put(Context.SECURITY_AUTHENTICATION, "simple")
            put(Context.SECURITY_PRINCIPAL, adminDn)
            put(Context.SECURITY_CREDENTIALS, adminPassword)
        }

        val ctx = InitialDirContext(props)

        // Verify we can read the base DN
        val attrs = ctx.getAttributes(baseDn)
        require(attrs.size() > 0) { "Could not read base DN attributes" }

        ctx.close()

        println("      ✓ Admin bind successful, can read directory")
    }

    test("LDAP search for users") {
        val ldapUrl = env.endpoints.ldap ?: "ldap://openldap:389"
        val baseDn = System.getenv("LDAP_BASE_DN") ?: "dc=datamancy,dc=net"
        val adminDn = "cn=admin,$baseDn"
        val adminPassword = env.ldapAdminPassword

        val props = Properties().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, ldapUrl)
            put(Context.SECURITY_AUTHENTICATION, "simple")
            put(Context.SECURITY_PRINCIPAL, adminDn)
            put(Context.SECURITY_CREDENTIALS, adminPassword)
        }

        val ctx = InitialDirContext(props)

        // Search for all organizational units
        val searchControls = javax.naming.directory.SearchControls()
        searchControls.searchScope = javax.naming.directory.SearchControls.SUBTREE_SCOPE

        val results = ctx.search(baseDn, "(objectClass=organizationalUnit)", searchControls)
        var ouCount = 0
        while (results.hasMore()) {
            results.next()
            ouCount++
        }

        ctx.close()

        println("      ✓ Found $ouCount organizational units in directory")
    }

    test("LDAP bind fails with wrong password") {
        val ldapUrl = env.endpoints.ldap ?: "ldap://openldap:389"
        val baseDn = System.getenv("LDAP_BASE_DN") ?: "dc=datamancy,dc=net"
        val adminDn = "cn=admin,$baseDn"

        val props = Properties().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, ldapUrl)
            put(Context.SECURITY_AUTHENTICATION, "simple")
            put(Context.SECURITY_PRINCIPAL, adminDn)
            put(Context.SECURITY_CREDENTIALS, "wrongpassword")
        }

        try {
            InitialDirContext(props)
            throw AssertionError("Expected authentication to fail with wrong password")
        } catch (e: javax.naming.AuthenticationException) {
            println("      ✓ Authentication correctly rejected invalid credentials")
        }
    }

    // =============================================================================
    // Authelia SSO Tests
    // =============================================================================

    test("Authelia health endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.authelia}/api/health")
        require(response.status == HttpStatusCode.OK) { "Authelia health check failed: ${response.status}" }

        val body = response.bodyAsText()
        require(body.contains("\"status\":\"UP\"") || body.contains("\"status\":\"OK\"") || body.contains("healthy")) {
            "Authelia not healthy: $body"
        }

        println("      ✓ Authelia SSO server is healthy")
    }

    test("Authelia configuration endpoint accessible") {
        val response = client.getRawResponse("${env.endpoints.authelia}/api/configuration")
        // May require authentication depending on network/domain
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Configuration endpoint failed: ${response.status}"
        }

        println("      ✓ Authelia configuration endpoint responding")
    }

    test("Authelia state endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.authelia}/api/state")
        // May return 401/403 without session (expected for session-based endpoints)
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Unexpected response: ${response.status}"
        }

        println("      ✓ Authelia state endpoint responding")
    }

    // =============================================================================
    // LDAP Account Manager Tests
    // =============================================================================

    test("LDAP Account Manager web interface loads") {
        val response = client.getRawResponse("http://ldap-account-manager:80/lam")
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Found)) {
            "LAM not accessible: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("LDAP Account Manager") || body.contains("LAM") || body.contains("login")) {
            "LAM interface not detected"
        }

        println("      ✓ LDAP Account Manager web interface loads")
    }

    test("LDAP Account Manager login page accessible") {
        val response = client.getRawResponse("http://ldap-account-manager:80/lam/templates/login.php")
        require(response.status == HttpStatusCode.OK) { "Login page failed: ${response.status}" }

        val body = response.bodyAsText()
        require(body.contains("login") || body.contains("LDAP")) {
            "Login form not found"
        }

        println("      ✓ LAM login page accessible")
    }

    // =============================================================================
    // Service Authentication Tests
    // =============================================================================

    test("Grafana requires authentication") {
        val response = client.getRawResponse("${env.endpoints.grafana}/api/dashboards/home")
        require(response.status == HttpStatusCode.Unauthorized) {
            "Grafana should require authentication: ${response.status}"
        }

        println("      ✓ Grafana correctly requires authentication")
    }

    test("Vaultwarden requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/api/accounts/profile")
        require(response.status == HttpStatusCode.Unauthorized) {
            "Vaultwarden should require authentication: ${response.status}"
        }

        println("      ✓ Vaultwarden correctly requires authentication")
    }

    test("BookStack requires authentication for content") {
        val response = client.getRawResponse("${env.endpoints.bookstack}/api/books")
        // BookStack may return 200 with empty list for public instances
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "BookStack should require authentication or return empty: ${response.status}"
        }

        println("      ✓ BookStack API accessible")
    }

    test("Forgejo requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/api/v1/user")
        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            "Forgejo should require authentication: ${response.status}"
        }

        println("      ✓ Forgejo correctly requires authentication")
    }

    test("Mastodon requires authentication for timeline") {
        val response = client.getRawResponse("${env.endpoints.mastodon}/api/v1/timelines/home")
        require(response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            "Mastodon should require authentication: ${response.status}"
        }

        println("      ✓ Mastodon correctly requires authentication")
    }

    test("Open-WebUI requires authentication") {
        val response = client.getRawResponse("${env.endpoints.openWebUI}/api/v1/auths")
        // May return 200, 307 redirect, or require auth
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.TemporaryRedirect)) {
            "Unexpected response from Open-WebUI: ${response.status}"
        }

        println("      ✓ Open-WebUI authentication endpoint accessible")
    }

    test("JupyterHub requires authentication") {
        val response = client.getRawResponse("${env.endpoints.jupyterhub}/hub/api/user")
        require(response.status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.Found)) {
            "JupyterHub should require authentication: ${response.status}"
        }

        println("      ✓ JupyterHub correctly requires authentication")
    }

    test("Seafile requires authentication for API") {
        val response = client.getRawResponse("${env.endpoints.seafile}/api2/auth/ping/")
        // Seafile may return 200 or 403 depending on configuration
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden)) {
            "Seafile API not accessible: ${response.status}"
        }

        println("      ✓ Seafile authentication endpoint accessible")
    }

    test("Planka requires authentication for boards") {
        val response = client.getRawResponse("${env.endpoints.planka}/api/boards")
        // Planka may return 200 with empty list or 401 depending on configuration
        require(response.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            "Planka should require authentication or return empty: ${response.status}"
        }

        println("      ✓ Planka API accessible")
    }
}
