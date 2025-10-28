package com.agentclientprotocol

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.flow.toList

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