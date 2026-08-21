package com.agentclientprotocol.samples

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.v2.ContentBlock as V2ContentBlock
import com.agentclientprotocol.model.v2.SessionUpdate as V2SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate as V2StateUpdate

@OptIn(UnstableApi::class)
fun SessionUpdate.render() {
    when (this) {
        is SessionUpdate.AgentMessageChunk -> {
            println("Agent: ${this.content.render()}")
        }

        is SessionUpdate.AgentThoughtChunk -> {
            println("Agent thinks: ${this.content.render()}")
        }

        is SessionUpdate.AvailableCommandsUpdate -> {
            println("Available commands updated:")
        }

        is SessionUpdate.CurrentModeUpdate -> {
            println("Session mode changed to: ${this.currentModeId.value}")
        }

        is SessionUpdate.PlanUpdate -> {
            println("Agent plan: ")
            for (entry in this.entries) {
                println("  [${entry.status}] ${entry.content} (${entry.priority})")
            }
        }

        is SessionUpdate.ToolCall -> {
            println("Tool call started: ${this.title} (${this.kind})")
        }

        is SessionUpdate.ToolCallUpdate -> {
            println("Tool call updated: ${this.title} (${this.kind})")
        }

        is SessionUpdate.UserMessageChunk -> {
            println("User: ${this.content.render()}")
        }

        is SessionUpdate.ConfigOptionUpdate -> {
            println("Configuration options updated")
        }

        is SessionUpdate.SessionInfoUpdate -> {
            println("Session info updated: title=${this.title}, updatedAt=${this.updatedAt}")
        }

        is SessionUpdate.UsageUpdate -> {
            println("Usage update: used=${this.used}, size=${this.size}, cost=${this.cost?.amount} ${this.cost?.currency ?: ""}")
        }

        is SessionUpdate.PlanUpdateV2 -> {
            println("Plan update (${this.plan.id}):")
            when (val plan = this.plan) {
                is PlanVariant.Items -> {
                    for (entry in plan.entries) {
                        println("  [${entry.status}] ${entry.content} (${entry.priority})")
                    }
                }
                is PlanVariant.File -> println("  File: ${plan.uri}")
                is PlanVariant.Markdown -> println("  ${plan.content}")
            }
        }

        is SessionUpdate.PlanRemoved -> {
            println("Plan removed: ${this.id}")
        }

        is SessionUpdate.UnknownSessionUpdate -> {
            println("Unknown session update: ${this.sessionUpdateType}")
        }
    }
}

fun ContentBlock.render(): String {
    return when (this) {
        is ContentBlock.Text -> text
        else -> {
            "Unsupported chunk: [${this::class.simpleName}]"
        }
    }
}

@OptIn(UnstableApi::class)
fun V2SessionUpdate.render() {
    when (this) {
        is V2SessionUpdate.UserMessage -> println("User message accepted: ${message.messageId}")
        is V2SessionUpdate.AgentMessageChunk -> println("Agent: ${chunk.content.render()}")
        is V2SessionUpdate.StateUpdate -> when (val current = state) {
            is V2StateUpdate.Idle -> println("Session idle: ${current.stopReason}")
            else -> println("Session state: ${current::class.simpleName}")
        }
        else -> println("Session update: ${this::class.simpleName}")
    }
}

@OptIn(UnstableApi::class)
private fun V2ContentBlock.render(): String = when (this) {
    is V2ContentBlock.Text -> text
    else -> "Unsupported chunk: [${this::class.simpleName}]"
}
