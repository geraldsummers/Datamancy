package org.datamancy.controlpanel

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {

    @Test
    fun `test SimpleMessage serialization`() {
        val message = SimpleMessage("test message")
        assertEquals("test message", message.message)
    }
}
