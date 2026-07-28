@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.ToolCallStatus as V1ToolCallStatus

class ToolCallStatusTest {

    // Known values

    @Test
    fun `decodes all known values`() {
        assertEquals(ToolCallStatus.Pending, decode("\"pending\""))
        assertEquals(ToolCallStatus.InProgress, decode("\"in_progress\""))
        assertEquals(ToolCallStatus.Completed, decode("\"completed\""))
        assertEquals(ToolCallStatus.Failed, decode("\"failed\""))
    }

    @Test
    fun `encodes all known values`() {
        assertEquals("\"pending\"", encode(ToolCallStatus.Pending))
        assertEquals("\"in_progress\"", encode(ToolCallStatus.InProgress))
        assertEquals("\"completed\"", encode(ToolCallStatus.Completed))
        assertEquals("\"failed\"", encode(ToolCallStatus.Failed))
    }

    // Unknown values (forward compatibility)

    @Test
    fun `decodes unknown ACP-reserved value as Unknown and preserves it`() {
        val status = decode("\"deferred\"")

        assertIs<ToolCallStatus.Unknown>(status)
        assertEquals("deferred", status.value)
    }

    @Test
    fun `decodes underscore-prefixed extension value as Unknown`() {
        val status = decode("\"_vendor_paused\"")

        assertIs<ToolCallStatus.Unknown>(status)
        assertEquals("_vendor_paused", status.value)
    }

    @Test
    fun `unknown value round-trips byte-identically`() {
        val json = "\"_vendor_paused\""

        assertEquals(json, encode(decode(json)))
    }

    @Test
    fun `Unknown carrying a known wire value normalizes to the known variant on round-trip`() {
        // Same semantics as Rust's #[serde(untagged)] fallback: the wire format has no
        // marker distinguishing Unknown("pending") from Pending, so decode prefers known.
        val decoded = decode(encode(ToolCallStatus.Unknown("pending")))

        assertEquals(ToolCallStatus.Pending, decoded)
    }

    @Test
    fun `decodes unknown value inside an enclosing object`() {
        @Serializable
        data class Wrapper(val status: ToolCallStatus)

        val wrapper = ACPJson.decodeFromString(Wrapper.serializer(), """{"status":"deferred"}""")

        assertEquals(ToolCallStatus.Unknown("deferred"), wrapper.status)
    }

    // Extension factory

    @Test
    fun `extension creates Unknown for underscore-prefixed values`() {
        assertEquals(ToolCallStatus.extension("_vendor_paused"), ToolCallStatus.Unknown("_vendor_paused"))
    }

    @Test
    fun `extension rejects values without underscore prefix`() {
        assertFailsWith<IllegalArgumentException> {
            ToolCallStatus.extension("vendor_paused")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts all known values to v1`() {
        assertEquals(V1ToolCallStatus.PENDING, ToolCallStatus.Pending.toV1())
        assertEquals(V1ToolCallStatus.IN_PROGRESS, ToolCallStatus.InProgress.toV1())
        assertEquals(V1ToolCallStatus.COMPLETED, ToolCallStatus.Completed.toV1())
        assertEquals(V1ToolCallStatus.FAILED, ToolCallStatus.Failed.toV1())
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ToolCallStatus.Unknown("deferred").toV1()
        }

        assertEquals("v2 ToolCallStatus variant `deferred` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 values to v2`() {
        assertEquals(ToolCallStatus.Pending, V1ToolCallStatus.PENDING.toV2())
        assertEquals(ToolCallStatus.InProgress, V1ToolCallStatus.IN_PROGRESS.toV2())
        assertEquals(ToolCallStatus.Completed, V1ToolCallStatus.COMPLETED.toV2())
        assertEquals(ToolCallStatus.Failed, V1ToolCallStatus.FAILED.toV2())
    }

    private fun decode(json: String): ToolCallStatus =
        ACPJson.decodeFromString(ToolCallStatus.serializer(), json)

    private fun encode(status: ToolCallStatus): String =
        ACPJson.encodeToString(ToolCallStatus.serializer(), status)
}
