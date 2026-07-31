@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToolCallContentTest {

    // Known variants

    @Test
    fun `decodes the content variant with a nested content block`() {
        assertEquals(
            ToolCallContent.Content(content = ContentBlock.Text(text = "hello")),
            decode("""{"type":"content","content":{"type":"text","text":"hello"}}"""),
        )
    }

    @Test
    fun `encodes the content variant with leading discriminator`() {
        assertEquals(
            """{"type":"content","content":{"type":"text","text":"hello"}}""",
            encode(ToolCallContent.Content(content = ContentBlock.Text(text = "hello"))),
        )
    }

    @Test
    fun `decodes the diff variant with structured changes and patch`() {
        val json = """
            {"type":"diff","changes":[
                {"operation":"modify","path":"/src/main.rs","fileType":"text"},
                {"operation":"move","oldPath":"/old.rs","path":"/new.rs"}
            ],"patch":{"format":"git_patch","diff":"--- a/src/main.rs"}}
        """.trimIndent().replace(Regex("\\n\\s*"), "")

        assertEquals(
            ToolCallContent.Diff(
                changes = listOf(
                    DiffChange(
                        operation = DiffChangeOperation.Modify(path = "/src/main.rs"),
                        fileType = DiffFileType.Text,
                    ),
                    DiffChange(operation = DiffChangeOperation.Move(oldPath = "/old.rs", path = "/new.rs")),
                ),
                patch = DiffPatch(diff = "--- a/src/main.rs"),
            ),
            decode(json),
        )
    }

    @Test
    fun `diff variant round-trips`() {
        val diff = ToolCallContent.Diff(
            changes = listOf(
                DiffChange(
                    operation = DiffChangeOperation.Add(path = "/a.txt"),
                    fileType = DiffFileType.Text,
                    mimeType = "text/plain",
                ),
                DiffChange(operation = DiffChangeOperation.Copy(oldPath = "/a.txt", path = "/b.txt")),
                DiffChange(operation = DiffChangeOperation.Delete(path = "/c.bin"), fileType = DiffFileType.Binary),
            ),
            patch = DiffPatch(diff = "diff --git a/a.txt b/a.txt"),
        )

        assertEquals(diff, decode(encode(diff)))
    }

    // Open leaves of the diff graph

    @Test
    fun `unknown diff change operation is preserved with its fields`() {
        val json = """{"type":"diff","changes":[{"operation":"archive","target":"/backup.tar","fileType":"text"}]}"""

        val diff = decode(json)

        assertIs<ToolCallContent.Diff>(diff)
        val change = diff.changes.single()
        val operation = change.operation
        assertIs<DiffChangeOperation.Unknown>(operation)
        assertEquals("archive", operation.operation)
        assertEquals(DiffFileType.Text, change.fileType)
        assertEquals(json, encode(diff))
    }

    @Test
    fun `unknown file type and patch format deserialize to Unknown`() {
        val diff = decode(
            """{"type":"diff","changes":[{"operation":"add","path":"/a","fileType":"socket"}],""" +
                """"patch":{"format":"_vendor_patch","diff":"x"}}""",
        )

        assertIs<ToolCallContent.Diff>(diff)
        assertEquals(DiffFileType.Unknown("socket"), diff.changes.single().fileType)
        assertEquals(DiffPatchFormat.Unknown("_vendor_patch"), diff.patch?.format)
    }

    @Test
    fun `diff change without operation fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"diff","changes":[{"path":"/a.txt"}]}""")
        }
    }

    @Test
    fun `known operation with missing path fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"diff","changes":[{"operation":"add"}]}""")
        }
    }

    // Unknown discriminators (forward compatibility)

    @Test
    fun `decodes unknown content type as Unknown preserving the full payload`() {
        val json = """{"type":"terminal","terminalId":"term-1"}"""

        val content = decode(json)

        assertIs<ToolCallContent.Unknown>(content)
        assertEquals("terminal", content.type)
        assertEquals(json, encode(content))
    }

    @Test
    fun `underscore-prefixed extension content round-trips byte-identically`() {
        val json = """{"type":"_vendor_preview","frames":[1,2]}"""

        assertEquals(json, encode(decode(json)))
    }

    // Strictness

    @Test
    fun `missing discriminator fails`() {
        assertFailsWith<SerializationException> {
            decode("""{"content":{"type":"text","text":"hello"}}""")
        }
    }

    @Test
    fun `known discriminator with malformed payload fails instead of falling back`() {
        assertFailsWith<SerializationException> {
            decode("""{"type":"content"}""")
        }
    }

    private fun decode(json: String): ToolCallContent =
        ACPJson.decodeFromString(ToolCallContent.serializer(), json)

    private fun encode(content: ToolCallContent): String =
        ACPJson.encodeToString(ToolCallContent.serializer(), content)
}
