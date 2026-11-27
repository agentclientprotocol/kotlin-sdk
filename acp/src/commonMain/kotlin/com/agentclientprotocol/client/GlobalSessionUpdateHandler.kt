package com.agentclientprotocol.client

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * Handler for `session/update` notifications about a session the client is not connected to.
 *
 * A session can live on the server without this client ever having called `session/new` / `session/load` /
 * `session/resume` for it - e.g. it was created from another IDE window, the web, or another machine. This is
 * invoked instead of failing or silently dropping the notification, letting a client observe such updates -
 * for example to keep a `session/list`-rendered list live without polling.
 *
 * It also receives the updates of sessions the client dropped: a session whose opening failed, the tree of a closed
 * root, and a subagent whose setup or stream failed.
 */
@UnstableApi
public fun interface GlobalSessionUpdateHandler {
    public suspend fun onUnconnectedSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?)
}
