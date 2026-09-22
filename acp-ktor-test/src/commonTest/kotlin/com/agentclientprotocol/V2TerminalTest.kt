package com.agentclientprotocol

import com.agentclientprotocol.agent.v2.Agent as V2Agent
import com.agentclientprotocol.agent.v2.AgentInfo as V2AgentInfo
import com.agentclientprotocol.agent.v2.AgentSession as V2AgentSession
import com.agentclientprotocol.agent.v2.AgentSupport as V2AgentSupport
import com.agentclientprotocol.agent.v2.ClientOperations as V2ClientOperations
import com.agentclientprotocol.agent.v2.SessionCreationParameters as V2SessionCreationParameters
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.v2.Client as V2Client
import com.agentclientprotocol.client.v2.ClientInfo as V2ClientInfo
import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.MaybeUndefined
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import com.agentclientprotocol.model.v2.TerminalExitStatus
import com.agentclientprotocol.model.v2.TerminalId
import com.agentclientprotocol.model.v2.TerminalOutput
import com.agentclientprotocol.model.v2.TerminalOutputChunk
import com.agentclientprotocol.model.v2.TerminalUpdate
import com.agentclientprotocol.model.v2.ToolCallContent
import com.agentclientprotocol.model.v2.ToolCallUpdate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The v2 agent-owned terminal display surface end to end, over a real transport.
 *
 * The surface is display-only: the agent reports terminal state and bytes through session
 * updates, and the client accumulates them. Nothing here asks the client to execute
 * anything — v2 removed the client-side `terminal` method family entirely.
 */
@OptIn(UnstableApi::class, ExperimentalEncodingApi::class)
abstract class V2TerminalTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {

    private companion object {
        val TERMINAL = TerminalId("term_001")

        /**
         * "héllo" split so that the two-byte `é` straddles the chunk boundary, which is
         * exactly the case a client must not decode per-chunk as text.
         */
        val SPLIT_UTF8: Pair<ByteArray, ByteArray> =
            byteArrayOf(0x68, 0xC3.toByte()) to byteArrayOf(0xA9.toByte(), 0x6C, 0x6C, 0x6F)
    }

    /**
     * Reports a tool call that references a terminal, then streams that terminal's state.
     */
    private class TerminalV2Session(override val sessionId: SessionId) : V2AgentSession {
        var emitSnapshotMidStream: Boolean = false

        override fun prompt(content: List<ContentBlock>, _meta: JsonElement?) = flow {
            emit(SessionUpdate.StateUpdate(StateUpdate.Running()))

            emit(
                SessionUpdate.ToolCallUpdate(
                    ToolCallUpdate(
                        toolCallId = ToolCallId("tc_1"),
                        title = MaybeUndefined.Value("Run tests"),
                        content = MaybeUndefined.Value(
                            listOf(ToolCallContent.Terminal(terminalId = TERMINAL)),
                        ),
                    ),
                )
            )
            emit(
                SessionUpdate.TerminalUpdate(
                    TerminalUpdate(
                        terminalId = TERMINAL,
                        command = MaybeUndefined.Value("cargo test"),
                        cwd = MaybeUndefined.Value("/project"),
                    ),
                )
            )

            emit(SessionUpdate.TerminalOutputChunk(chunk(SPLIT_UTF8.first)))
            if (emitSnapshotMidStream) {
                emit(
                    SessionUpdate.TerminalUpdate(
                        TerminalUpdate(
                            terminalId = TERMINAL,
                            output = MaybeUndefined.Value(
                                TerminalOutput(data = Base64.encode("replaced".encodeToByteArray())),
                            ),
                        ),
                    )
                )
            }
            emit(SessionUpdate.TerminalOutputChunk(chunk(SPLIT_UTF8.second)))

            emit(
                SessionUpdate.TerminalUpdate(
                    TerminalUpdate(
                        terminalId = TERMINAL,
                        exitStatus = MaybeUndefined.Value(TerminalExitStatus(exitCode = 0u)),
                    ),
                )
            )
            emit(SessionUpdate.StateUpdate(StateUpdate.Idle(stopReason = StopReason.EndTurn)))
        }

        private fun chunk(bytes: ByteArray) =
            TerminalOutputChunk(terminalId = TERMINAL, data = Base64.encode(bytes))

        override suspend fun cancel() = Unit
    }

    private class TerminalSupport(private val emitSnapshotMidStream: Boolean = false) : V2AgentSupport {
        val sessions = mutableListOf<TerminalV2Session>()

        override suspend fun initialize(clientInfo: V2ClientInfo): V2AgentInfo = V2AgentInfo(
            implementation = Implementation(name = "test-agent", version = "1.0.0"),
        )

