package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedResourceResourceTest {

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
}
