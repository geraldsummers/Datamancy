package org.datamancy.testrunner

import org.datamancy.testrunner.framework.validateCredentials
import kotlin.test.*

class CredentialValidationTest {

    @Test
    fun `validateCredentials should accept valid credentials`() {
        // Should not throw
        validateCredentials("validuser", "validpass123")
    }

    @Test
    fun `validateCredentials should reject blank username`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("", "validpass123")
        }
        assertTrue(exception.message?.contains("blank") == true)
    }

    @Test
    fun `validateCredentials should reject blank password`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "")
        }
        assertTrue(exception.message?.contains("blank") == true)
    }

    @Test
    fun `validateCredentials should reject short username`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("ab", "validpass123")
        }
        assertTrue(exception.message?.contains("3-64 characters") == true)
    }

    @Test
    fun `validateCredentials should reject long username`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("a".repeat(65), "validpass123")
        }
        assertTrue(exception.message?.contains("3-64 characters") == true)
    }

    @Test
    fun `validateCredentials should reject short password`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "short")
        }
        assertTrue(exception.message?.contains("at least 8 characters") == true)
    }

    @Test
    fun `validateCredentials should reject weak password 'password'`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "password")
        }
        assertTrue(exception.message?.contains("weak") == true)
    }

    @Test
    fun `validateCredentials should reject weak password '12345678'`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "12345678")
        }
        assertTrue(exception.message?.contains("weak") == true)
    }

    @Test
    fun `validateCredentials should reject weak password 'admin123'`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "admin123")
        }
        assertTrue(exception.message?.contains("weak") == true)
    }

    @Test
    fun `validateCredentials should reject weak password 'test1234'`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "test1234")
        }
        assertTrue(exception.message?.contains("weak") == true)
    }

    @Test
    fun `validateCredentials should accept minimum length username`() {
        // Should not throw
        validateCredentials("abc", "validpass123")
    }

    @Test
    fun `validateCredentials should accept maximum length username`() {
        // Should not throw
        validateCredentials("a".repeat(64), "validpass123")
    }

    @Test
    fun `validateCredentials should accept minimum length password`() {
        // Should not throw
        validateCredentials("validuser", "pass1234")
    }

    @Test
    fun `validateCredentials should accept strong password`() {
        // Should not throw
        validateCredentials("validuser", "MyStr0ng!Pass")
    }

    @Test
    fun `validateCredentials should be case-insensitive for weak passwords`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            validateCredentials("validuser", "PASSWORD")
        }
        assertTrue(exception.message?.contains("weak") == true)
    }

    @Test
    fun `validateCredentials should accept username with numbers`() {
        // Should not throw
        validateCredentials("user123", "validpass123")
    }

    @Test
    fun `validateCredentials should accept username with underscores`() {
        // Should not throw
        validateCredentials("valid_user", "validpass123")
    }

    @Test
    fun `validateCredentials should accept email as username`() {
        // Should not throw
        validateCredentials("user@example.com", "validpass123")
    }
}
