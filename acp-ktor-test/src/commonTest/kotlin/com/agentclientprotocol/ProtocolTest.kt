package com.agentclientprotocol

import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

@Serializable
data class TestRequest(val message: String, override val _meta: JsonElement? = null) : AcpRequest
@Serializable
data class TestResponse(val message: String, override val _meta: JsonElement? = null) : AcpResponse

abstract class ProtocolTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    val cancellationMessage = "Cancelled from test"

    companion object {
        object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>("test/testRequest", TestRequest.serializer(), TestResponse.serializer())
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
    fun `request cancelled from client by cancelPendingOutgoingRequests should be cancelled on agent`(): TestResult {
        return testWithProtocols { clientProtocol, agentProtocol ->
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
                clientProtocol.cancelPendingOutgoingRequests(kotlinx.coroutines.CancellationException(cancellationMessage))
            }

            try {
                val response = withTimeout(2000) { clientProtocol.sendRequest(TestMethod, TestRequest("Test")) }
            }
            catch (te: TimeoutCancellationException) {
                fail("Request should be cancelled explicitly and not timed out")
            }
            catch (ce: CancellationException) {
                // expected
                assertEquals(cancellationMessage, ce.message, "Cancellation exception should be propagated to client")
            }
            catch (e: Exception) {
                fail("Unexpected exception: ${e.message}", e)
            }
            val agentCe = withTimeoutOrNull(1000) { agentCeDeferred.await() }
            assertNotNull(agentCe, "Cancellation exception should be propagated to agent")
            assertEquals(cancellationMessage, agentCe.message, "Cancellation exception should be propagated to agent")
        }
    }

    @Test
    fun `request cancelled from client by coroutine cancel should be cancelled on agent`() = testWithProtocols { clientProtocol, agentProtocol ->
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

        val requestJob = launch {
            clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
        }

        delay(500)
        requestJob.cancel(kotlinx.coroutines.CancellationException(cancellationMessage))

        val agentCe = withTimeoutOrNull(1000) { agentCeDeferred.await() }
        assertNotNull(agentCe, "Cancellation exception should be propagated to agent")
        assertEquals(cancellationMessage, agentCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `request cancelled from client by coroutine cancel should wait for graceful cancellation`() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentCeDeferred = CompletableDeferred<CancellationException>()
        agentProtocol.setRequestHandler(TestMethod) { request ->
            try {
                awaitCancellation()
            }
            catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    // Wait for graceful cancellation
                    delay(900) // less than protocol graceful cancellation timeout
                    agentCeDeferred.complete(ce)
                }
                throw ce
            }
        }

        val clientRequestCeDeferred = CompletableDeferred<CancellationException>()
        val requestJob = launch {
            try {
                clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
            }
            catch (ce: CancellationException) {
                clientRequestCeDeferred.complete(ce)
                throw ce
            }
        }

        delay(500)
        requestJob.cancel(kotlinx.coroutines.CancellationException(cancellationMessage))

        withTimeout(5000) {
            val cancellationException = measureTimedValue { clientRequestCeDeferred.await() }
            assertEquals(cancellationMessage, cancellationException.value.message, "Cancellation exception should be propagated to client")
            assertTrue(cancellationException.duration > 900.milliseconds, "Graceful cancellation should be performed")

        }
    }

    @Test
    fun `request cancelled from agent by cancelPendingIncomingRequests should be cancelled on client`() = testWithProtocols { clientProtocol, agentProtocol ->
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
            agentProtocol.cancelPendingIncomingRequests(kotlinx.coroutines.CancellationException(cancellationMessage))
        }

        try {
            val response = withTimeout(1000) { clientProtocol.sendRequest(TestMethod, TestRequest("Test")) }
        }
        catch (te: TimeoutCancellationException) {
            fail("Request should be cancelled explicitly and not timed out")
        }
        catch (ce: CancellationException) {
            //expected
            assertEquals(cancellationMessage, ce.message, "Cancellation exception should be propagated to client")
        }
        catch (e: Exception) {
            fail("Unexpected exception: ${e.message}", e)
        }

        val agentCe = withTimeoutOrNull(1000) { agentCeDeferred.await() }
        assertNotNull(agentCe, "Cancellation exception should be propagated to agent")
        assertEquals(cancellationMessage, agentCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `request cancelled from agent by throwing CE should be cancelled on client`() = testWithProtocols { clientProtocol, agentProtocol ->
        val agentCeDeferred = CompletableDeferred<CancellationException>()
        agentProtocol.setRequestHandler(TestMethod) { request ->
            try {
                delay(500)
                throw kotlinx.coroutines.CancellationException(cancellationMessage)
            }
            catch (ce: CancellationException) {
                agentCeDeferred.complete(ce)
                throw ce
            }
        }

        try {
            val response = withTimeout(1000) { clientProtocol.sendRequest(TestMethod, TestRequest("Test")) }
        }
        catch (te: TimeoutCancellationException) {
            fail("Request should be cancelled explicitly and not timed out")
        }
        catch (ce: CancellationException) {
            //expected
            assertEquals(cancellationMessage, ce.message, "Cancellation exception should be propagated to client")
        }
        catch (e: Exception) {
            fail("Unexpected exception: ${e.message}", e)
        }

        val agentCe = withTimeoutOrNull(1000) { agentCeDeferred.await() }
        assertNotNull(agentCe, "Cancellation exception should be propagated to agent")
        assertEquals(cancellationMessage, agentCe.message, "Cancellation exception should be propagated to agent")
    }

    @Test
    fun `error is propagated to client (INTERNAL_ERROR)`() = testWithProtocols { clientProtocol, agentProtocol ->
        val errorMessage = "Test error from handler"
        agentProtocol.setRequestHandler(TestMethod) { request ->
            throw IllegalStateException(errorMessage)
        }

        try {
            clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
            fail("Expected exception to be thrown")
        }
        catch (e: JsonRpcException) {
            assertEquals(errorMessage, e.message, "Error message should be propagated to client")
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR.code, e.code, "Error code should be INTERNAL_ERROR")
        }
    }

    @Test
    fun `error is propagated to client(INVALID_PARAMS)`() = testWithProtocols { clientProtocol, agentProtocol ->
        val errorMessage = "Invalid parameters provided"
        agentProtocol.setRequestHandler(TestMethod) { request ->
            acpFail(errorMessage)
        }

        try {
            clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
            fail("Expected JsonRpcException to be thrown")
        }
        catch (e: AcpExpectedError) {
            assertEquals(errorMessage, e.message, "Error message should be propagated to client")
        }
    }

    @Test
    fun `error is propagated to client(PARSE_ERROR)`() = testWithProtocols { clientProtocol, agentProtocol ->
        agentProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse("should not reach here")
        }

        try {
            // Send invalid JSON that cannot be deserialized to TestRequest
            clientProtocol.sendRequestRaw(TestMethod.methodName, kotlinx.serialization.json.buildJsonObject {
                put("invalidField", "not a valid TestRequest")
            })
            fail("Expected JsonRpcException to be thrown")
        }
        catch (e: SerializationException) {
            // expected
        }
        catch (e: Exception) {
            fail("Unexpected exception: ${e.message}", e)
        }
    }

    @Test
    fun `error is propagated to client(METHOD_NOT_FOUND)`() = testWithProtocols { clientProtocol, agentProtocol ->
        // Don't set any handler, so METHOD_NOT_FOUND is returned
        try {
            clientProtocol.sendRequest(TestMethod, TestRequest("Test"))
            fail("Expected JsonRpcException to be thrown")
        }
        catch (e: JsonRpcException) {
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND.code, e.code, "Error code should be METHOD_NOT_FOUND")
        }
    }
}