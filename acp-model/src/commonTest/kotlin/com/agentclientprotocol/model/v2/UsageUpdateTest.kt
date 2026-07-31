@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UsageUpdateTest {

    @Test
    fun `decodes context usage with a cumulative cost`() {
        assertEquals(
            UsageUpdate(used = 12_000, size = 200_000, cost = Cost(amount = 0.42, currency = "USD")),
            decode("""{"used":12000,"size":200000,"cost":{"amount":0.42,"currency":"USD"}}"""),
        )
    }

    @Test
    fun `encodes context usage with a cumulative cost`() {
        assertEquals(
            """{"used":12000,"size":200000,"cost":{"amount":0.42,"currency":"USD"}}""",
            encode(UsageUpdate(used = 12_000, size = 200_000, cost = Cost(amount = 0.42, currency = "USD"))),
        )
    }

    @Test
    fun `omits an absent cost`() {
        val update = UsageUpdate(used = 1, size = 2)

        assertEquals("""{"used":1,"size":2}""", encode(update))
        assertNull(decode("""{"used":1,"size":2}""").cost)
    }

    @Test
    fun `requires both token counts`() {
        assertFailsWith<SerializationException> { decode("""{"used":1}""") }
        assertFailsWith<SerializationException> { decode("""{"size":2}""") }
    }

    private fun decode(json: String): UsageUpdate =
        ACPJson.decodeFromString(UsageUpdate.serializer(), json)

    private fun encode(update: UsageUpdate): String =
        ACPJson.encodeToString(UsageUpdate.serializer(), update)
}
