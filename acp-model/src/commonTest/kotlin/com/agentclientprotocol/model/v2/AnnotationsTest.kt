@file:OptIn(UnstableApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.v2.conversion.toV1
import com.agentclientprotocol.model.v2.conversion.toV2
import com.agentclientprotocol.rpc.ACPJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.agentclientprotocol.model.Annotations as V1Annotations
import com.agentclientprotocol.model.ContentBlock as V1ContentBlock

class AnnotationsTest {
    @Test
    fun `priority is optional and includes both bounds`() {
        for (priority in listOf(null, 0.0, 0.5, 1.0)) {
            val annotations = Annotations(priority = priority)
            val block = ContentBlock.Text(text = "hello", annotations = annotations)
            val json = """{"type":"text","text":"hello","annotations":{"priority":$priority}}"""

            assertEquals(block, ACPJson.decodeFromString(ContentBlock.serializer(), json))
            assertEquals(
                block,
                ACPJson.decodeFromString(
                    ContentBlock.serializer(),
                    ACPJson.encodeToString(ContentBlock.serializer(), block),
                ),
            )
            assertEquals(annotations, annotations.toV1().toV2())
        }

        val block = ACPJson.decodeFromString<ContentBlock>(
            """{"type":"text","text":"hello","annotations":{}}""",
        )
        assertEquals(Annotations(), assertIs<ContentBlock.Text>(block).annotations)
    }

    @Test
    fun `priority rejects out of range and non-finite values`() {
        for (priority in listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("priority=$priority") {
                Annotations(priority = priority)
            }
            assertFailsWith<IllegalArgumentException>("copy priority=$priority") {
                Annotations(priority = 0.5).copy(priority = priority)
            }
        }
    }

    @Test
    fun `content decoding rejects out of range priorities`() {
        for (priority in listOf(-0.01, 1.01)) {
            assertFailsWith<IllegalArgumentException>("priority=$priority") {
                ACPJson.decodeFromString<ContentBlock>(
                    """{"type":"text","text":"hello","annotations":{"priority":$priority}}""",
                )
            }
        }
    }

    @Test
    fun `v1 conversion rejects out of range priorities`() {
        for (priority in listOf(-0.01, 1.01)) {
            assertFailsWith<IllegalArgumentException>("priority=$priority") {
                V1ContentBlock.Text(text = "hello", annotations = V1Annotations(priority = priority)).toV2()
            }
        }
    }
}