        override suspend fun createSession(
            parameters: V2SessionCreationParameters,
            client: V2ClientOperations,
        ): V2AgentSession = TerminalV2Session(SessionId("v2-${sessions.size + 1}"))
            .also { it.emitSnapshotMidStream = emitSnapshotMidStream; sessions += it }
    }

    private fun v2ClientInfo() = V2ClientInfo(
        protocolVersion = PROTOCOL_VERSION_V2,
        implementation = Implementation(name = "test-client", version = "1.0.0"),
    )

    @Test
    fun `a tool call references a terminal whose state arrives as separate updates`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            V2Agent(agentProtocol, TerminalSupport())
            val client = V2Client(clientProtocol)
            client.initialize(v2ClientInfo())
            val session = client.newSession(cwd = ".")

            session.prompt(listOf(ContentBlock.Text("run the tests")))
            val updates = withTimeout(10.seconds) { session.updates.take(7).toList() }.map { it.update }

            val toolCall = assertIs<SessionUpdate.ToolCallUpdate>(updates[1])
            val content = assertIs<MaybeUndefined.Value<List<ToolCallContent>>>(toolCall.update.content)
            val reference = assertIs<ToolCallContent.Terminal>(content.value.single())
            assertEquals(TERMINAL, reference.terminalId)

            // The reference carries no state; command and cwd arrive on the terminal update.
            val created = assertIs<SessionUpdate.TerminalUpdate>(updates[2]).update
            assertEquals(TERMINAL, created.terminalId)
            assertEquals(MaybeUndefined.Value("cargo test"), created.command)
            assertEquals(MaybeUndefined.Value("/project"), created.cwd)

            val exited = assertIs<SessionUpdate.TerminalUpdate>(updates[5]).update
            assertEquals(MaybeUndefined.Value(TerminalExitStatus(exitCode = 0u)), exited.exitStatus)

            val idle = assertIs<StateUpdate.Idle>(assertIs<SessionUpdate.StateUpdate>(updates[6]).state)
            assertEquals(StopReason.EndTurn, idle.stopReason)
        }

    @Test
    fun `chunks are decoded independently and appended rather than concatenated before decoding`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            V2Agent(agentProtocol, TerminalSupport())
            val client = V2Client(clientProtocol)
            client.initialize(v2ClientInfo())
            val session = client.newSession(cwd = ".")

            session.prompt(listOf(ContentBlock.Text("run the tests")))
            val updates = withTimeout(10.seconds) { session.updates.take(7).toList() }.map { it.update }

            val chunks = updates.filterIsInstance<SessionUpdate.TerminalOutputChunk>().map { it.chunk }
            assertEquals(2, chunks.size)
            chunks.forEach { assertEquals(TERMINAL, it.terminalId) }

            /*
             * Each chunk's data is its own base64 value, so the bytes must be decoded per
             * chunk and only then appended. Concatenating the encoded strings first would
             * not round-trip these two payloads.
             */
            val accumulated = chunks.fold(byteArrayOf()) { bytes, chunk -> bytes + Base64.decode(chunk.data) }
            assertContentEquals(SPLIT_UTF8.first + SPLIT_UTF8.second, accumulated)
            assertEquals("héllo", accumulated.decodeToString())
        }

    @Test
    fun `an output snapshot replaces accumulated bytes and later chunks append to it`() =
        testWithProtocols { clientProtocol, agentProtocol ->
            V2Agent(agentProtocol, TerminalSupport(emitSnapshotMidStream = true))
            val client = V2Client(clientProtocol)
            client.initialize(v2ClientInfo())
            val session = client.newSession(cwd = ".")

            session.prompt(listOf(ContentBlock.Text("run the tests")))
            val updates = withTimeout(10.seconds) { session.updates.take(8).toList() }.map { it.update }

            // Replay the stream the way a client stores it: snapshots replace, chunks append.
            var bytes = byteArrayOf()
            updates.forEach { update ->
                when (update) {
                    is SessionUpdate.TerminalOutputChunk -> bytes += Base64.decode(update.chunk.data)
                    is SessionUpdate.TerminalUpdate ->
                        when (val output = update.update.output) {
                            is MaybeUndefined.Value -> bytes = Base64.decode(output.value.data)
                            MaybeUndefined.Null -> bytes = byteArrayOf()
                            MaybeUndefined.Undefined -> {}
                        }

                    else -> {}
                }
            }

            // The first chunk is discarded by the snapshot; only the trailing chunk survives it.
            assertContentEquals("replaced".encodeToByteArray() + SPLIT_UTF8.second, bytes)
        }
}
