@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SessionUpdateTest {

    // Every known variant flattens its payload next to the discriminator

    @Test
    fun `round-trips the three content chunk variants`() {
        val payload = """"messageId":"msg_1","content":{"type":"text","text":"hi"}"""
        val chunk = ContentChunk(messageId = MessageId("msg_1"), content = ContentBlock.Text(text = "hi"))

        val cases = listOf(
            "user_message_chunk" to SessionUpdate.UserMessageChunk(chunk),
            "agent_message_chunk" to SessionUpdate.AgentMessageChunk(chunk),
            "agent_thought_chunk" to SessionUpdate.AgentThoughtChunk(chunk),
        )

        cases.forEach { (discriminator, update) ->
            val json = """{"sessionUpdate":"$discriminator",$payload}"""
            assertEquals(update, decode(json), "decoding $discriminator")
            assertEquals(json, encode(update), "encoding $discriminator")
        }
    }

    @Test
    fun `round-trips the three message upsert variants`() {
        val content = MaybeUndefined.Value(listOf<ContentBlock>(ContentBlock.Text(text = "hi")))
        val payload = """"messageId":"msg_1","content":[{"type":"text","text":"hi"}]"""

        val cases = listOf(
            "user_message" to SessionUpdate.UserMessage(
                UserMessage(messageId = MessageId("msg_1"), content = content),
            ),
            "agent_message" to SessionUpdate.AgentMessage(
                AgentMessage(messageId = MessageId("msg_1"), content = content),
            ),
            "agent_thought" to SessionUpdate.AgentThought(
                AgentThought(messageId = MessageId("msg_1"), content = content),
            ),
        )

        cases.forEach { (discriminator, update) ->
            val json = """{"sessionUpdate":"$discriminator",$payload}"""
            assertEquals(update, decode(json), "decoding $discriminator")
            assertEquals(json, encode(update), "encoding $discriminator")
        }
    }

    @Test
    fun `round-trips a state update with its own nested discriminator`() {
        val json = """{"sessionUpdate":"state_update","state":"idle","stopReason":"end_turn"}"""
        val update = SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn))

        assertEquals(update, decode(json))
        assertEquals(json, encode(update))
    }

    @Test
    fun `round-trips a tool call upsert`() {
        val json = """{"sessionUpdate":"tool_call_update","toolCallId":"tc_1","status":"completed"}"""
        val update = SessionUpdate.ToolCallUpdate(
            ToolCallUpdate(
                toolCallId = ToolCallId("tc_1"),
                status = MaybeUndefined.Value(ToolCallStatus.Completed),
            ),
        )

        assertEquals(update, decode(json))
        assertEquals(json, encode(update))
    }

    @Test
    fun `round-trips a tool call content chunk`() {
        val json =
            """{"sessionUpdate":"tool_call_content_chunk","toolCallId":"tc_1",""" +
                """"content":{"type":"content","content":{"type":"text","text":"out"}}}"""
        val update = SessionUpdate.ToolCallContentChunk(
            ToolCallContentChunk(
                toolCallId = ToolCallId("tc_1"),
                content = ToolCallContent.Content(content = ContentBlock.Text(text = "out")),
            ),
        )

        assertEquals(update, decode(json))
        assertEquals(json, encode(update))
    }

    @Test
    fun `round-trips plan update and plan removal`() {
        val planUpdate = """{"sessionUpdate":"plan_update","plan":{"type":"items","planId":"main","entries":[]}}"""
        val planRemoved = """{"sessionUpdate":"plan_removed","planId":"main"}"""

        assertEquals(
            SessionUpdate.PlanUpdate(
                PlanUpdate(plan = PlanUpdateContent.Items(planId = PlanId("main"), entries = emptyList())),
            ),
            decode(planUpdate),
        )
        assertEquals(planUpdate, encode(decode(planUpdate)))
        assertEquals(SessionUpdate.PlanRemoved(PlanRemoved(planId = PlanId("main"))), decode(planRemoved))
        assertEquals(planRemoved, encode(decode(planRemoved)))
    }

    @Test
    fun `round-trips available commands config options and usage`() {
        val commands = """{"sessionUpdate":"available_commands_update","availableCommands":[]}"""
        val configOptions = """{"sessionUpdate":"config_option_update","configOptions":[]}"""
        val usage = """{"sessionUpdate":"usage_update","used":10,"size":100}"""

        assertEquals(
            SessionUpdate.AvailableCommandsUpdate(AvailableCommandsUpdate(availableCommands = emptyList())),
            decode(commands),
        )
        assertEquals(commands, encode(decode(commands)))
        assertEquals(
            SessionUpdate.ConfigOptionUpdate(ConfigOptionUpdate(configOptions = emptyList())),
            decode(configOptions),
        )
        assertEquals(configOptions, encode(decode(configOptions)))
        assertEquals(SessionUpdate.UsageUpdate(UsageUpdate(used = 10, size = 100)), decode(usage))
        assertEquals(usage, encode(decode(usage)))
    }

    @Test
    fun `round-trips a session info update with patch semantics`() {
        val json = """{"sessionUpdate":"session_info_update","title":null}"""
        val update = SessionUpdate.SessionInfoUpdate(SessionInfoUpdate(title = MaybeUndefined.Null))

        assertEquals(update, decode(json))
        assertEquals(json, encode(update))
    }

    @Test
    fun `an empty session info update encodes as the discriminator alone`() {
        assertEquals(
            """{"sessionUpdate":"session_info_update"}""",
            encode(SessionUpdate.SessionInfoUpdate(SessionInfoUpdate())),
        )
    }

    // Open union behaviour

    @Test
    fun `preserves an unknown session update byte-identically`() {
        val json = """{"sessionUpdate":"_vendor_telemetry","spans":[{"id":1}]}"""

        val decoded = decode(json)
        assertIs<SessionUpdate.Unknown>(decoded)
        assertEquals("_vendor_telemetry", decoded.sessionUpdate)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `preserves a future ACP session update byte-identically`() {
        val json = """{"sessionUpdate":"checkpoint_created","checkpointId":"c1"}"""

        val decoded = decode(json)
        assertIs<SessionUpdate.Unknown>(decoded)
        assertEquals("checkpoint_created", decoded.sessionUpdate)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `an unknown nested state still re-emits with both discriminators`() {
        val json = """{"sessionUpdate":"state_update","state":"_vendor_paused","until":"soon"}"""

        val decoded = decode(json)
        val stateUpdate = assertIs<SessionUpdate.StateUpdate>(decoded).state
        assertIs<StateUpdate.Unknown>(stateUpdate)
        assertEquals("_vendor_paused", stateUpdate.state)
        assertEquals(json, encode(decoded))
    }

    @Test
    fun `missing sessionUpdate discriminator fails`() {
        assertFailsWith<SerializationException> { decode("""{"toolCallId":"tc_1"}""") }
    }

    @Test
    fun `non-string sessionUpdate discriminator fails`() {
        assertFailsWith<SerializationException> { decode("""{"sessionUpdate":42}""") }
    }

    @Test
    fun `known discriminator with a malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"sessionUpdate":"tool_call_update","status":"completed"}""")
        }
        assertFailsWith<SerializationException> {
            decode("""{"sessionUpdate":"usage_update","used":10}""")
        }
    }

    private fun decode(json: String): SessionUpdate =
        ACPJson.decodeFromString(SessionUpdate.serializer(), json)

    private fun encode(update: SessionUpdate): String =
        ACPJson.encodeToString(SessionUpdate.serializer(), update)
}
