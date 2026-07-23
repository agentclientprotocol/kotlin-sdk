@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.ProtocolConversionException
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.agentclientprotocol.model.ContentBlock as V1ContentBlock
import com.agentclientprotocol.model.Role as V1Role

class ContentBlockTest {

    // Known variants

    @Test
    fun `decodes all known variants`() {
        assertEquals(
            ContentBlock.Text(text = "hello"),
            decode("""{"type":"text","text":"hello"}"""),
        )
        assertEquals(
            ContentBlock.Image(data = "aGk=", mimeType = "image/png"),
            decode("""{"type":"image","data":"aGk=","mimeType":"image/png"}"""),
        )
        assertEquals(
            ContentBlock.Audio(data = "aGk=", mimeType = "audio/wav"),
            decode("""{"type":"audio","data":"aGk=","mimeType":"audio/wav"}"""),
        )
        assertEquals(
            ContentBlock.ResourceLink(name = "main.rs", uri = "file:///main.rs"),
            decode("""{"type":"resource_link","name":"main.rs","uri":"file:///main.rs"}"""),
        )
        assertEquals(
            ContentBlock.Resource(
                resource = EmbeddedResourceResource.TextResourceContents(text = "fn main() {}", uri = "file:///main.rs"),
            ),
            decode("""{"type":"resource","resource":{"text":"fn main() {}","uri":"file:///main.rs"}}"""),
        )
    }

    @Test
    fun `encodes text with leading discriminator`() {
        assertEquals(
            """{"type":"text","text":"hello"}""",
            encode(ContentBlock.Text(text = "hello")),
        )
    }

    @Test
    fun `embedded resource is discriminated by shape not by a type field`() {
        val blob = ContentBlock.Resource(
            resource = EmbeddedResourceResource.BlobResourceContents(blob = "aGk=", uri = "file:///a.bin"),
        )

        assertEquals(
            """{"type":"resource","resource":{"blob":"aGk=","uri":"file:///a.bin"}}""",
            encode(blob),
        )
        assertEquals(blob, decode(encode(blob)))
    }

    @Test
    fun `embedded resource without text or blob fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"resource","resource":{"uri":"file:///a.bin"}}""")
        }
    }

    @Test
    fun `resource link with icons and annotations round-trips`() {
        val link = ContentBlock.ResourceLink(
            name = "main.rs",
            uri = "file:///main.rs",
            title = "Main",
            icons = listOf(Icon(src = "https://example.com/icon.png", theme = IconTheme.Dark)),
            annotations = Annotations(audience = listOf(Role.User, Role.Unknown("system")), priority = 0.5),
        )

        assertEquals(link, decode(encode(link)))
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown content type as Unknown preserving the full payload`() {
        // The priority is deliberately not a whole number: JS has a single number type, so a
        // `1.0` literal re-emits as `1` there and byte-identity cannot hold for it.
        val json = """{"type":"video","data":"aGk=","mimeType":"video/mp4","annotations":{"priority":0.5}}"""

        val block = decode(json)

        assertIs<ContentBlock.Unknown>(block)
        assertEquals("video", block.type)
        assertEquals(json, encode(block))
    }

    @Test
    fun `underscore-prefixed extension content round-trips byte-identically`() {
        val json = """{"type":"_vendor_widget","payload":{"nested":[1,2,3]}}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"text":"hello"}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"image","data":"aGk="}""")
        }
    }

    // v2 <-> v1 conversion

    @Test
    fun `converts known variants to v1`() {
        assertEquals(
            V1ContentBlock.Text(text = "hello"),
            ContentBlock.Text(text = "hello").toV1(),
        )
        assertEquals(
            V1ContentBlock.Resource(
                resource = com.agentclientprotocol.model.EmbeddedResourceResource.TextResourceContents(
                    text = "fn main() {}",
                    uri = "file:///main.rs",
                ),
            ),
            ContentBlock.Resource(
                resource = EmbeddedResourceResource.TextResourceContents(text = "fn main() {}", uri = "file:///main.rs"),
            ).toV1(),
        )
    }

    @Test
    fun `converting to v1 skips audience roles with no v1 representation`() {
        val block = ContentBlock.Text(
            text = "hello",
            annotations = Annotations(audience = listOf(Role.User, Role.Unknown("system"))),
        ).toV1()

        assertEquals(listOf(V1Role.USER), (block as V1ContentBlock.Text).annotations?.audience)
    }

    @Test
    fun `converting resource link with icons to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ContentBlock.ResourceLink(
                name = "main.rs",
                uri = "file:///main.rs",
                icons = listOf(Icon(src = "https://example.com/icon.png")),
            ).toV1()
        }

        assertEquals("v2 ResourceLink.icons cannot be represented in v1", exception.message)
    }

    @Test
    fun `converting resource link with empty icons to v1 succeeds`() {
        val v1 = ContentBlock.ResourceLink(name = "main.rs", uri = "file:///main.rs", icons = emptyList()).toV1()

        assertEquals(V1ContentBlock.ResourceLink(name = "main.rs", uri = "file:///main.rs"), v1)
        assertNull((v1 as V1ContentBlock.ResourceLink).annotations)
    }

    @Test
    fun `converting Unknown to v1 fails instead of losing data`() {
        val exception = assertFailsWith<ProtocolConversionException> {
            ContentBlock.Unknown("video", buildJsonObject {}).toV1()
        }

        assertEquals("v2 ContentBlock variant `video` cannot be represented in v1", exception.message)
    }

    @Test
    fun `converts all v1 variants to v2`() {
        assertEquals(
            ContentBlock.Text(text = "hello"),
            V1ContentBlock.Text(text = "hello").toV2(),
        )
        assertEquals(
            ContentBlock.Image(data = "aGk=", mimeType = "image/png", uri = "file:///a.png"),
            V1ContentBlock.Image(data = "aGk=", mimeType = "image/png", uri = "file:///a.png").toV2(),
        )
        assertEquals(
            ContentBlock.Audio(data = "aGk=", mimeType = "audio/wav"),
            V1ContentBlock.Audio(data = "aGk=", mimeType = "audio/wav").toV2(),
        )
        assertEquals(
            ContentBlock.ResourceLink(name = "main.rs", uri = "file:///main.rs", size = 42L),
            V1ContentBlock.ResourceLink(name = "main.rs", uri = "file:///main.rs", size = 42L).toV2(),
        )
        assertEquals(
            ContentBlock.Resource(
                resource = EmbeddedResourceResource.BlobResourceContents(blob = "aGk=", uri = "file:///a.bin"),
            ),
            V1ContentBlock.Resource(
                resource = com.agentclientprotocol.model.EmbeddedResourceResource.BlobResourceContents(
                    blob = "aGk=",
                    uri = "file:///a.bin",
                ),
            ).toV2(),
        )
    }

    @Test
    fun `converts v1 annotations to v2 totally`() {
        val v2 = V1ContentBlock.Text(
            text = "hello",
            annotations = com.agentclientprotocol.model.Annotations(
                audience = listOf(V1Role.ASSISTANT),
                priority = 1.0,
                lastModified = "2025-01-12T15:00:58Z",
            ),
        ).toV2()

        assertEquals(
            Annotations(
                audience = listOf(Role.Assistant),
                priority = 1.0,
                lastModified = "2025-01-12T15:00:58Z",
            ),
            (v2 as ContentBlock.Text).annotations,
        )
    }

    private fun decode(json: String): ContentBlock =
        ACPJson.decodeFromString(ContentBlock.serializer(), json)

    private fun encode(block: ContentBlock): String =
        ACPJson.encodeToString(ContentBlock.serializer(), block)
}
