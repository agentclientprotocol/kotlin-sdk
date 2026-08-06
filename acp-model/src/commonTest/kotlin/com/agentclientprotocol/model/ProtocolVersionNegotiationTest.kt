package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The rule under test:
 * [version negotiation](https://agentclientprotocol.com/protocol/v2/initialization#version-negotiation)
 * — a supported request is echoed back, anything else is answered with the latest supported version.
 */
@OptIn(UnstableApi::class)
class ProtocolVersionNegotiationTest {

    private val v1Only = setOf(LATEST_PROTOCOL_VERSION)
    private val v1AndV2 = setOf(LATEST_PROTOCOL_VERSION, PROTOCOL_VERSION_V2)
    private val v2Only = setOf(PROTOCOL_VERSION_V2)
    private val unsupportedHigh = 5

    // === supported request is echoed ===

    @Test
    fun `echoes a supported request`() {
        assertEquals(1, negotiateProtocolVersion(requested = 1, supported = v1Only))
        assertEquals(1, negotiateProtocolVersion(requested = 1, supported = v1AndV2))
        assertEquals(2, negotiateProtocolVersion(requested = 2, supported = v1AndV2))
        assertEquals(2, negotiateProtocolVersion(requested = 2, supported = v2Only))
    }

    // === unsupported request falls back to the latest supported ===

    @Test
    fun `answers the latest supported version when the request is too high`() {
        assertEquals(1, negotiateProtocolVersion(requested = unsupportedHigh, supported = v1Only))
        assertEquals(2, negotiateProtocolVersion(requested = unsupportedHigh, supported = v1AndV2))
        assertEquals(2, negotiateProtocolVersion(requested = unsupportedHigh, supported = v2Only))
    }

    @Test
    fun `answers the latest supported version when the request is too low`() {
        // The interesting case: the answer is *higher* than the request, which `min(...)` and any
        // "supported versions up to my latest" derivation both get wrong.
        assertEquals(2, negotiateProtocolVersion(requested = 1, supported = v2Only))
    }

    @Test
    fun `answers the latest supported version when the request is unknown but within range`() {
        assertEquals(3, negotiateProtocolVersion(requested = 2, supported = setOf(1, 3)))
    }

    // === degenerate input ===

    @Test
    fun `fails when nothing is supported`() {
        val failure = assertFailsWith<IllegalStateException> {
            negotiateProtocolVersion(requested = 1, supported = emptySet())
        }
        assertTrue(failure.message!!.contains("no supported versions"), "unexpected message: ${failure.message}")
    }

    // === the SDK's own vocabulary ===

    @Test
    fun `the draft version is not stable-supported but is negotiable`() {
        assertTrue(PROTOCOL_VERSION_V2 !in SUPPORTED_PROTOCOL_VERSIONS)
        assertTrue(PROTOCOL_VERSION_V2 in KNOWN_PROTOCOL_VERSIONS)
        assertTrue(SUPPORTED_PROTOCOL_VERSIONS.all { it in KNOWN_PROTOCOL_VERSIONS })
        assertEquals(LATEST_PROTOCOL_VERSION, SUPPORTED_PROTOCOL_VERSIONS.max())
    }
}
