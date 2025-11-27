package org.example.host

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CapabilityPolicyTest {
    @Test
    fun `no restrictions allow anything`() {
        val policy = CapabilityPolicy(allowed = emptySet())
        assertDoesNotThrow {
            enforceCapabilities(policy, "p1", listOf("network", "filesystem"))
        }
    }

    @Test
    fun `allowed capabilities pass`() {
        val policy = CapabilityPolicy(allowed = setOf("network", "filesystem"))
        assertDoesNotThrow {
            enforceCapabilities(policy, "p1", listOf("network"))
        }
    }

    @Test
    fun `disallowed capabilities throw`() {
        val policy = CapabilityPolicy(allowed = setOf("network"))
        assertThrows(CapabilityViolation::class.java) {
            enforceCapabilities(policy, "p1", listOf("filesystem"))
        }
    }
}
