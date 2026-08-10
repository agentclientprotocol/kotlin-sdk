@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LogoutCapabilities
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.SessionAdditionalDirectoriesCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.fromV1
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV1Parts
import com.agentclientprotocol.model.v2.conversion.toV2
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.agentclientprotocol.model.AgentAuthCapabilities as V1AgentAuthCapabilities
import com.agentclientprotocol.model.AgentCapabilities as V1AgentCapabilities
import com.agentclientprotocol.model.AuthCapabilities as V1AuthCapabilities
import com.agentclientprotocol.model.AuthMethod as V1AuthMethod
import com.agentclientprotocol.model.ClientCapabilities as V1ClientCapabilities
import com.agentclientprotocol.model.InitializeRequest as V1InitializeRequest
import com.agentclientprotocol.model.InitializeResponse as V1InitializeResponse
import com.agentclientprotocol.model.McpCapabilities as V1McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities as V1PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities as V1SessionCapabilities
import com.agentclientprotocol.model.SessionDeleteCapabilities as V1SessionDeleteCapabilities

class CapabilitiesConversionTest {

    // Prompt capabilities: booleans <-> marker objects

    @Test
    fun `v1 prompt capability booleans convert to v2 objects`() {
        val v1 = V1PromptCapabilities(audio = true, image = true, embeddedContext = false)
        val v2 = PromptCapabilities(image = PromptImageCapabilities(), audio = PromptAudioCapabilities())

        assertEquals(v2, v1.toV2())
        assertEquals(v1, v2.toV1())
    }

    @Test
    fun `converting a v2 prompt marker with meta to v1 fails because a boolean cannot hold it`() {
        val v2 = PromptCapabilities(
            image = PromptImageCapabilities(_meta = buildJsonObject { put("k", JsonPrimitive("v")) }),
        )

        val exception = assertFailsWith<ProtocolConversionException> { v2.toV1() }
        assertEquals("v2 PromptCapabilities.image metadata cannot be represented in v1", exception.message)
    }

    // MCP capabilities: stdio is synthesized, sse is gone

    @Test
    fun `v1 mcp capabilities convert to v2 transport objects with stdio synthesized`() {
        assertEquals(
            McpCapabilities(stdio = McpStdioCapabilities(), http = McpHttpCapabilities()),
            V1McpCapabilities(http = true).toV2(),
        )
        assertEquals(
            McpCapabilities(stdio = McpStdioCapabilities()),
            V1McpCapabilities().toV2(),
        )
    }

    @Test
    fun `converting v1 sse support to v2 fails because the transport was removed`() {
        val exception = assertFailsWith<ProtocolConversionException> { V1McpCapabilities(sse = true).toV2() }

        assertEquals("v1 McpCapabilities.sse cannot be represented in v2", exception.message)
    }

    @Test
    fun `opting out of stdio is lost on the way to v1`() {
        assertEquals(
            V1McpCapabilities(http = true, sse = false),
            McpCapabilities(stdio = null, http = McpHttpCapabilities()).toV1(),
        )
    }

