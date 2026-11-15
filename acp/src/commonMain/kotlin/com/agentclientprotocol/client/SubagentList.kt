package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The [BaseClientSession.subagents] of one session, in announcement order. It only grows, until the session's holder
 * fails or is closed.
 *
 * The session's [ClientSessionHolder] owns it and hands it to the session object, because the children are added
 * while the session's history is delivered, before a root's [ClientSession] exists.
 */
@OptIn(UnstableApi::class)
internal class SubagentList {
    private data class Entries(
        val list: PersistentList<ClientSubagentSession> = persistentListOf(),
        val closed: Boolean = false,
    )

    private val entries = atomic(Entries())

    /**
     * The children added so far. A later [add] does not change a returned list.
     */
    val snapshot: List<ClientSubagentSession> get() = entries.value.list

    /**
     * Adds [child], unless [close] has run.
     *
     * @return `false` if the list is closed, so [child] was not added
     */
    fun add(child: ClientSubagentSession): Boolean {
        var added = false
        entries.update { current ->
            added = !current.closed
            if (added) current.copy(list = current.list.add(child)) else current
        }
        return added
    }

    /**
     * Keeps the list as it is from now on.
     */
    fun close() {
        entries.update { it.copy(closed = true) }
    }
}
