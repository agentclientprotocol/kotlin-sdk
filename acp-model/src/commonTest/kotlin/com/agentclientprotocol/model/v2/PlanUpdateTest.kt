@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PlanUpdateTest {

    private val entry = PlanEntry(
        content = "Port the plan payloads",
        priority = PlanEntryPriority.High,
        status = PlanEntryStatus.InProgress,
    )

    // Known content types

    @Test
    fun `decodes an itemized plan`() {
        assertEquals(
            PlanUpdate(plan = PlanUpdateContent.Items(planId = PlanId("main"), entries = listOf(entry))),
            decode(
                """{"plan":{"type":"items","planId":"main","entries":[""" +
                    """{"content":"Port the plan payloads","priority":"high","status":"in_progress"}]}}""",
            ),
        )
    }

    @Test
    fun `encodes an itemized plan with a leading discriminator`() {
        assertEquals(
            """{"plan":{"type":"items","planId":"main","entries":[""" +
                """{"content":"Port the plan payloads","priority":"high","status":"in_progress"}]}}""",
            encode(PlanUpdate(plan = PlanUpdateContent.Items(planId = PlanId("main"), entries = listOf(entry)))),
        )
    }

    @Test
    fun `round-trips the unstable file and markdown content types`() {
        val file = """{"plan":{"type":"file","planId":"p1","uri":"file:///tmp/plan.md"}}"""
        val markdown = """{"plan":{"type":"markdown","planId":"p1","content":"# Plan"}}"""

        assertEquals(
            PlanUpdate(plan = PlanUpdateContent.File(planId = PlanId("p1"), uri = "file:///tmp/plan.md")),
            decode(file),
        )
        assertEquals(file, encode(decode(file)))
        assertEquals(
            PlanUpdate(plan = PlanUpdateContent.Markdown(planId = PlanId("p1"), content = "# Plan")),
            decode(markdown),
        )
        assertEquals(markdown, encode(decode(markdown)))
    }

    @Test
    fun `every content variant exposes its plan id`() {
        val contents = listOf(
            PlanUpdateContent.Items(planId = PlanId("p1"), entries = emptyList()),
            PlanUpdateContent.File(planId = PlanId("p1"), uri = "file:///plan.md"),
            PlanUpdateContent.Markdown(planId = PlanId("p1"), content = "# Plan"),
        )

        contents.forEach { assertEquals(PlanId("p1"), it.planId) }
    }

    @Test
    fun `preserves an unknown plan entry priority and status`() {
        val decoded = decode(
            """{"plan":{"type":"items","planId":"main","entries":[""" +
                """{"content":"x","priority":"_urgent","status":"_blocked"}]}}""",
        )

        val items = decoded.plan
        assertIs<PlanUpdateContent.Items>(items)
        assertEquals(PlanEntryPriority.Unknown("_urgent"), items.entries.single().priority)
        assertEquals(PlanEntryStatus.Unknown("_blocked"), items.entries.single().status)
    }

    // Open union behaviour

    @Test
    fun `preserves unknown plan content byte-identically and keeps the plan id`() {
        val json = """{"plan":{"type":"_vendor_graph","planId":"p1","nodes":[1,2]}}"""

        val decoded = decode(json)
        val unknown = decoded.plan
        assertIs<PlanUpdateContent.Unknown>(unknown)
        assertEquals("_vendor_graph", unknown.type)
        assertEquals(PlanId("p1"), unknown.planId)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `unknown plan content without a plan id fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"plan":{"type":"_vendor_graph","nodes":[1,2]}}""")
        }
    }

    @Test
    fun `unknown plan content with a non-string plan id fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"plan":{"type":"_vendor_graph","planId":{"nested":true}}}""")
        }
    }

    @Test
    fun `missing type discriminator fails`() {
        assertFailsWith<SerializationException> { decode("""{"plan":{"planId":"p1","entries":[]}}""") }
    }

    @Test
    fun `known content type with a malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> { decode("""{"plan":{"type":"items","entries":[]}}""") }
    }

    private fun decode(json: String): PlanUpdate =
        ACPJson.decodeFromString(PlanUpdate.serializer(), json)

    private fun encode(update: PlanUpdate): String =
        ACPJson.encodeToString(PlanUpdate.serializer(), update)
}
