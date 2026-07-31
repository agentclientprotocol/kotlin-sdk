@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.v2.conversion.LEGACY_V1_PLAN_ID
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.ContentBlock as V1ContentBlock
import com.agentclientprotocol.model.PlanEntry as V1PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority as V1PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus as V1PlanEntryStatus
import com.agentclientprotocol.model.PlanVariant as V1PlanVariant
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate

class SessionUpdateConversionTest {

    // Chunks

    @Test
    fun `converts content chunks in both directions`() {
        val v2 = SessionUpdate.AgentMessageChunk(
            ContentChunk(messageId = MessageId("msg_1"), content = ContentBlock.Text(text = "hi")),
        )
        val v1 = V1SessionUpdate.AgentMessageChunk(
            content = V1ContentBlock.Text(text = "hi"),
            messageId = MessageId("msg_1"),
        )

        assertEquals(listOf(v1), v2.toV1())
        assertEquals(v2, v1.toV2())
    }

    @Test
    fun `converting a v1 chunk without a message id fails because v2 requires one`() {
        val v1 = V1SessionUpdate.UserMessageChunk(content = V1ContentBlock.Text(text = "hi"))

        assertFailsWith<ProtocolConversionException> { v1.toV2() }
    }

    // Variants with no v1 representation

    @Test
    fun `state updates and tool call content chunks have no v1 form`() {
        assertFailsWith<ProtocolConversionException> {
            SessionUpdate.StateUpdate(StateUpdate.Idle()).toV1()
        }
        assertFailsWith<ProtocolConversionException> {
            SessionUpdate.ToolCallContentChunk(
                ToolCallContentChunk(
                    toolCallId = ToolCallId("tc_1"),
                    content = ToolCallContent.Content(content = ContentBlock.Text(text = "out")),
                ),
            ).toV1()
        }
    }

    @Test
    fun `an unknown v2 update has no v1 form`() {
        assertFailsWith<ProtocolConversionException> {
            SessionUpdate.Unknown("_vendor", buildJsonObject { put("sessionUpdate", JsonPrimitive("_vendor")) })
                .toV1()
        }
    }

    @Test
    fun `an unknown v1 update crosses to v2 unchanged`() {
        val rawJson = buildJsonObject { put("sessionUpdate", JsonPrimitive("_vendor")) }
        val v1 = V1SessionUpdate.UnknownSessionUpdate(sessionUpdateType = "_vendor", rawJson = rawJson)

        assertEquals(SessionUpdate.Unknown("_vendor", rawJson), v1.toV2())
    }

    @Test
    fun `current mode update was removed in v2`() {
        assertFailsWith<ProtocolConversionException> {
            V1SessionUpdate.CurrentModeUpdate(currentModeId = SessionModeId("ask")).toV2()
        }
    }

    // Plans

    @Test
    fun `a v1 plan becomes a v2 plan update under the legacy plan id`() {
        val v1 = V1SessionUpdate.PlanUpdate(
            entries = listOf(
                V1PlanEntry("Do it", V1PlanEntryPriority.HIGH, V1PlanEntryStatus.PENDING),
            ),
        )

        assertEquals(
            SessionUpdate.PlanUpdate(
                PlanUpdate(
                    plan = PlanUpdateContent.Items(
                        planId = LEGACY_V1_PLAN_ID,
                        entries = listOf(
                            PlanEntry("Do it", PlanEntryPriority.High, PlanEntryStatus.Pending),
                        ),
                    ),
                ),
            ),
            (v1 as V1SessionUpdate).toV2(),
        )
    }

    @Test
    fun `itemized plans convert down to v1's plan update`() {
        val update = SessionUpdate.PlanUpdate(
            PlanUpdate(
                plan = PlanUpdateContent.Items(
                    planId = PlanId("p1"),
                    entries = listOf(PlanEntry("Do it", PlanEntryPriority.Low, PlanEntryStatus.Completed)),
                ),
            ),
        )

        assertEquals(
            V1SessionUpdate.PlanUpdate(
                entries = listOf(V1PlanEntry("Do it", V1PlanEntryPriority.LOW, V1PlanEntryStatus.COMPLETED)),
            ),
            update.toV1().single(),
        )
    }

