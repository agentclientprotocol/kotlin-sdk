package com.agentclientprotocol.framework

import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.test.TestResult

abstract class ClientAgentTest {
    abstract fun testWithProtocols(block: suspend (clientProtocol: Protocol, agentProtocol: Protocol) -> Unit): TestResult
}