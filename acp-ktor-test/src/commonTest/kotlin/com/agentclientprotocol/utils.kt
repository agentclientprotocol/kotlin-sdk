package com.agentclientprotocol

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.v2.ClientSession as V2ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.v2.SessionUpdate as V2SessionUpdate
import com.agentclientprotocol.model.v2.UserMessage as V2UserMessage
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

fun agentTextChunk(text: String) = SessionUpdate.AgentMessageChunk(textBlock(text))

fun textBlock(text: String) = ContentBlock.Text(text)
fun textBlocks(vararg lines: String) = lines.map { textBlock(it) }

fun ContentBlock.render(): String {
    return when (this) {
        is ContentBlock.Text -> this.text
        is ContentBlock.Image -> "${this.mimeType}<image content>"
        else -> this.toString()
    }
}

suspend fun ClientSession.promptToList(message: String): List<String> {
    return prompt(textBlocks(message)).toList().map {
        when (it) {
            is Event.PromptResponseEvent -> {
                it.response.stopReason.toString()
            }
            is Event.SessionUpdateEvent -> {
                when (val update = it.update) {
                    is SessionUpdate.AgentMessageChunk -> update.content.render()
                    is SessionUpdate.UserMessageChunk -> update.content.render()
                    is SessionUpdate.AgentThoughtChunk -> update.content.render()
                    // TODO tool calls
                    else -> update.toString()
                }
            }
        }
    }
}

/**
 * The update with which a v2 turn reports where the user message landed, which answers the prompt.
 */
@OptIn(UnstableApi::class)
fun insertedUserMessage(): V2SessionUpdate = V2SessionUpdate.UserMessage(V2UserMessage(MessageId("user-1")))

/**
 * Collects the next [count] updates of a turn, after the user message that opens it.
 */
@OptIn(UnstableApi::class)
suspend fun V2ClientSession.turnUpdates(count: Int): List<V2SessionUpdate> {
    val updates = withTimeout(10.seconds) { updates.take(count + 1).toList() }.map { it.update }
    assertIs<V2SessionUpdate.UserMessage>(updates.first(), "a v2 turn opens with the inserted user message")
    return updates.drop(1)
}