    @Test
    fun `file and markdown plans convert down to v1's plan variant which keeps the id`() {
        val file = SessionUpdate.PlanUpdate(
            PlanUpdate(plan = PlanUpdateContent.File(planId = PlanId("p1"), uri = "file:///plan.md")),
        )
        val markdown = SessionUpdate.PlanUpdate(
            PlanUpdate(plan = PlanUpdateContent.Markdown(planId = PlanId("p1"), content = "# Plan")),
        )

        assertEquals(
            V1SessionUpdate.PlanUpdateV2(V1PlanVariant.File(id = "p1", uri = "file:///plan.md")),
            file.toV1().single(),
        )
        assertEquals(
            V1SessionUpdate.PlanUpdateV2(V1PlanVariant.Markdown(id = "p1", content = "# Plan")),
            markdown.toV1().single(),
        )
        // and back
        assertEquals(file, (file.toV1().single() as V1SessionUpdate.PlanUpdateV2).toV2().let(SessionUpdate::PlanUpdate))
    }

    @Test
    fun `an unknown plan content type has no v1 form`() {
        val update = SessionUpdate.PlanUpdate(
            PlanUpdate(
                plan = PlanUpdateContent.Unknown(
                    type = "_vendor",
                    planId = PlanId("p1"),
                    rawJson = buildJsonObject { put("planId", JsonPrimitive("p1")) },
                ),
            ),
        )

        assertFailsWith<ProtocolConversionException> { update.toV1() }
    }

    @Test
    fun `plan removal round-trips through v1's differently named id field`() {
        val v2 = SessionUpdate.PlanRemoved(PlanRemoved(planId = PlanId("p1")))

        assertEquals(V1SessionUpdate.PlanRemoved(id = "p1"), v2.toV1().single())
        assertEquals(v2, V1SessionUpdate.PlanRemoved(id = "p1").toV2().let(SessionUpdate::PlanRemoved))
    }

    // Remaining payloads

    @Test
    fun `commands updates convert in both directions`() {
        val v2 = SessionUpdate.AvailableCommandsUpdate(
            AvailableCommandsUpdate(
                availableCommands = listOf(
                    AvailableCommand(name = "c", description = "d", input = AvailableCommandInput.Text(hint = "h")),
                ),
            ),
        )

        val v1 = assertIs<V1SessionUpdate.AvailableCommandsUpdate>(v2.toV1().single())
        assertEquals("c", v1.availableCommands.single().name)
        assertEquals(v2, v1.toV2().let(SessionUpdate::AvailableCommandsUpdate))
    }

    @Test
    fun `a commands update with meta cannot be represented in v1`() {
        val v2 = SessionUpdate.AvailableCommandsUpdate(
            AvailableCommandsUpdate(
                availableCommands = emptyList(),
                _meta = buildJsonObject { put("x", JsonPrimitive(1)) },
            ),
        )

        assertFailsWith<ProtocolConversionException> { v2.toV1() }
    }

    @Test
    fun `commands whose input cannot cross are skipped`() {
        val v2 = SessionUpdate.AvailableCommandsUpdate(
            AvailableCommandsUpdate(
                availableCommands = listOf(
                    AvailableCommand(name = "keep", description = "d"),
                    AvailableCommand(
                        name = "drop",
                        description = "d",
                        input = AvailableCommandInput.Unknown("_vendor", buildJsonObject { }),
                    ),
                ),
            ),
        )

        val v1 = assertIs<V1SessionUpdate.AvailableCommandsUpdate>(v2.toV1().single())
        assertEquals(listOf("keep"), v1.availableCommands.map { it.name })
    }

    @Test
    fun `usage updates round-trip`() {
        val v2 = SessionUpdate.UsageUpdate(UsageUpdate(used = 10, size = 100))

        assertEquals(V1SessionUpdate.UsageUpdate(used = 10, size = 100), v2.toV1().single())
        assertEquals(v2, V1SessionUpdate.UsageUpdate(used = 10, size = 100).toV2().let(SessionUpdate::UsageUpdate))
    }

    @Test
    fun `config option updates round-trip`() {
        val v1 = V1SessionUpdate.ConfigOptionUpdate(configOptions = emptyList())

        assertEquals(
            SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(configOptions = emptyList())),
            (v1 as V1SessionUpdate).toV2(),
        )
    }
}
