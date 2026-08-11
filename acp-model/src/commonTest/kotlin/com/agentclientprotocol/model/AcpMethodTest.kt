package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.MethodName
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpMethodTest {
    @Test
    fun `cancel request uses ACP wire method name`() {
        assertEquals(MethodName("\$/cancel_request"), AcpMethod.MetaMethods.CancelRequest.methodName)
    }
}