    @Test
    fun `converting v2 acp transport support to v1 fails because v1 has no such field`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            McpCapabilities(acp = McpAcpCapabilities()).toV1()
        }

        assertEquals("v2 McpCapabilities.acp cannot be represented in v1", exception.message)
    }

    // Session capabilities: the un-nesting

    @Test
    fun `v2 session capabilities convert to v1 agent capability parts`() {
        val parts = SessionCapabilities(
            prompt = PromptCapabilities(image = PromptImageCapabilities()),
            mcp = McpCapabilities(stdio = McpStdioCapabilities(), http = McpHttpCapabilities()),
            additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
        ).toV1Parts()

        assertEquals(true, parts.loadSession)
        assertEquals(V1PromptCapabilities(image = true), parts.promptCapabilities)
        assertEquals(V1McpCapabilities(http = true), parts.mcpCapabilities)
        assertEquals(
            V1SessionCapabilities(
                list = SessionListCapabilities(),
                resume = SessionResumeCapabilities(),
                close = SessionCloseCapabilities(),
                additionalDirectories = SessionAdditionalDirectoriesCapabilities(),
            ),
            parts.sessionCapabilities,
        )
    }

    @Test
    fun `session delete capability round trips between v1 and v2 with meta`() {
        val meta = buildJsonObject { put("k", JsonPrimitive("v")) }
        val v2 = baselineV2AgentCapabilities().session!!.copy(
            delete = SessionDeleteCapabilities(_meta = meta),
        )

        val parts = v2.toV1Parts()
        val roundTripped = SessionCapabilities.fromV1(
            sessionCapabilities = parts.sessionCapabilities,
            promptCapabilities = parts.promptCapabilities,
            loadSession = parts.loadSession,
            mcpCapabilities = parts.mcpCapabilities,
        )

        assertEquals(V1SessionDeleteCapabilities(_meta = meta), parts.sessionCapabilities.delete)
        assertEquals(v2, roundTripped)
    }

    @Test
    fun `session delete capability remains absent across v1 and v2 conversions`() {
        assertNull(baselineV2AgentCapabilities().toV1().sessionCapabilities.delete)
        assertNull(baselineV1AgentCapabilities().toV2().session!!.delete)
    }

    @Test
    fun `v1 capabilities below the v2 session baseline have no v2 form`() {
        val baseline = baselineV1SessionCapabilities()

        assertEquals(
            "v1 AgentCapabilities.loadSession cannot be represented in v2",
            fromV1Failure(baseline, loadSession = false).message,
        )
        assertEquals(
            "v1 SessionCapabilities.list cannot be represented in v2",
            fromV1Failure(baseline.copy(list = null)).message,
        )
        assertEquals(
            "v1 SessionCapabilities.resume cannot be represented in v2",
            fromV1Failure(baseline.copy(resume = null)).message,
        )
        assertEquals(
            "v1 SessionCapabilities.close cannot be represented in v2",
            fromV1Failure(baseline.copy(close = null)).message,
        )
    }

    @Test
    fun `meta on a v1 marker that v2 dropped has nowhere to go`() {
        val meta = buildJsonObject { put("k", JsonPrimitive("v")) }
        val baseline = baselineV1SessionCapabilities()

        assertEquals(
            "v1 SessionCapabilities.list metadata cannot be represented in v2",
            fromV1Failure(baseline.copy(list = SessionListCapabilities(_meta = meta))).message,
        )
    }

    // Agent capabilities

    @Test
    fun `the baseline agent capabilities round trip`() {
        val v1 = baselineV1AgentCapabilities()
        val v2 = baselineV2AgentCapabilities()

        assertEquals(v2, v1.toV2())
        assertEquals(v1, v2.toV1())
        assertEquals(v1, v1.toV2().toV1())
    }

    @Test
    fun `v2 agent capabilities without session do not convert to v1`() {
        val exception = assertFailsWith<ProtocolConversionException> { AgentCapabilities().toV1() }

        assertEquals("v2 AgentCapabilities without `session` cannot be represented in v1", exception.message)
    }

    @Test
    fun `an all default v1 auth object collapses to no v2 auth capabilities`() {
        assertNull(baselineV1AgentCapabilities().toV2().auth)
    }

    @Test
    fun `converting a v1 logout marker on its own to v2 fails`() {
        val v1 = baselineV1AgentCapabilities()
            .copy(auth = V1AgentAuthCapabilities(logout = LogoutCapabilities()))

        val exception = assertFailsWith<ProtocolConversionException> { v1.toV2() }
        assertEquals("v1 AgentAuthCapabilities.logout cannot be represented in v2", exception.message)
    }

    // Client capabilities

    @Test
    fun `the v2 client surface drops everything but elicitation`() {
        val v2 = ClientCapabilities(elicitation = ElicitationCapabilities())

        assertEquals(
            V1ClientCapabilities(fs = null, terminal = false, elicitation = ElicitationCapabilities()),
            v2.toV1(),
        )
        assertEquals(v2, v2.toV1().toV2())
    }

    @Test
    fun `v1 client fs and terminal capabilities do not convert to v2`() {
        assertEquals(
            "v1 ClientCapabilities.fs cannot be represented in v2",
            assertFailsWith<ProtocolConversionException> {
                V1ClientCapabilities(fs = FileSystemCapability(readTextFile = true)).toV2()
            }.message,
        )
        assertEquals(
            "v1 ClientCapabilities.terminal cannot be represented in v2",
            assertFailsWith<ProtocolConversionException> {
                V1ClientCapabilities(terminal = true).toV2()
            }.message,
        )
        assertEquals(
            "v1 ClientCapabilities.plan cannot be represented in v2",
            assertFailsWith<ProtocolConversionException> {
                V1ClientCapabilities(planCapabilities = PlanCapabilities()).toV2()
            }.message,
        )
    }

    @Test
    fun `an fs object that advertises nothing is treated as absent`() {
        assertEquals(ClientCapabilities(), V1ClientCapabilities(fs = FileSystemCapability()).toV2())
    }

    @Test
    fun `converting v2 terminal auth support to v1 fails because v1 has no such field`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            AuthCapabilities(terminal = TerminalAuthCapabilities()).toV1()
        }

        assertEquals("v2 AuthCapabilities.terminal cannot be represented in v1", exception.message)
    }

    @Test
    fun `client auth capabilities round trip`() {
        assertEquals(AuthCapabilities(), V1AuthCapabilities().toV2())
        assertEquals(V1AuthCapabilities(), AuthCapabilities().toV1())
    }

    // Initialization

    @Test
    fun `initialize request renames capabilities and requires info`() {
        val info = Implementation(name = "zed", version = "1.0.0")
        val v2 = InitializeRequest(protocolVersion = 1, info = info)
        val v1 = V1InitializeRequest(protocolVersion = 1, clientCapabilities = V1ClientCapabilities(), clientInfo = info)

        assertEquals(v1, v2.toV1())
        assertEquals(v2, v1.toV2())
    }

    @Test
    fun `a v1 initialize request without client info has no v2 form`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            V1InitializeRequest(protocolVersion = 1).toV2()
        }

        assertEquals("v1 InitializeRequest without `clientInfo` cannot be represented in v2", exception.message)
    }

    @Test
    fun `a v1 initialize response without agent info has no v2 form`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            V1InitializeResponse(protocolVersion = 1, agentCapabilities = baselineV1AgentCapabilities()).toV2()
        }

        assertEquals("v1 InitializeResponse without `agentInfo` cannot be represented in v2", exception.message)
    }

    @Test
    fun `non empty auth methods materialize the v1 logout marker`() {
        val info = Implementation(name = "acme-agent", version = "2.0.0")
        val v2 = InitializeResponse(
            protocolVersion = 1,
            info = info,
            capabilities = baselineV2AgentCapabilities(),
            authMethods = listOf(AuthMethod.Agent(methodId = AuthMethodId("oauth"), name = "OAuth")),
        )

        val v1 = v2.toV1()

        assertEquals(LogoutCapabilities(), v1.agentCapabilities.auth.logout)
        assertEquals(v2, v1.toV2())
    }

    @Test
    fun `empty auth methods leave the v1 logout marker unset`() {
        val v2 = InitializeResponse(
            protocolVersion = 1,
            info = Implementation(name = "acme-agent", version = "2.0.0"),
            capabilities = AgentCapabilities(session = SessionCapabilities()),
        )

        assertNull(v2.toV1().agentCapabilities.auth.logout)
    }

    @Test
    fun `a v1 initialize response whose logout marker disagrees with auth methods has no v2 form`() {
        val info = Implementation(name = "acme-agent", version = "2.0.0")

        assertEquals(
            "v1 InitializeResponse with non-empty `authMethods` and no " +
                "`agentCapabilities.auth.logout` cannot be represented in v2",
            assertFailsWith<ProtocolConversionException> {
                V1InitializeResponse(
                    protocolVersion = 1,
                    agentCapabilities = baselineV1AgentCapabilities(),
                    authMethods = listOf(V1AuthMethod.AgentAuth(id = AuthMethodId("oauth"), name = "OAuth")),
                    agentInfo = info,
                ).toV2()
            }.message,
        )
        assertEquals(
            "v1 InitializeResponse with `agentCapabilities.auth.logout` and empty " +
                "`authMethods` cannot be represented in v2",
            assertFailsWith<ProtocolConversionException> {
                V1InitializeResponse(
                    protocolVersion = 1,
                    agentCapabilities = baselineV1AgentCapabilities()
                        .copy(auth = V1AgentAuthCapabilities(logout = LogoutCapabilities())),
                    agentInfo = info,
                ).toV2()
            }.message,
        )
    }

    private fun baselineV1SessionCapabilities() = V1SessionCapabilities(
        list = SessionListCapabilities(),
        resume = SessionResumeCapabilities(),
        close = SessionCloseCapabilities(),
    )

    private fun baselineV1AgentCapabilities() = V1AgentCapabilities(
        loadSession = true,
        sessionCapabilities = baselineV1SessionCapabilities(),
    )

    /**
     * The v2 shape a v1 baseline agent maps onto: the per-method session markers are gone,
     * but the prompt and MCP groups always materialize because v1 always has values for them.
     */
    private fun baselineV2AgentCapabilities() = AgentCapabilities(
        session = SessionCapabilities(
            prompt = PromptCapabilities(),
            mcp = McpCapabilities(stdio = McpStdioCapabilities()),
        ),
    )

    private fun fromV1Failure(
        sessionCapabilities: V1SessionCapabilities,
        loadSession: Boolean = true,
    ): ProtocolConversionException = assertFailsWith {
        SessionCapabilities.fromV1(
            sessionCapabilities = sessionCapabilities,
            promptCapabilities = V1PromptCapabilities(),
            loadSession = loadSession,
            mcpCapabilities = V1McpCapabilities(),
        )
    }
}
