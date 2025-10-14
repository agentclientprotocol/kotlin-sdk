package com.agentclientprotocol

import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setRequestHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

@Serializable
data class TestRequest(val message: String, override val _meta: JsonElement? = null) : AcpRequest
@Serializable
data class TestResponse(val message: String, override val _meta: JsonElement? = null) : AcpResponse

abstract class ProtocolTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {


    companion object {
        object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>("test/testRequest")
    }

    @Test
    fun `simple request returns result`() = testWithProtocols { clientProtocol, agentProtocol ->
        agentProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse(request.message)
        }

        val response = clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
        assertEquals("Test", response.message)
    }


    @Test
    fun `request cancelled from client should be cancelled on agent`() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentCeDeferred = CompletableDeferred<CancellationException>()
        agentProtocol.setRequestHandler(TestMethod) { request ->
            try {
                awaitCancellation()
            }
            catch (ce: CancellationException) {
                agentCeDeferred.complete(ce)
                throw ce
            }
        }

        launch {
            delay(500)
            clientProtocol.cancelPendingOutgoingRequests(kotlinx.coroutines.CancellationException("Cancelled from test"))
        }

        try {
            val response = withTimeout(2000) { clientProtocol.sendRequest(TestMethod, TestRequest("Test")) }
        }
        catch (te: TimeoutCancellationException) {
            fail("Request should be cancelled explicitly and not timed out")
        }
        catch (ce: CancellationException) {
            // expected
        }
        catch (e: Exception) {
            fail("Unexpected exception: ${e.message}", e)
        }
        val agentCe = withTimeoutOrNull(1000) { agentCeDeferred.await() }
        assertNotNull(agentCe, "Cancellation exception should be propagated to agent")
    }
}