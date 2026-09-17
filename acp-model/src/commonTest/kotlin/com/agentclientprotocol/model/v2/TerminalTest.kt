@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalTest {

    // TerminalId

    @Test
    fun `terminal id serializes as a bare string`() {
        assertEquals(""""term_001"""", ACPJson.encodeToString(TerminalId.serializer(), TerminalId("term_001")))
        assertEquals(
            TerminalId("term_001"),
            ACPJson.decodeFromString(TerminalId.serializer(), """"term_001""""),
        )
    }

    // TerminalOutput

    @Test
    fun `output snapshot round-trips with base64 data kept encoded`() {
        val json = """{"data":"aGVsbG8=","_meta":{"scope":"snapshot"}}"""
        val output = TerminalOutput(
            data = "aGVsbG8=",
            _meta = buildJsonObject { put("scope", "snapshot") },
        )

        assertEquals(output, decodeOutput(json))
        assertEquals(json, encodeOutput(output))
    }

    @Test
    fun `output snapshot omits absent meta`() {
        assertEquals("""{"data":"aGVsbG8="}""", encodeOutput(TerminalOutput(data = "aGVsbG8=")))
    }

    @Test
    fun `output snapshot requires data`() {
        assertFailsWith<SerializationException> { decodeOutput("""{"_meta":{}}""") }
    }

    // TerminalExitStatus

    @Test
    fun `exit status round-trips an exit code and a signal`() {
        val json = """{"exitCode":2,"signal":"SIGTERM"}"""
        val status = TerminalExitStatus(exitCode = 2u, signal = "SIGTERM")

        assertEquals(status, decodeExitStatus(json))
        assertEquals(json, encodeExitStatus(status))
    }

    @Test
    fun `exit status marks exit with neither code nor signal known`() {
        assertEquals("{}", encodeExitStatus(TerminalExitStatus()))
        assertEquals(TerminalExitStatus(), decodeExitStatus("{}"))
    }

    @Test
    fun `exit status treats an explicit null field as absent`() {
        assertEquals(
            TerminalExitStatus(exitCode = null, signal = "SIGKILL"),
            decodeExitStatus("""{"exitCode":null,"signal":"SIGKILL"}"""),
        )
    }

    // TerminalOutputChunk

    @Test
    fun `output chunk round-trips with chunk-scoped meta`() {
        val json = """{"terminalId":"term_001","data":"YWJj","_meta":{"scope":"chunk"}}"""
        val chunk = TerminalOutputChunk(
            terminalId = TerminalId("term_001"),
            data = "YWJj",
            _meta = buildJsonObject { put("scope", "chunk") },
        )

        assertEquals(chunk, decodeChunk(json))
        assertEquals(json, encodeChunk(chunk))
    }

    @Test
    fun `output chunk requires a terminal id and data`() {
        assertFailsWith<SerializationException> { decodeChunk("""{"data":"YWJj"}""") }
        assertFailsWith<SerializationException> { decodeChunk("""{"terminalId":"term_001"}""") }
    }

    // TerminalUpdate encoding: Undefined is omitted, Null is an explicit null, Value is encoded

    @Test
    fun `round-trips an upsert with only the terminal id`() {
        val json = """{"terminalId":"term_001"}"""
        val update = TerminalUpdate(terminalId = TerminalId("term_001"))

        assertEquals(update, decodeUpdate(json))
        assertEquals(json, encodeUpdate(update))
    }

    @Test
    fun `encodes only set fields as an upsert`() {
        val update = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Value("cargo test"),
            cwd = MaybeUndefined.Value("/project"),
        )

        assertEquals(
            """{"terminalId":"term_001","command":"cargo test","cwd":"/project"}""",
            encodeUpdate(update),
        )
    }

    @Test
    fun `encodes an explicit clear as a json null`() {
        val update = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Null,
            exitStatus = MaybeUndefined.Null,
            _meta = MaybeUndefined.Null,
        )

        assertEquals(
            """{"terminalId":"term_001","command":null,"exitStatus":null,"_meta":null}""",
            encodeUpdate(update),
        )
    }

    @Test
    fun `decodes absent and null and set fields as distinct states`() {
        val update = decodeUpdate(
            """{"terminalId":"term_001","command":"cargo test","cwd":null}"""
        )

        assertEquals(MaybeUndefined.Value("cargo test"), update.command)
        assertEquals(MaybeUndefined.Null, update.cwd)
        assertEquals(MaybeUndefined.Undefined, update.output)
        assertEquals(MaybeUndefined.Undefined, update.exitStatus)
        assertEquals(MaybeUndefined.Undefined, update._meta)
    }

    @Test
    fun `round-trips a fully populated upsert`() {
        val json = """{"terminalId":"term_001","command":"cargo test","cwd":"/project",""" +
            """"output":{"data":"aGk="},"exitStatus":{"exitCode":0},"_meta":{"k":"v"}}"""
        val update = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Value("cargo test"),
            cwd = MaybeUndefined.Value("/project"),
            output = MaybeUndefined.Value(TerminalOutput(data = "aGk=")),
            exitStatus = MaybeUndefined.Value(TerminalExitStatus(exitCode = 0u)),
            _meta = MaybeUndefined.Value(buildJsonObject { put("k", "v") }),
        )

        assertEquals(update, decodeUpdate(json))
        assertEquals(json, encodeUpdate(update))
    }

    @Test
    fun `upsert requires a terminal id`() {
        assertFailsWith<SerializationException> { decodeUpdate("""{"command":"cargo test"}""") }
    }

    @Test
    fun `malformed optional fields degrade to undefined instead of failing`() {
        val update = decodeUpdate(
            """{"terminalId":"term_001","command":[1,2],"output":"not-an-object","exitStatus":7}"""
        )

        assertEquals(MaybeUndefined.Undefined, update.command)
        assertEquals(MaybeUndefined.Undefined, update.output)
        assertEquals(MaybeUndefined.Undefined, update.exitStatus)
    }

    // TerminalUpdate patch application

    @Test
    fun `applyUpdate leaves omitted fields unchanged`() {
        val stored = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Value("cargo test"),
            cwd = MaybeUndefined.Value("/project"),
        )

        val merged = stored.applyUpdate(TerminalUpdate(terminalId = TerminalId("term_001")))

        assertEquals(MaybeUndefined.Value("cargo test"), merged.command)
        assertEquals(MaybeUndefined.Value("/project"), merged.cwd)
    }

    @Test
    fun `applyUpdate preserves an explicit clear as null`() {
        val stored = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Value("cargo test"),
        )

        val merged = stored.applyUpdate(
            TerminalUpdate(terminalId = TerminalId("term_001"), command = MaybeUndefined.Null)
        )

        assertEquals(MaybeUndefined.Null, merged.command)
    }

    @Test
    fun `applyUpdate replaces an output snapshot instead of merging it`() {
        val stored = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            output = MaybeUndefined.Value(TerminalOutput(data = "b2xk")),
        )

        val merged = stored.applyUpdate(
            TerminalUpdate(
                terminalId = TerminalId("term_001"),
                output = MaybeUndefined.Value(TerminalOutput(data = "bmV3")),
            )
        )

        assertEquals(MaybeUndefined.Value(TerminalOutput(data = "bmV3")), merged.output)
    }

    @Test
    fun `applyUpdate records exit status on a previously running terminal`() {
        val stored = TerminalUpdate(
            terminalId = TerminalId("term_001"),
            command = MaybeUndefined.Value("cargo test"),
        )

        val merged = stored.applyUpdate(
            TerminalUpdate(
                terminalId = TerminalId("term_001"),
                exitStatus = MaybeUndefined.Value(TerminalExitStatus(exitCode = 1u)),
            )
        )

        assertEquals(MaybeUndefined.Value(TerminalExitStatus(exitCode = 1u)), merged.exitStatus)
        assertEquals(MaybeUndefined.Value("cargo test"), merged.command)
    }

    @Test
    fun `applyUpdate rejects a patch for a different terminal`() {
        val stored = TerminalUpdate(terminalId = TerminalId("term_001"))

        assertFailsWith<IllegalArgumentException> {
            stored.applyUpdate(TerminalUpdate(terminalId = TerminalId("term_002")))
        }
    }

    private fun decodeOutput(json: String): TerminalOutput =
        ACPJson.decodeFromString(TerminalOutput.serializer(), json)

    private fun encodeOutput(output: TerminalOutput): String =
        ACPJson.encodeToString(TerminalOutput.serializer(), output)

    private fun decodeExitStatus(json: String): TerminalExitStatus =
        ACPJson.decodeFromString(TerminalExitStatus.serializer(), json)

    private fun encodeExitStatus(status: TerminalExitStatus): String =
        ACPJson.encodeToString(TerminalExitStatus.serializer(), status)

    private fun decodeChunk(json: String): TerminalOutputChunk =
        ACPJson.decodeFromString(TerminalOutputChunk.serializer(), json)

    private fun encodeChunk(chunk: TerminalOutputChunk): String =
        ACPJson.encodeToString(TerminalOutputChunk.serializer(), chunk)

    private fun decodeUpdate(json: String): TerminalUpdate =
        ACPJson.decodeFromString(TerminalUpdate.serializer(), json)

    private fun encodeUpdate(update: TerminalUpdate): String =
        ACPJson.encodeToString(TerminalUpdate.serializer(), update)
}
