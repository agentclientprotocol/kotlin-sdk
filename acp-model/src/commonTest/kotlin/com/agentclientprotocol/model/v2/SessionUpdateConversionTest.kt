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
import kotlin.test.assertNull
import com.agentclientprotocol.model.ContentBlock as V1ContentBlock
import com.agentclientprotocol.model.PlanEntry as V1PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority as V1PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus as V1PlanEntryStatus
import com.agentclientprotocol.model.PlanVariant as V1PlanVariant
import com.agentclientprotocol.model.SessionUpdate as V1SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus as V1ToolCallStatus
import com.agentclientprotocol.model.ToolKind as V1ToolKind

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

    // Message upserts explode into one v1 chunk per content block

    @Test
    fun `a message upsert becomes one v1 chunk per content block`() {
        val update = SessionUpdate.AgentMessage(
            AgentMessage(
                messageId = MessageId("msg_1"),
                content = MaybeUndefined.Value(
                    listOf(ContentBlock.Text(text = "one"), ContentBlock.Text(text = "two")),
                ),
            ),
        )

        assertEquals(
            listOf(
                V1SessionUpdate.AgentMessageChunk(V1ContentBlock.Text(text = "one"), MessageId("msg_1")),
                V1SessionUpdate.AgentMessageChunk(V1ContentBlock.Text(text = "two"), MessageId("msg_1")),
            ),
            update.toV1(),
        )
    }

    @Test
    fun `a message upsert without usable content cannot become v1 chunks`() {
        val cases = listOf<MaybeUndefined<List<ContentBlock>>>(
            MaybeUndefined.Undefined,
            MaybeUndefined.Null,
            MaybeUndefined.Value(emptyList()),
        )

        cases.forEach { content ->
            val update = SessionUpdate.UserMessage(
                UserMessage(messageId = MessageId("msg_1"), content = content),
            )
            assertFailsWith<ProtocolConversionException>("content=$content") { update.toV1() }
        }
    }

    @Test
    fun `a message upsert with cleared meta cannot become v1 chunks`() {
        val update = SessionUpdate.AgentThought(
            AgentThought(
                messageId = MessageId("msg_1"),
                content = MaybeUndefined.Value(listOf(ContentBlock.Text(text = "hm"))),
                _meta = MaybeUndefined.Null,
            ),
        )

        assertFailsWith<ProtocolConversionException> { update.toV1() }
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

    // Tool calls: v1's two variants collapse into one v2 upsert

    @Test
    fun `a v1 tool call becomes a v2 upsert leaving defaults unset`() {
        val v1 = V1SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc_1"),
            title = "Read file",
            kind = V1ToolKind.OTHER,
            status = V1ToolCallStatus.PENDING,
        )

        assertEquals(
            SessionUpdate.ToolCallUpdate(
                ToolCallUpdate(toolCallId = ToolCallId("tc_1"), title = MaybeUndefined.Value("Read file")),
            ),
            (v1 as V1SessionUpdate).toV2(),
        )
    }

    @Test
    fun `a v1 tool call carries over non-default kind and status`() {
        val v1 = V1SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc_1"),
            title = "Read file",
            kind = V1ToolKind.READ,
            status = V1ToolCallStatus.IN_PROGRESS,
        )

        val v2 = assertIs<SessionUpdate.ToolCallUpdate>((v1 as V1SessionUpdate).toV2()).update
        assertEquals(MaybeUndefined.Value(ToolKind.Read), v2.kind)
        assertEquals(MaybeUndefined.Value(ToolCallStatus.InProgress), v2.status)
    }

    @Test
    fun `a v2 tool call upsert collapses patch states for v1`() {
        val update = SessionUpdate.ToolCallUpdate(
            ToolCallUpdate(
                toolCallId = ToolCallId("tc_1"),
                title = MaybeUndefined.Value("Read file"),
                status = MaybeUndefined.Null,
                content = MaybeUndefined.Null,
            ),
        )

        val v1 = assertIs<V1SessionUpdate.ToolCallUpdate>(update.toV1().single())
        assertEquals("Read file", v1.title)
        // A cleared scalar becomes unset; a cleared collection becomes empty, which is how
        // v1 says "no content".
        assertNull(v1.status)
        assertEquals(emptyList(), v1.content)
        assertNull(v1.locations)
    }

    @Test
    fun `an unknown tool kind is dropped rather than failing the update`() {
        val update = SessionUpdate.ToolCallUpdate(
            ToolCallUpdate(
                toolCallId = ToolCallId("tc_1"),
                kind = MaybeUndefined.Value(ToolKind.Unknown("_vendor_kind")),
                title = MaybeUndefined.Value("Read file"),
            ),
        )

        val v1 = assertIs<V1SessionUpdate.ToolCallUpdate>(update.toV1().single())
        assertNull(v1.kind)
        assertEquals("Read file", v1.title)
    }

    @Test
    fun `a cleared tool call meta cannot be represented in v1`() {
        val update = SessionUpdate.ToolCallUpdate(
            ToolCallUpdate(toolCallId = ToolCallId("tc_1"), _meta = MaybeUndefined.Null),
        )

        assertFailsWith<ProtocolConversionException> { update.toV1() }
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
    fun `session info updates collapse patch states going down and stay unset going up`() {
        val v2 = SessionUpdate.SessionInfoUpdate(
            SessionInfoUpdate(title = MaybeUndefined.Value("T"), updatedAt = MaybeUndefined.Null),
        )

        val v1 = assertIs<V1SessionUpdate.SessionInfoUpdate>(v2.toV1().single())
        assertEquals("T", v1.title)
        assertNull(v1.updatedAt)

        val backToV2 = assertIs<SessionUpdate.SessionInfoUpdate>((v1 as V1SessionUpdate).toV2()).update
        assertEquals(MaybeUndefined.Value("T"), backToV2.title)
        // An absent v1 field is "no update", never an explicit clear.
        assertEquals(MaybeUndefined.Undefined, backToV2.updatedAt)
    }

    @Test
    fun `a cleared session info meta cannot be represented in v1`() {
        val v2 = SessionUpdate.SessionInfoUpdate(SessionInfoUpdate(_meta = MaybeUndefined.Null))

        assertFailsWith<ProtocolConversionException> { v2.toV1() }
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
