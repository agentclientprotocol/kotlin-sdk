@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionNoticeAndCompactionTest {

    // --- Notice ---

    @Test
    fun `decodes notice with warning severity`() {
        val json = """{"severity":"warning","title":"MCP server unavailable","description":"Continuing without it."}"""
        val notice = ACPJson.decodeFromString(Notice.serializer(), json)
        assertEquals(NoticeSeverity.Warning, notice.severity)
        assertEquals("MCP server unavailable", notice.title)
        assertEquals("Continuing without it.", notice.description)
        assertNull(notice._meta)
    }

    @Test
    fun `decodes notice without optional fields`() {
        val json = """{"severity":"info","title":"Indexing started"}"""
        val notice = ACPJson.decodeFromString(Notice.serializer(), json)
        assertEquals(NoticeSeverity.Info, notice.severity)
        assertEquals("Indexing started", notice.title)
        assertNull(notice.description)
    }

    @Test
    fun `decodes notice with error severity`() {
        val json = """{"severity":"error","title":"Provider failed","description":"Retrying."}"""
        val notice = ACPJson.decodeFromString(Notice.serializer(), json)
        assertEquals(NoticeSeverity.Error, notice.severity)
    }

    @Test
    fun `decodes notice with unknown severity`() {
        val json = """{"severity":"_custom","title":"Custom notice"}"""
        val notice = ACPJson.decodeFromString(Notice.serializer(), json)
        assertIs<NoticeSeverity.Unknown>(notice.severity)
        assertEquals("_custom", notice.severity.value)
    }

    @Test
    fun `round-trips notice as session update`() {
        val original = SessionUpdate.Notice(
            Notice(severity = NoticeSeverity.Warning, title = "Low memory", description = "Consider closing tabs.")
        )
        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), original)
        assertContains(encoded, "\"sessionUpdate\":\"notice\"")
        assertContains(encoded, "\"severity\":\"warning\"")
        assertContains(encoded, "\"title\":\"Low memory\"")

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertIs<SessionUpdate.Notice>(decoded)
        assertEquals("Low memory", decoded.notice.title)
        assertEquals(NoticeSeverity.Warning, decoded.notice.severity)
    }

    @Test
    fun `decodes notice session update from json`() {
        val json = """{
            "sessionUpdate": "notice",
            "severity": "info",
            "title": "Sync complete",
            "description": "All files are up to date."
        }"""
        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), json)
        assertIs<SessionUpdate.Notice>(update)
        assertEquals(NoticeSeverity.Info, update.notice.severity)
        assertEquals("Sync complete", update.notice.title)
        assertEquals("All files are up to date.", update.notice.description)
    }

    // --- NoticeSeverity open enum ---

    @Test
    fun `NoticeSeverity extension value must start with underscore`() {
        assertFailsWith<IllegalArgumentException> {
            NoticeSeverity.extension("customValue")
        }
    }

    @Test
    fun `NoticeSeverity extension value with underscore is accepted`() {
        val ext = NoticeSeverity.extension("_myCustomSeverity")
        assertIs<NoticeSeverity.Unknown>(ext)
        assertEquals("_myCustomSeverity", ext.value)
    }

    // --- CompactionStatus open enum ---

    @Test
    fun `decodes all known CompactionStatus values`() {
        assertEquals(CompactionStatus.InProgress, decodeStatus("in_progress"))
        assertEquals(CompactionStatus.Completed, decodeStatus("completed"))
        assertEquals(CompactionStatus.Failed, decodeStatus("failed"))
        assertEquals(CompactionStatus.Cancelled, decodeStatus("cancelled"))
    }

    @Test
    fun `decodes unknown CompactionStatus as Unknown`() {
        val status = decodeStatus("_experimental")
        assertIs<CompactionStatus.Unknown>(status)
        assertEquals("_experimental", status.value)
    }

    private fun decodeStatus(value: String): CompactionStatus =
        ACPJson.decodeFromString(CompactionStatus.serializer(), "\"$value\"")

    // --- CompactionUpdate ---

    @Test
    fun `decodes compaction_update with in_progress status`() {
        val json = """{"compactionId":"cmp_001","status":"in_progress"}"""
        val update = ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        assertEquals("cmp_001", update.compactionId)
        assertEquals(CompactionStatus.InProgress, update.status)
        assertEquals(MaybeUndefined.Undefined, update.summary)
        assertEquals(MaybeUndefined.Undefined, update.error)
        assertEquals(MaybeUndefined.Undefined, update._meta)
    }

    @Test
    fun `decodes compaction_update with completed status and summary`() {
        val json = """{
            "compactionId": "cmp_001",
            "status": "completed",
            "summary": [{"type": "text", "text": "Kept 3 messages."}]
        }"""
        val update = ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        assertEquals(CompactionStatus.Completed, update.status)
        assertIs<MaybeUndefined.Value<List<ContentBlock>>>(update.summary)
        val summary = (update.summary as MaybeUndefined.Value).value
        assertEquals(1, summary.size)
        assertEquals("Kept 3 messages.", (summary[0] as ContentBlock.Text).text)
    }

    @Test
    fun `decodes compaction_update with null summary as Null`() {
        val json = """{"compactionId":"cmp_001","status":"completed","summary":null}"""
        val update = ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        assertEquals(MaybeUndefined.Null, update.summary)
    }

    @Test
    fun `decodes compaction_update with error`() {
        val json = """{"compactionId":"cmp_002","status":"failed","error":"Context limit exceeded"}"""
        val update = ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        assertEquals(CompactionStatus.Failed, update.status)
        assertIs<MaybeUndefined.Value<String>>(update.error)
        assertEquals("Context limit exceeded", (update.error as MaybeUndefined.Value).value)
    }

    @Test
    fun `round-trips compaction_update as session update`() {
        val original = SessionUpdate.CompactionUpdate(
            CompactionUpdate(compactionId = "cmp_42", status = CompactionStatus.InProgress)
        )
        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), original)
        assertContains(encoded, "\"sessionUpdate\":\"compaction_update\"")
        assertContains(encoded, "\"compactionId\":\"cmp_42\"")
        assertContains(encoded, "\"status\":\"in_progress\"")
        assertTrue(!encoded.contains("summary"))
        assertTrue(!encoded.contains("error"))

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertIs<SessionUpdate.CompactionUpdate>(decoded)
        assertEquals("cmp_42", decoded.update.compactionId)
        assertEquals(CompactionStatus.InProgress, decoded.update.status)
    }

    @Test
    fun `decodes compaction_update session update from json`() {
        val json = """{
            "sessionUpdate": "compaction_update",
            "compactionId": "cmp_001",
            "status": "in_progress"
        }"""
        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), json)
        assertIs<SessionUpdate.CompactionUpdate>(update)
        assertEquals("cmp_001", update.update.compactionId)
        assertEquals(CompactionStatus.InProgress, update.update.status)
    }

    @Test
    fun `serializes compaction_update omitting undefined patch fields`() {
        val compactionUpdate = CompactionUpdate(
            compactionId = "cmp_1",
            status = CompactionStatus.Completed,
            summary = MaybeUndefined.Value(listOf(ContentBlock.Text("Summary text"))),
        )
        val encoded = ACPJson.encodeToString(CompactionUpdate.serializer(), compactionUpdate)
        assertContains(encoded, "\"summary\"")
        assertTrue(!encoded.contains("\"error\""))
    }

    @Test
    fun `serializes compaction_update with null error as explicit null`() {
        val compactionUpdate = CompactionUpdate(
            compactionId = "cmp_1",
            status = CompactionStatus.Failed,
            error = MaybeUndefined.Null,
        )
        val encoded = ACPJson.encodeToString(CompactionUpdate.serializer(), compactionUpdate)
        assertContains(encoded, "\"error\":null")
    }

    @Test
    fun `throws on missing compactionId`() {
        val json = """{"status":"in_progress"}"""
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        }
    }

    @Test
    fun `throws on missing status`() {
        val json = """{"compactionId":"cmp_1"}"""
        assertFailsWith<SerializationException> {
            ACPJson.decodeFromString(CompactionUpdate.serializer(), json)
        }
    }

    // --- CompactionSummaryChunk ---

    @Test
    fun `decodes compaction_summary_chunk`() {
        val json = """{"compactionId":"cmp_001","content":{"type":"text","text":"Condensed history."}}"""
        val chunk = ACPJson.decodeFromString(CompactionSummaryChunk.serializer(), json)
        assertEquals("cmp_001", chunk.compactionId)
        assertEquals("Condensed history.", (chunk.content as ContentBlock.Text).text)
        assertNull(chunk._meta)
    }

    @Test
    fun `round-trips compaction_summary_chunk as session update`() {
        val original = SessionUpdate.CompactionSummaryChunk(
            CompactionSummaryChunk(
                compactionId = "cmp_001",
                content = ContentBlock.Text("Retained context."),
            )
        )
        val encoded = ACPJson.encodeToString(SessionUpdate.serializer(), original)
        assertContains(encoded, "\"sessionUpdate\":\"compaction_summary_chunk\"")
        assertContains(encoded, "\"compactionId\":\"cmp_001\"")

        val decoded = ACPJson.decodeFromString(SessionUpdate.serializer(), encoded)
        assertIs<SessionUpdate.CompactionSummaryChunk>(decoded)
        assertEquals("cmp_001", decoded.chunk.compactionId)
        assertEquals("Retained context.", (decoded.chunk.content as ContentBlock.Text).text)
    }

    @Test
    fun `decodes compaction_summary_chunk session update from json`() {
        val json = """{
            "sessionUpdate": "compaction_summary_chunk",
            "compactionId": "cmp_001",
            "content": {"type": "text", "text": "Kept the last 5 exchanges."}
        }"""
        val update = ACPJson.decodeFromString(SessionUpdate.serializer(), json)
        assertIs<SessionUpdate.CompactionSummaryChunk>(update)
        assertEquals("cmp_001", update.chunk.compactionId)
        assertEquals("Kept the last 5 exchanges.", (update.chunk.content as ContentBlock.Text).text)
    }

    // --- full lifecycle sequence ---

    @Test
    fun `decodes a compaction lifecycle sequence`() {
        val updates = listOf(
            """{"sessionUpdate":"compaction_update","compactionId":"cmp_1","status":"in_progress"}""",
            """{"sessionUpdate":"compaction_summary_chunk","compactionId":"cmp_1","content":{"type":"text","text":"Chunk 1"}}""",
            """{"sessionUpdate":"compaction_summary_chunk","compactionId":"cmp_1","content":{"type":"text","text":"Chunk 2"}}""",
            """{"sessionUpdate":"compaction_update","compactionId":"cmp_1","status":"completed","summary":[{"type":"text","text":"Final summary"}]}""",
        ).map { ACPJson.decodeFromString(SessionUpdate.serializer(), it) }

        assertIs<SessionUpdate.CompactionUpdate>(updates[0])
        assertIs<SessionUpdate.CompactionSummaryChunk>(updates[1])
        assertIs<SessionUpdate.CompactionSummaryChunk>(updates[2])
        assertIs<SessionUpdate.CompactionUpdate>(updates[3])

        val terminal = updates[3] as SessionUpdate.CompactionUpdate
        assertEquals(CompactionStatus.Completed, terminal.update.status)
        val summary = (terminal.update.summary as MaybeUndefined.Value).value
        assertEquals("Final summary", (summary[0] as ContentBlock.Text).text)
    }
}
