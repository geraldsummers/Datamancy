package org.datamancy.datafetcher.validation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UrlValidatorTest {

    @Test
    fun `valid HTTP URL`() {
        val result = UrlValidator.validate("http://example.com/path")
        assertTrue(result.isValid)
        assertEquals("http://example.com/path", result.sanitizedUrl)
    }

    @Test
    fun `valid HTTPS URL`() {
        val result = UrlValidator.validate("https://example.com/path")
        assertTrue(result.isValid)
        assertEquals("https://example.com/path", result.sanitizedUrl)
    }

    @Test
    fun `URL with port`() {
        val result = UrlValidator.validate("https://example.com:8080/path")
        assertTrue(result.isValid)
        assertEquals("https://example.com:8080/path", result.sanitizedUrl)
    }

    @Test
    fun `URL with query parameters`() {
        val result = UrlValidator.validate("https://example.com/path?foo=bar&baz=qux")
        assertTrue(result.isValid)
        assertTrue(result.sanitizedUrl!!.contains("?foo=bar&baz=qux"))
    }

    @Test
    fun `URL with fragment`() {
        val result = UrlValidator.validate("https://example.com/path#section")
        assertTrue(result.isValid)
        assertTrue(result.sanitizedUrl!!.contains("#section"))
    }

    @Test
    fun `URL with trailing slash is normalized`() {
        val result = UrlValidator.validate("https://example.com/path/")
        assertTrue(result.isValid)
        assertEquals("https://example.com/path", result.sanitizedUrl)
    }

    @Test
    fun `missing scheme`() {
        val result = UrlValidator.validate("example.com")
        assertFalse(result.isValid)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("scheme"))
    }

    @Test
    fun `invalid scheme - ftp`() {
        val result = UrlValidator.validate("ftp://example.com")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("scheme"))
    }

    @Test
    fun `invalid scheme - file`() {
        val result = UrlValidator.validate("file:///etc/passwd")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("scheme"))
    }

    @Test
    fun `invalid scheme - javascript`() {
        val result = UrlValidator.validate("javascript:alert(1)")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("scheme"))
    }

    @Test
    fun `malformed URL`() {
        val result = UrlValidator.validate("http:/example.com")
        assertFalse(result.isValid)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `empty string`() {
        val result = UrlValidator.validate("")
        assertFalse(result.isValid)
    }

    @Test
    fun `URL without host`() {
        val result = UrlValidator.validate("http:///path")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("host"))
    }

    @Test
    fun `localhost rejected by default`() {
        val result = UrlValidator.validate("http://localhost:8080/api")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Localhost"))
    }

    @Test
    fun `127 dot 0 dot 0 dot 1 rejected by default`() {
        val result = UrlValidator.validate("http://127.0.0.1/api")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Localhost"))
    }

    @Test
    fun `localhost allowed with option`() {
        val options = UrlValidator.ValidationOptions(allowLocalhost = true)
        val result = UrlValidator.validate("http://localhost:8080/api", options)
        assertTrue(result.isValid)
    }

    @Test
    fun `private IP 10 dot x rejected by default`() {
        val result = UrlValidator.validate("http://10.0.0.1/api")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Private IP"))
    }

    @Test
    fun `private IP 192 dot 168 rejected by default`() {
        val result = UrlValidator.validate("http://192.168.1.1/api")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Private IP"))
    }

    @Test
    fun `private IP 172 dot 16 to 31 rejected by default`() {
        val result = UrlValidator.validate("http://172.16.0.1/api")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Private IP"))
    }

    @Test
    fun `private IP allowed with option`() {
        val options = UrlValidator.ValidationOptions(allowPrivateIps = true)
        val result = UrlValidator.validate("http://192.168.1.1/api", options)
        assertTrue(result.isValid)
    }

    @Test
    fun `AWS metadata endpoint blocked`() {
        val result = UrlValidator.validate("http://169.254.169.254/latest/meta-data/")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Private IP") || result.errorMessage!!.contains("metadata"))
    }

    @Test
    fun `GCP metadata endpoint blocked`() {
        val result = UrlValidator.validate("http://metadata.google.internal/computeMetadata/v1/")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("metadata"))
    }

    @Test
    fun `Oracle Cloud metadata endpoint blocked`() {
        val result = UrlValidator.validate("http://100.100.100.200/latest/meta-data/")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("metadata"))
    }

    @Test
    fun `URL with null bytes rejected`() {
        val url = "http://example.com/path\u0000"
        val result = UrlValidator.validate(url)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("null bytes"))
    }

    @Test
    fun `very long URL rejected`() {
        val longPath = "a".repeat(3000)
        val result = UrlValidator.validate("http://example.com/$longPath")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("maximum length"))
    }

    @Test
    fun `URL with double dots produces warning`() {
        val result = UrlValidator.validate("http://example.com/path/../other")
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("..") })
    }

    @Test
    fun `URL with at sign produces warning`() {
        val result = UrlValidator.validate("http://user:pass@example.com/path")
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("@") })
    }

    @Test
    fun `require HTTPS option enforced`() {
        val options = UrlValidator.ValidationOptions(requireHttps = true)
        val result = UrlValidator.validate("http://example.com/api", options)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("HTTPS"))
    }

    @Test
    fun `require HTTPS allows https`() {
        val options = UrlValidator.ValidationOptions(requireHttps = true)
        val result = UrlValidator.validate("https://example.com/api", options)
        assertTrue(result.isValid)
    }

    @Test
    fun `normalize removes default port for http`() {
        val result = UrlValidator.validate("http://example.com:80/path")
        assertTrue(result.isValid)
        assertEquals("http://example.com/path", result.sanitizedUrl)
    }

    @Test
    fun `normalize removes default port for https`() {
        val result = UrlValidator.validate("https://example.com:443/path")
        assertTrue(result.isValid)
        assertEquals("https://example.com/path", result.sanitizedUrl)
    }

    @Test
    fun `normalize keeps non-default port`() {
        val result = UrlValidator.validate("https://example.com:8443/path")
        assertTrue(result.isValid)
        assertEquals("https://example.com:8443/path", result.sanitizedUrl)
    }

    @Test
    fun `isValid convenience method`() {
        assertTrue(UrlValidator.isValid("https://example.com"))
        assertFalse(UrlValidator.isValid("localhost"))
        assertFalse(UrlValidator.isValid("http://192.168.1.1"))
    }

    @Test
    fun `validateOrThrow succeeds for valid URL`() {
        val normalized = UrlValidator.validateOrThrow("https://example.com/path/")
        assertEquals("https://example.com/path", normalized)
    }

    @Test
    fun `validateOrThrow throws for invalid URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlValidator.validateOrThrow("http://192.168.1.1")
        }
    }

    @Test
    fun `normalizeUrl function handles malformed URLs gracefully`() {
        val result = UrlValidator.normalizeUrl("not a url")
        assertNull(result)
    }
}
