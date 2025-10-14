package com.agentclientprotocol.framework

import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult

interface ProtocolDriver {
    fun testWithProtocols(block: suspend CoroutineScope.(clientProtocol: Protocol, agentProtocol: Protocol) -> Unit): TestResult
}