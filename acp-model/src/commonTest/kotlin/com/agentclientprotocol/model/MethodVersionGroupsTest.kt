@file:Suppress("DEPRECATION")

package com.agentclientprotocol.model

import com.agentclientprotocol.annotations.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Methods are grouped by protocol version; the old flat names stay as deprecated aliases.
 *
 * A connection has one group's handlers installed — the one it negotiated — so these groups are what
 * the fork in `Agent` switches between.
 */
@OptIn(UnstableApi::class)
class MethodVersionGroupsTest {

    @Test
    fun `deprecated flat names are the v1 objects not copies of them`() {
        // Same instance, so registering through either path cannot produce two competing handlers.
        assertSame(AcpMethod.AgentMethods.V1.Initialize, AcpMethod.AgentMethods.Initialize)
        assertSame(AcpMethod.AgentMethods.V1.SessionPrompt, AcpMethod.AgentMethods.SessionPrompt)
        assertSame(AcpMethod.ClientMethods.V1.SessionUpdate, AcpMethod.ClientMethods.SessionUpdate)
    }

    @Test
    fun `both groups use the same wire name for initialize`() {
        // Which is why `initialize` has to be routed by the version in its payload.
        assertEquals(
            AcpMethod.AgentMethods.V1.Initialize.methodName,
            AcpMethod.AgentMethods.V2.Initialize.methodName,
        )
    }

    @Test
    fun `each group carries its own payload types`() {
        assertEquals(
            InitializeRequest.serializer().descriptor.serialName,
            AcpMethod.AgentMethods.V1.Initialize.requestSerializer.descriptor.serialName,
        )
        assertEquals(
            com.agentclientprotocol.model.v2.InitializeRequest.serializer().descriptor.serialName,
            AcpMethod.AgentMethods.V2.Initialize.requestSerializer.descriptor.serialName,
        )
    }
}
