package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializationTests {

    @Test
    fun `decodes resource content without discriminator`() {
        val payload = """
            {
              "type": "resource",
              "resource": {
                "text": "hello",
                "uri": "file:///tmp/example.txt"
              }
            }
        """.trimIndent()

        val block = ACPJson.decodeFromString(ContentBlock.serializer(), payload)

        assertTrue(block is ContentBlock.Resource)
        val resource = block.resource
        assertTrue(resource is EmbeddedResourceResource.TextResourceContents)
        assertEquals("hello", resource.text)
        assertEquals("file:///tmp/example.txt", resource.uri)
    }

    @Test
    fun `decodes blob resource content without discriminator`() {
        val payload = """
            {
              "type": "resource",
              "resource": {
                "blob": "ZGF0YQ==",
                "mimeType": "application/octet-stream",
                "uri": "file:///tmp/data.bin"
              }
            }
        """.trimIndent()

        val block = ACPJson.decodeFromString(ContentBlock.serializer(), payload)

        assertTrue(block is ContentBlock.Resource)
        val resource = block.resource
        assertTrue(resource is EmbeddedResourceResource.BlobResourceContents)
        assertEquals("ZGF0YQ==", resource.blob)
        assertEquals("application/octet-stream", resource.mimeType)
        assertEquals("file:///tmp/data.bin", resource.uri)
    }

    @Test
    fun `decodes AvailableCommandInput without discriminator defaults to Unstructured`() {
        val payload = """
            {
              "hint": "optional custom review instructions"
            }
        """.trimIndent()

        val input = ACPJson.decodeFromString(AvailableCommandInput.serializer(), payload)

        assertTrue(input is AvailableCommandInput.Unstructured)
        assertEquals("optional custom review instructions", input.hint)
    }

    @Test
    fun `decodes AvailableCommandInput with explicit discriminator`() {
        val payload = """
            {
              "type": "unstructured",
              "hint": "enter your query"
            }
        """.trimIndent()

        val input = ACPJson.decodeFromString(AvailableCommandInput.serializer(), payload)

        assertTrue(input is AvailableCommandInput.Unstructured)
        assertEquals("enter your query", input.hint)
    }

    @Test
    fun `decodes AvailableCommand with input without discriminator`() {
        val payload = """
            {
              "name": "review",
              "description": "Review code",
              "input": {
                "hint": "optional custom review instructions"
              }
            }
        """.trimIndent()

        val command = ACPJson.decodeFromString(AvailableCommand.serializer(), payload)

        assertEquals("review", command.name)
        assertEquals("Review code", command.description)
        assertTrue(command.input is AvailableCommandInput.Unstructured)
        assertEquals("optional custom review instructions", command.input.hint)
    }

    @Test
    fun `decodes usage_update SessionUpdate`() {
        val payload = """
            {
              "sessionUpdate": "usage_update",
              "used": 0,
              "size": 200000,
              "cost": {
                "amount": 0,
                "currency": "USD"
              }
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UsageUpdate)
        assertEquals(0L, update.used)
        assertEquals(200000L, update.size)
        assertEquals(0.0, update.cost?.amount)
        assertEquals("USD", update.cost?.currency)
    }

    @Test
    fun `decodes usage_update SessionUpdate without cost`() {
        val payload = """
            {
              "sessionUpdate": "usage_update",
              "used": 12345,
              "size": 500000
            }
        """.trimIndent()

        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), payload)

        assertTrue(update is SessionUpdate.UsageUpdate)
        assertEquals(12345L, update.used)
        assertEquals(500000L, update.size)
        assertEquals(null, update.cost)
    }
}
