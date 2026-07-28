@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Usage
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StateUpdateTest {

    // Known states

    @Test
    fun `decodes the three known states`() {
        assertEquals(StateUpdate.Running(), decode("""{"state":"running"}"""))
        assertEquals(StateUpdate.Idle(), decode("""{"state":"idle"}"""))
        assertEquals(StateUpdate.RequiresAction(), decode("""{"state":"requires_action"}"""))
    }

    @Test
    fun `encodes states with a leading state discriminator`() {
        assertEquals("""{"state":"running"}""", encode(StateUpdate.Running()))
        assertEquals("""{"state":"requires_action"}""", encode(StateUpdate.RequiresAction()))
    }

    @Test
    fun `idle carries an optional stop reason`() {
        val json = """{"state":"idle","stopReason":"end_turn"}"""

        assertEquals(StateUpdate.Idle(stopReason = StopReason.EndTurn), decode(json))
        assertEquals(json, encode(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
    }

    @Test
    fun `idle carries optional token usage`() {
        val idle = StateUpdate.Idle(
            stopReason = StopReason.EndTurn,
            usage = Usage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
        )
        val json =
            """{"state":"idle","stopReason":"end_turn",""" +
                """"usage":{"inputTokens":100,"outputTokens":20,"totalTokens":120}}"""

        assertEquals(idle, decode(json))
        assertEquals(json, encode(idle))
    }

    @Test
    fun `idle preserves an unknown stop reason`() {
        val decoded = decode("""{"state":"idle","stopReason":"_vendor_stop"}""")

        assertIs<StateUpdate.Idle>(decoded)
        assertEquals(StopReason.Unknown("_vendor_stop"), decoded.stopReason)
    }

    // Open union behaviour

    @Test
    fun `preserves an unknown state byte-identically`() {
        val json = """{"state":"_vendor_paused","until":"2026-01-01T00:00:00Z"}"""

        val decoded = decode(json)
        assertIs<StateUpdate.Unknown>(decoded)
        assertEquals("_vendor_paused", decoded.state)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `preserves a future ACP state byte-identically`() {
        val json = """{"state":"suspended","reason":"quota"}"""

        val decoded = decode(json)
        assertIs<StateUpdate.Unknown>(decoded)
        assertEquals("suspended", decoded.state)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `missing state discriminator fails`() {
        assertFailsWith<SerializationException> { decode("""{"stopReason":"end_turn"}""") }
    }

    @Test
    fun `non-string state discriminator fails`() {
        assertFailsWith<SerializationException> { decode("""{"state":42}""") }
    }

    @Test
    fun `known state with a malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"state":"idle","usage":{"inputTokens":"not a number"}}""")
        }
    }

    private fun decode(json: String): StateUpdate =
        ACPJson.decodeFromString(StateUpdate.serializer(), json)

    private fun encode(update: StateUpdate): String =
        ACPJson.encodeToString(StateUpdate.serializer(), update)
}
