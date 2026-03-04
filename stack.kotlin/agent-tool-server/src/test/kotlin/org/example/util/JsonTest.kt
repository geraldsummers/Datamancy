package org.example.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

data class User(val id: Int, val name: String, val email: String? = null)

class JsonTest {
    @Test
    fun `serialize and deserialize kotlin data class`() {
        val user = User(1, "Alice", null)
        val json = Json.mapper.writeValueAsString(user)
        val roundTrip = Json.mapper.readValue(json, User::class.java)
        assertEquals(user, roundTrip)
    }

    @Test
    fun `ignore unknown fields on deserialize`() {
        val json = """{"id":2,"name":"Bob","unknown":"x","email":null}"""
        val user = Json.mapper.readValue(json, User::class.java)
        assertEquals(2, user.id)
        assertEquals("Bob", user.name)
        assertNull(user.email)
    }
}
