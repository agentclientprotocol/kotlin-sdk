@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlanRemovedTest {

    @Test
    fun `round-trips a removal notice`() {
        val json = """{"planId":"p1"}"""

        assertEquals(PlanRemoved(planId = PlanId("p1")), decode(json))
        assertEquals(json, encode(PlanRemoved(planId = PlanId("p1"))))
    }

    @Test
    fun `round-trips meta`() {
        val removed = PlanRemoved(
            planId = PlanId("p1"),
            _meta = buildJsonObject { put("reason", JsonPrimitive("superseded")) },
        )
        val json = """{"planId":"p1","_meta":{"reason":"superseded"}}"""

        assertEquals(json, encode(removed))
        assertEquals(removed, decode(json))
    }

    @Test
    fun `requires a plan id`() {
        assertFailsWith<SerializationException> { decode("{}") }
    }

    private fun decode(json: String): PlanRemoved =
        ACPJson.decodeFromString(PlanRemoved.serializer(), json)

    private fun encode(removed: PlanRemoved): String =
        ACPJson.encodeToString(PlanRemoved.serializer(), removed)
}
