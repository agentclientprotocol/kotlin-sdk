package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The [BaseClientSession.subagents] of one session, in announcement order. It only grows.
 *
 * The session's [ClientSessionHolder] owns it and hands it to the session object, because the children are added
 * while the session's history is delivered, before a root's [ClientSession] exists.
 */
@OptIn(UnstableApi::class)
internal class SubagentList {
    private val list = atomic<PersistentList<ClientSubagentSession>>(persistentListOf())

    /**
     * The children added so far. A later [add] does not change a returned list.
     */
    val snapshot: List<ClientSubagentSession> get() = list.value

    fun add(child: ClientSubagentSession) {
        list.update { it.add(child) }
    }
}
