package com.agentclientprotocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JsonDecodeTest {

    @Test
    fun testDecode() {
        val result = decodeJsonRpcMessage(
            """
            {
              "jsonrpc": "2.0",
              "id": 5,
              "result": {
                "outcome": {
                  "outcome": "selected",
                  "optionId": "allow-once"
                }
              }
            }
        """.trimIndent()
        )
        assertTrue(result is JsonRpcResponse)
    }

    @Test
    fun testDecodeWithPrefix() {
        val result = decodeJsonRpcMessage(
            """
            asdfasdfasdf(){
              "jsonrpc": "2.0",
              "id": 5,
              "result": {
                "outcome": {
                  "outcome": "selected",
                  "optionId": "allow-once"
                }
              }
            }
        """.trimIndent()
        )
        assertTrue(result is JsonRpcResponse)
        assertEquals("selected", result.result!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun testDecodeError() {
        try {
            decodeJsonRpcMessage(
                """
            asdfasdfas
                "outcome": {
                  "outcome": "selected",
                  "optionId": "allow-once"
                }
              }
            }
        """.trimIndent()
            )
            fail("Exception expected")
        } catch (_: SerializationException) {
        }
    }

}