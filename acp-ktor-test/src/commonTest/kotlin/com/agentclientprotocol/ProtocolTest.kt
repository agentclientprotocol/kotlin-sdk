package com.agentclientprotocol

import com.agentclientprotocol.framework.ProtocolDriver
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.jsonRpcRequest
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.protocol.sendRequest
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.protocol.setRequestHandler
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
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
@Serializable
data class TestNotification(val message: String, override val _meta: JsonElement? = null) : AcpNotification

abstract class ProtocolTest(protocolDriver: ProtocolDriver) : ProtocolDriver by protocolDriver {
    val cancellationMessage = "Cancelled from test"

    companion object {
        object TestMethod : AcpMethod.AcpRequestResponseMethod<TestRequest, TestResponse>("test/testRequest", TestRequest.serializer(), TestResponse.serializer())
        object TestNotificationMethod : AcpMethod.AcpNotificationMethod<TestNotification>("test/testNotification", TestNotification.serializer())
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
    fun `response is processed while notification handler is suspended`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationStarted = CompletableDeferred<Unit>()
        val releaseNotification = CompletableDeferred<Unit>()
        val notificationCompleted = CompletableDeferred<Unit>()
        val responseCompleted = CompletableDeferred<TestResponse>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) {
            notificationStarted.complete(Unit)
            releaseNotification.await()
            notificationCompleted.complete(Unit)
        }
        agentProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse(request.message)
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("suspend"))
        withTimeout(5_000) { notificationStarted.await() }

        val requestJob = launch {
            responseCompleted.complete(clientProtocol.sendRequest(TestMethod, TestRequest("response")))
        }
        val response = withTimeout(5_000) { responseCompleted.await() }

        assertEquals("response", response.message)
        assertTrue(!releaseNotification.isCompleted, "Notification handler should still be suspended")

        releaseNotification.complete(Unit)
        withTimeout(5_000) { notificationCompleted.await() }
        requestJob.join()
    }

    @Test
    fun `non suspending notification is handled before later response`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationHandled = CompletableDeferred<Unit>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) {
            notificationHandled.complete(Unit)
        }
        agentProtocol.setRequestHandler(TestMethod) { request ->
            agentProtocol.sendNotification(TestNotificationMethod, TestNotification("before response"))
            TestResponse(request.message)
        }

        val response = clientProtocol.sendRequest(TestMethod, TestRequest("response"))

        assertEquals("response", response.message)
        assertTrue(notificationHandled.isCompleted, "Notification should be handled before the later response")
    }

    @Test
    fun `notification handler can await outgoing request`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationStarted = CompletableDeferred<Unit>()
        val requestCompleted = CompletableDeferred<TestResponse>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) {
            notificationStarted.complete(Unit)
            requestCompleted.complete(clientProtocol.sendRequest(TestMethod, TestRequest("from notification")))
        }
        agentProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse(request.message)
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("request"))

        withTimeout(5_000) { notificationStarted.await() }
        val response = withTimeout(5_000) { requestCompleted.await() }
        assertEquals("from notification", response.message)
    }

    @Test
    fun `later notifications and requests progress while notification handler is suspended`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationStarted = CompletableDeferred<Unit>()
        val releaseNotification = CompletableDeferred<Unit>()
        val notificationCompleted = CompletableDeferred<Unit>()
        val laterNotificationHandled = CompletableDeferred<Unit>()
        val requestCompleted = CompletableDeferred<TestResponse>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) { notification ->
            if (notification.message == "suspend") {
                notificationStarted.complete(Unit)
                releaseNotification.await()
                notificationCompleted.complete(Unit)
            } else {
                laterNotificationHandled.complete(Unit)
            }
        }
        clientProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse(request.message)
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("suspend"))
        withTimeout(5_000) { notificationStarted.await() }
        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("later"))

        val requestJob = launch {
            requestCompleted.complete(agentProtocol.sendRequest(TestMethod, TestRequest("request")))
        }
        val response = withTimeout(5_000) {
            laterNotificationHandled.await()
            requestCompleted.await()
        }

        assertEquals("request", response.message)
        assertTrue(!releaseNotification.isCompleted, "First notification handler should still be suspended")

        releaseNotification.complete(Unit)
        withTimeout(5_000) { notificationCompleted.await() }
        requestJob.join()
    }

    @Test
    fun `cancel request notification progresses while notification handler is suspended`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationStarted = CompletableDeferred<Unit>()
        val releaseNotification = CompletableDeferred<Unit>()
        val notificationCompleted = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<RequestId>()
        val requestCancelled = CompletableDeferred<CancellationException>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) {
            notificationStarted.complete(Unit)
            releaseNotification.await()
            notificationCompleted.complete(Unit)
        }
        clientProtocol.setRequestHandler(TestMethod) {
            requestStarted.complete(currentCoroutineContext().jsonRpcRequest.id)
            try {
                awaitCancellation()
            } catch (ce: CancellationException) {
                requestCancelled.complete(ce)
                throw ce
            }
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("suspend"))
        withTimeout(5_000) { notificationStarted.await() }

        val requestJob = launch {
            agentProtocol.sendRequest(TestMethod, TestRequest("cancel"))
        }
        val requestId = withTimeout(5_000) { requestStarted.await() }
        agentProtocol.sendNotification(
            AcpMethod.MetaMethods.CancelRequest,
            CancelRequestNotification(requestId, cancellationMessage)
        )

        val cancellationException = withTimeout(5_000) { requestCancelled.await() }
        assertEquals(cancellationMessage, cancellationException.message)
        assertTrue(!releaseNotification.isCompleted, "Notification handler should still be suspended")

        releaseNotification.complete(Unit)
        withTimeout(5_000) { notificationCompleted.await() }
        agentProtocol.cancelPendingOutgoingRequests(CancellationException("Test request completed"))
        requestJob.join()
    }

    @Test
    fun `notification handler failure does not stop later protocol traffic`() = testWithProtocols { clientProtocol, agentProtocol ->
        val failingNotificationStarted = CompletableDeferred<Unit>()
        val laterNotificationHandled = CompletableDeferred<Unit>()
        val requestCompleted = CompletableDeferred<TestResponse>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) { notification ->
            if (notification.message == "fail") {
                failingNotificationStarted.complete(Unit)
                error("Notification handler failure")
            } else {
                laterNotificationHandled.complete(Unit)
            }
        }
        clientProtocol.setRequestHandler(TestMethod) { request ->
            TestResponse(request.message)
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("fail"))
        withTimeout(5_000) { failingNotificationStarted.await() }
        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("later"))

        val requestJob = launch {
            requestCompleted.complete(agentProtocol.sendRequest(TestMethod, TestRequest("request")))
        }
        val response = withTimeout(5_000) {
            laterNotificationHandled.await()
            requestCompleted.await()
        }

        assertEquals("request", response.message)
        requestJob.join()
    }

    @Test
    fun `closing protocol cancels suspended notification handler`() = testWithProtocols { clientProtocol, agentProtocol ->
        val notificationStarted = CompletableDeferred<Unit>()
        val notificationFinalized = CompletableDeferred<Unit>()

        clientProtocol.setNotificationHandler(TestNotificationMethod) {
            notificationStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                notificationFinalized.complete(Unit)
            }
        }

        agentProtocol.sendNotification(TestNotificationMethod, TestNotification("suspend"))
        withTimeout(5_000) { notificationStarted.await() }

        clientProtocol.close()

        withTimeout(5_000) { notificationFinalized.await() }
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
    fun `INTERNAL_ERROR is propagated to client`() = testWithProtocols { clientProtocol, agentProtocol ->
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
    fun `INVALID_PARAMS is propagated to client`() = testWithProtocols { clientProtocol, agentProtocol ->
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
    fun `PARSE_ERROR is propagated to client`() = testWithProtocols { clientProtocol, agentProtocol ->
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
    fun `METHOD_NOT_FOUND is propagated to client`() = testWithProtocols { clientProtocol, agentProtocol ->
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