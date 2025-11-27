package org.example.manifest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VersionTest {
    @Test
    fun `semver compare works`() {
        assertTrue(SemVer.parse("1.2.3") < SemVer.parse("1.2.4"))
        assertTrue(SemVer.parse("1.2.3") < SemVer.parse("1.3.0"))
        assertTrue(SemVer.parse("1.2.3") < SemVer.parse("2.0.0"))
        assertEquals(SemVer(1,2,3), SemVer.parse("1.2.3"))
    }

    @Test
    fun `constraint exact match`() {
        val c = VersionConstraint.parse("1.2.3")!!
        assertTrue(c.matches(SemVer.parse("1.2.3")))
        assertFalse(c.matches(SemVer.parse("1.2.4")))
    }

    @Test
    fun `constraint with ranges`() {
        val c = VersionConstraint.parse(">=1.2.3 <2.0.0")!!
        assertTrue(c.matches(SemVer.parse("1.2.3")))
        assertTrue(c.matches(SemVer.parse("1.9.9")))
        assertFalse(c.matches(SemVer.parse("2.0.0")))
        assertFalse(c.matches(SemVer.parse("1.2.2")))
    }

    @Test
    fun `constraint with wildcards`() {
        val c1 = VersionConstraint.parse("1.2.x")!!
        assertTrue(c1.matches(SemVer.parse("1.2.0")))
        assertTrue(c1.matches(SemVer.parse("1.2.99")))
        assertFalse(c1.matches(SemVer.parse("1.3.0")))

        val c2 = VersionConstraint.parse("1.x")!!
        assertTrue(c2.matches(SemVer.parse("1.0.0")))
        assertTrue(c2.matches(SemVer.parse("1.9.9")))
        assertFalse(c2.matches(SemVer.parse("2.0.0")))
    }
}
