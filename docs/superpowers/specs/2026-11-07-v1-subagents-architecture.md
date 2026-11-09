# v1 subagents: architecture

Status: every question under "Open questions" at the end is decided, and the decisions are written into the
sections above. It supersedes the agent-side sections of `2026-11-05-v1-subagents-design.md`, which records the
earlier discussion. Where the two disagree, this file wins.

## Sources

- Spec: `agent-client-protocol` at `67cde2d5`. `docs/rfds/subagents.mdx` has not changed since `dc011f0e`, the
  revision the earlier notes cite. Line numbers below refer to that RFD unless another file is named. The RFD is an
  unstable draft.
- Rust v1 models: `agent-client-protocol-schema/src/v1/client.rs`, behind the `unstable_subagents` feature:
  - `SessionMessageChunk` (`:200`) and `SessionMessage` (`:246`);
  - `SubagentUpdate` (`:964`);
  - `SubagentSessionCapabilities` (`:1131`) and `SessionCancelCapabilities` (`:1187`);
  - `StateUpdate` (`:1234`) and `IdleStateUpdate` (`:1298`);
  - `ClientCapabilities.subagents: Option<SubagentCapabilities>` (`:3386`).
- Use the `check-specification` skill to re-read the RFD and the Rust models before writing models or serializers.
  Do not rely on this summary for wire shapes.

## Prerequisites

- This branch (`eugenethedev/subagents`) is stacked on PR #156 (`9941000`, branch
  `eugenethedev/permission-cancellation-fix`). #156 makes the v1 client answer pending permission requests with
  `cancelled` on `cancel()`/`close()`, through `client/PendingPermissionCancellation.kt`.
- #156 is based on master with PR #155 (`f858693`: one v1 turn at a time, cancel targets its own turn), so this
  branch includes #155. If #156 changes again, rebase onto its new head.
- Scope: protocol v1 only. v2 follows separately (see "Looking ahead to v2"). Do not change v2 runtimes.

## What the spec requires (v1)

- **Opt-in.** The client advertises `clientCapabilities.subagents: {}`. Without it, the agent MUST NOT send
  `subagent_update`, `session_message` or `session_message_chunk`, including during replay (`:59-75`, `:881-883`).
- **Announcement.** The first `subagent_update` for an unknown `update.sessionId` announces a child. Later ones patch
  it. The outer `sessionId` is the immediate parent, which allows arbitrary nesting (`:86-90`, `:162-166`). The
  announcement MUST precede every request or notification bearing the child's id, including live `session_message`s
  naming it (`:92-95`). The agent MUST NOT guess parentage (`:184-188`). Only `update.sessionId` is required, and it
  is unique on the connection (`:123-127`).
- **Patches.** `title`, `description`, `capabilities`, `state`, `cwd`, `additionalDirectories` and `_meta`: omitted
  means unchanged, `null` clears, a value replaces. `capabilities` is replaced as a whole, and in v1 `_meta` is too.
  `{ "cancel": null }` and `capabilities: null` both revoke cancel (`:138-160`).
- **Streams.** A `subagent_update` goes on the parent's stream (outer `sessionId` = parent). Everything else about the
  child goes on the child's stream (outer = child). In v1, the child's work state exists only as
  `subagent_update.state` on the parent's stream, and nothing is duplicated (`:663-665`, `:1115-1117`).
- **Work state** (`:731-800`). `running`, `requires_action`, `idle` (optional `stopReason`, including `error` with an
  optional JSON-RPC `error`, plus `usage` and `_meta`) and `unknown`. Unrecognized states and stop reasons are kept.
  - The agent MUST report `running`/`idle` when it observes child foreground work starting, resuming or stopping.
  - It MUST report `unknown` when observability is actually lost.
  - Work may start and stop many times. It may also start without the parent assigning anything (`:677-679`).
  - A v1 child has no prompt and no prompt response (`:710`, `:755-757`).
- **Messages** (`:417-575`). `session_message` (an upsert with participant ids and links between the two views) and
  `session_message_chunk`. Each view goes on the stream of the session whose transcript it belongs to. Delivery is
  the agent's own business, and the agent reports only views it observed.
- **Restrictions** (`:946-969`). The client must not prompt, load, resume or close a child. `session/cancel` is
  allowed only while the child's current `capabilities.cancel` is granted.
- **Cancellation** (`:985-1033`):
  - Cancelling work also cancels the descendant work it waits on, while background children may continue.
  - The agent reports cancellation from actual evidence: a cancellation that races with already-ended work does not
    rewrite that work's outcome (`:1007-1013`).
  - Closing a parent cancels all descendant work and needs no terminal notification.
- **Replay** (`:840-940`):
  - `session/load` replays child associations and history best-effort, and everything replayed MUST be sent before
    the response.
  - Snapshots of state and grants sent before the response are history. Fresh snapshots come after it, as ordinary
    patches; no new announcement phase is needed.
  - `session/resume` has no replay, but controls still wait for a successful answer.

## Model layer (`:acp-model`, all `@UnstableApi`)

- **Move `MaybeUndefined`** from `model.v2` (`acp-model/.../model/v2/MaybeUndefined.kt`) to a package shared by v1
  and v2. v1 models must not import a v2 type.
  - Keep v2 source compatibility if practical: a deprecated typealias is fine. Otherwise update all v2 usages in the
    same change.
  - Regenerate the API dump and review it.
- **New capability:** `ClientCapabilities.subagents: SubagentCapabilities?` in `model/Capabilities.kt`.
  `SubagentCapabilities` is an empty marker with `_meta`.
- **New `SessionUpdate` variants** in `model/SessionUpdate.kt`:
  - `SubagentUpdate`: `sessionId`, then `MaybeUndefined` `title`, `description`, `capabilities`, `state`, `cwd`,
    `additionalDirectories` and `_meta`;
  - `SessionMessage`: an upsert with `messageId`, `MaybeUndefined` `content`, participant ids, message references and
    `_meta`;
  - `SessionMessageChunk`: `messageId`, one `ContentBlock` and chunk-scoped `_meta`.
- **Capabilities and state types:**
  - `SubagentSessionCapabilities(cancel: SessionCancelCapabilities?, _meta)` and `SessionCancelCapabilities(_meta)`;
  - open unions `StateUpdate` (Rust `v1::client::StateUpdate`, `client.rs:1234`) and `IdleStopReason` (Rust
    `client.rs:1410`), with the Rust names in the v1 package `com.agentclientprotocol.model`:
    - `StateUpdate`: `Running`, `Idle`, `RequiresAction`, `Unknown` (the RFD's real `unknown` state) and `Other`
      (an unrecognized state; keeps the discriminator and payload). Use `Other` for unrecognized values, as Rust
      does, because `Unknown` is a real state here. Note this differs from v2's Kotlin `StateUpdate`, where `Unknown`
      means unrecognized.
    - `IdleStopReason`: the v1 `StopReason` values, `Error` (with an optional JSON-RPC `error`) and `Other`.
  - **Why dedicated v1 types.** In Rust, v1 `StateUpdate` exists only for subagents: it's behind
    `unstable_subagents` and used only by `SubagentUpdate.state` (`client.rs:1003`), since v1 has no `state_update`.
    - Reusing v2's `model.v2.StateUpdate` would cross the version boundary (v1 models must not import v2 types).
      It also lacks `error` and the real `unknown` state, and it would tie v1 deserialization to future v2-only
      states. Rust keeps the v1 and v2 types separate, with the same shape.
    - Reusing v1's `StopReason` for `Idle.stopReason` doesn't work: it's a closed, stable enum used by
      `PromptResponse`, with no `error` payload and no way to keep unrecognized values. Rust keeps it as is and adds
      `IdleStopReason` too.
    - A version-neutral shared type (like `MaybeUndefined`) was rejected: it would tie two separate schemas together,
      unlike `MaybeUndefined`, which is a serialization helper with no protocol meaning.
  - `Idle` (Rust `IdleStateUpdate`, `client.rs:1298`) has the flattened stop reason, `usage: Usage?` (the existing v1
    `Usage` model; omitted or `null` means not reported) and `_meta`. In Rust, `usage` sits behind
    `unstable_end_turn_token_usage` and is deserialized with `DefaultOnError`, so a malformed `usage` becomes
    `null` without failing the snapshot. Match that, and test it.
- **Serializers.** Write them by hand, like other structs with `MaybeUndefined` fields. Add JSON tests for:
  - absent versus `null` on every patch field;
  - `capabilities` replaced as a whole;
  - round-tripping unrecognized states and stop reasons;
  - `idle` with `error`.

## Agent side

### Model

The SDK doesn't run subagents. A child's work may be a coroutine in this process or a subroutine inside a native agent
process the SDK wraps. The app gives the SDK an object with the child's event stream and a cancel callback. The SDK:

1. collects the events and routes each one to the parent's or the child's stream;
2. routes `session/cancel` to the child while it is granted;
3. ties the collection to the parent's lifetime;
4. reports `unknown` when the stream ends while the child is active.

The app reports everything else itself, including `running`/`idle`, following the spec. This mirrors the v2 runtime,
where the app reports state and the SDK only closes what the app left open (`agent/v2/Agent.kt`, `runTurn`). v1
children use the v2 state model (`:731`).

### Public API (`com.agentclientprotocol.agent`, all `@UnstableApi`)

The shape follows v2's agent API, because the RFD models v1 and v2 subagents on v2, and the user wants one shape for
both versions. "One shape" means similar structure, not shared types. The v2 types will most likely be separate
declarations in `agent.v2`, because `SessionUpdate` and the client operations differ between versions. Keep the two
shapes as close as reasonably possible without contradicting the established patterns of each version. The SDK hands
the child's operations to an app factory, as v2's `AgentSupport.createSession(parameters, client)` does. During the parent's replay, the factory replays the child's history through those operations before it
returns, as v2's `resumeSession` does.

```kotlin
/**
 * What the SDK gives the factory that builds a child: the child's own operations, bound to its id.
 */
@UnstableApi
public interface AgentSubagentContext {
    public val sessionId: SessionId

    /**
     * The child's operations. They apply the routing rule (see "Routing"), and fail until the child's
     * announcement has been sent, except for sending the announcement itself.
     */
    public val client: ClientSessionOperations

    /**
     * The child's own subagents, for announcing grandchildren.
     */
    public val subagents: AgentSubagents
}

/**
 * A subagent the app exposes to the client. Built by an [AgentSubagentFactory].
 */
@UnstableApi
public interface AgentSubagentSession {
    /**
     * The child's live events, as the subagents spec defines them.
     *
     * A SubagentUpdate for this child goes on the parent's stream: it announces the child if nothing was sent for
     * the child before, and otherwise patches it and carries its work state. Every other update goes on the child's
     * own stream. The SDK collects this once, after the factory returns, in the child's session context, so it
     * should be cold (or hot with replay). For a child replayed during the parent's `session/load`, collection starts
     * after the parent's response. It may be empty for a history-only child.
     */
    public val events: Flow<SessionUpdate> get() = emptyFlow()

    /**
     * Called on `session/cancel` for this child while the current cancel grant is in effect. Report the outcome
     * through [events]. The SDK does not stop the collection.
     */
    public suspend fun cancel() {}
}

/**
 * Builds a child for [AgentSubagents.announce]. Follows `ClientOperationsFactory` on the client side.
 */
@UnstableApi
public fun interface AgentSubagentFactory<out S : AgentSubagentSession> {
    /**
     * Builds the child. When called from the parent's replay, send the child's announcement and recorded history
     * through [context] before returning. The SDK collects the returned session's events afterwards.
     */
    public suspend fun createSubagent(context: AgentSubagentContext): S
}

@UnstableApi
public interface AgentSubagents {
    /**
     * Registers [sessionId] under [parent], calls [factory] with the child's context, then collects the returned
     * session's events. Returns the session once the child's announcement has been sent and [factory] has returned.
     *
     * [parent] defaults to the session this object is bound to. It may name a live, announced descendant of that
     * session, for relays that learn about nested children from a flat event stream.
     */
    public suspend fun <S : AgentSubagentSession> announce(
        sessionId: SessionId,
        factory: AgentSubagentFactory<S>,
        parent: SessionId? = null,
    ): S
}

/**
 * The subagents of the current session: a root session or a subagent. `null` when the client didn't advertise
 * `subagents`: run the child without exposing it.
 */
public val CoroutineContext.subagents: AgentSubagents?
```

- The accessor is nullable instead of an `isSupported` flag, so the compiler forces the capability check. No other
  SDK API uses a support flag. It also matches the provisional v2 shape, a nullable `subagents` parameter (see
  "Looking ahead to v2"). `AgentSubagentContext.subagents` stays non-null, because a child exists only when the
  client supports subagents.

- `factory` comes before `parent`, so a trailing lambda still works with the default `parent`. The app can implement
  the factory as a named class (for example `RestoreReviewer(record)` and `StartReviewer(queue)`) or pass a lambda.
- The id goes to `announce`, not to the session, because the SDK needs it before the factory runs, to bind the
  child's operations. This is the same reason v2's `resumeSession` receives the id. `AgentSubagentSession` has no
  `sessionId`, so the two ids can't disagree.
- There's no `isReplay` flag. The app calls `announce` either from the parent's replay code or from its live code, so
  it already knows the mode, as the v2 SDK knows it by calling `resumeSession` or `createSession`. The SDK uses the
  timing only internally (see "Replay").
- There's no per-child `replayHistory()` hook. The v1 root hook exists only because v1's `AgentSupport.loadSession`
  gets no client operations (#150, `e5f8c31`). v2's `AgentSession` has no hooks, and a hook would bring a v1-only
  pattern into the shared shape.
- `AgentSubagents` is bound to one session and stays valid for that session's lifetime, not only inside the call that
  obtained it. After the session is closed, `announce` fails. There is no SDK-side handle type and no `update`
  method, because patches are ordinary events.
- No subagent factory on `AgentSupport`: the agent starts children itself, so nothing on the client side triggers
  their creation, and the SDK would never call such a method on its own.

### `announce` semantics

1. No capability check: without `subagents` the accessor is `null`, so `announce` can't be reached.
2. Resolve the parent:
   - `parent == null`: the bound session;
   - otherwise it must be the bound session or a live, announced descendant of it, or `IllegalArgumentException`.
3. Check that `sessionId` is not registered on the connection, root sessions included, or
   `IllegalArgumentException`.
4. Register a `SubagentWrapper` (below) under the parent, and call `factory.createSubagent(context)` in the child's
   context.
5. **Enforce the announcement first.** The first thing sent for the child must be a `SubagentUpdate` whose
   `sessionId` is the child's, whether it goes through `context.client.notify` inside the factory or comes as the
   first item of `events`. Both paths go through the child's operations, so it's one check in one place.
   - Until the announcement has been sent, the child's client operations (permission, fs, terminal, elicitation,
     `notify` of anything else) fail with `IllegalStateException`, so nothing about the child can overtake it.
   - If the factory throws, or if nothing was sent and `events` completes or throws before emitting, or if its first
     item isn't the child's `SubagentUpdate`: stop, unregister the child, send nothing more, and make `announce`
     throw `IllegalStateException`.
   - **During the parent's replay window, the factory must send the announcement itself.** If it returns without
     sending one, `announce` throws `IllegalStateException` right away and unregisters the child. Waiting for
     `events` would deadlock: its collection starts only after the load response, which waits for `replayHistory()`,
     which waits for `announce`.
6. After the factory returns, start collecting `events` in the child's context, in a scope owned by the root session
   (see "Lifetimes"): right away for a live announcement, after the parent's load response for a replayed child.
7. Return the session once the announcement has been sent and the factory has returned. This is the caller's
   ordering point. For example, the parent may then send its own `session_message` naming the child (`:92-95`).
   - A live child that announces itself only in `events` makes `announce` wait for that first item, with no
     timeout. The SDK puts no timeouts on app code. An app that wants a bound wraps the call in `withTimeout`.
   - **If the caller is cancelled while waiting** and nothing has been sent yet, cancel the collection, unregister
     the child and rethrow. If the announcement has already gone out, the child exists on the wire: it stays
     registered and its collection keeps running. Only the caller gets the `CancellationException`.

### Routing

The rule applies to every update sent for the child: from `events`, from `context.client.notify`, or from
`currentCoroutineContext().client.notify` in the child's context. It's implemented in the child's operations, and the
collector sends through the same path:

| Update | Outer `sessionId` |
|---|---|
| `SubagentUpdate` with `sessionId` = this child | the child's parent |
| `SubagentUpdate` for any other id | rejected with `IllegalArgumentException`: announce grandchildren through `subagents.announce` |
| anything else, including `session_message`/`session_message_chunk` | the child |

- In a root session's context, any `SubagentUpdate` through `client.notify` or the prompt flow's
  `Event.SessionUpdateEvent` is rejected with `IllegalArgumentException` pointing to `subagents.announce`. The SDK must
  see every announcement and grant.
- The SDK doesn't validate or rewrite patch contents.
- Each send goes through `RemoteClientSessionOperations`/`Protocol` immediately, in emission order, so per-session
  ordering holds.

### What the SDK tracks per child (`SubagentWrapper`)

- **`grant`:** whether cancel is currently granted. It's updated from each `SubagentUpdate` for the child that went
  out live:
  - `capabilities` omitted: unchanged;
  - `capabilities` `null`, or `cancel` null/absent: no grant;
  - `cancel` non-null: granted.

  The initial value is no grant. Only live updates count; see "Replay" for history. Updates from every path count
  (`events`, `context.client.notify`, the context's `client`), because the grant decides which `session/cancel`
  the client may send.
- **`state`:** the last `state` snapshot sent **through `events`**: none, a state, or cleared. Used only to decide
  whether a feed that ends owes `unknown`. State sent through `client.notify` is not seen. This follows the existing
  runtimes: v1's `SessionWrapper.prompt` and v2's `runTurn` (`terminalIdleSent`) inspect only the flow they collect,
  and `client.notify` goes straight to the wire.
- **Its parent and children,** for nesting checks and subtree cleanup (see "Lifetimes").
- **The child's context.** `SubagentWrapper` extends `Agent.BaseSessionWrapper`, so `currentCoroutineContext().client`,
  `.agent` and `.subagents` work in the child's context. `client` is a `RemoteClientSessionOperations` for the child's
  id, gated until the announcement is sent, and applies the routing rule.

`Agent` keeps one table per connection, from the child id to its `SubagentWrapper`, next to `_sessions`.

### Incoming requests on a child id

- **`session/cancel`:**
  - If the id is a child whose `grant` is in effect, call `AgentSubagentSession.cancel()` in the child's context.
  - Otherwise log and ignore, since a notification can't get an error.
  - The SDK doesn't stop the collection, and doesn't report `cancelled` itself. The app reports the actual outcome
    (`:1007-1013`).
  - Cancel is still routed after the feed has ended, because `unknown` doesn't revoke a grant (`:780-783`).
- **Every other session method on a child id** fails with an explicit "session X is a subagent" error through
  `acpFail`. That covers `session/prompt`, `set_mode`, `set_model`, `set_config_option`, `close`, `fork`, `load` and
  `resume`. Today, `getSessionOrThrow` answers "not found" for these.

### Feed end and failure

- If the collection **completes or throws** while the last `state` is `running`, `requires_action` or an unrecognized
  state, send `SubagentUpdate(sessionId = child, state = Unknown)`. Log the exception at `warn`: the SDK is
  recovering as the spec intends, and the app's work didn't necessarily fail.
  - Use `runCatching` around the send, as `reportIdle` does in `v2/Agent.kt`: a dead protocol must stay silent.
  - A thrown flow means the SDK lost its view of the child, not that the work failed. In a relay the native child
    may still be running. `idle(error)` would invent an outcome, which the RFD forbids: `unknown` is "not a work
    outcome" (`:735-737`), `error` is only for "a failure that ends the child's foreground work" (`:753-755`), and
    a lost feed is reported as `unknown` "without inventing an outcome" (`:1287`).
  - This differs from v2's `runTurn`, which reports `idle` with no stop reason when the prompt flow throws. There the
    flow is the turn, so a throw proves the turn ended. Here `events` only observes work that runs elsewhere.
  - When the app knows its own work failed, it catches the failure and emits `idle(error)` through `events` before
    the flow ends. The last state is then `idle`, so the SDK sends nothing. Document this in the KDoc.
- If the last `state` is `idle`, `unknown`, none or cleared, send nothing.
- If the collection is **cancelled** because the parent closed or the connection ended, send nothing (`:1029-1033`).
- After the feed ends, the child stays registered: its id stays unique, cancel routing still works, and it can still
  be named as a parent. It's removed only by subtree cleanup.

### Lifetimes

- Each root `SessionWrapper` owns a supervisor scope for its subtree's collections. It's cancelled when the root
  session is closed, after `AgentSession.close()` returns, and when the connection ends. One child's collection
  failing doesn't affect others.
- **Subtree cleanup** is what the SDK does for every descendant of a closed root, at every depth:
  1. cancels the collection of its `events` (one cancel of the root's supervisor scope). No `unknown` is sent,
     because the feed ended by cancellation. For an in-process child whose flow is the work, this also stops the
     work. For a relay it only stops forwarding;
  2. removes it from the child table: its id is free, `session/cancel` for it is ignored, and it can't be named as
     a `parent`;
  3. makes its `AgentSubagents` and client operations fail.

  The SDK calls no app code for the children: not `cancel()`, and there is no per-child close hook. Stopping the
  descendants' work, background children included (`:1030-1034`), and freeing their resources belongs to the app's
  root `AgentSession.close()`.
  - `cancel()` doesn't fit: it stops only foreground work, reports an outcome nobody needs after close, and is tied
    to the client's grant.
  - A per-child close hook was considered and deferred. v2 has no per-session close hook
    (`AgentSupport.closeSession`), and the app already owns the tree it announced. It can be added later without
    breaking anything.
- **When it runs.** In v1 only a root can be closed, since `session/close` on a child id is rejected. So subtree
  cleanup runs in exactly two cases:
  - **`session/close` on the root** (only when the agent advertises `close`): first the app's `close()`, then subtree
    cleanup, then the response. Nothing about the children reaches the wire after the close is answered. Children
    may still send final updates while the app's `close()` runs, which is harmless. If `close()` throws, there is no
    cleanup: the parent stays registered (today's handler, `agent/Agent.kt:386-393`), so its children do too.
  - **The connection ends:** subtree cleanup without calling the app's `close()`.
- A child whose feed ends doesn't cancel its descendants' collections. In a relay, they may still be running natively.
- Cancelling a child calls only that child's `cancel()`. Stopping the descendants its work waits on (`:987-993`) is
  the app's job.

### Replay (`session/load`)

The spec defines history by timing: during the parent's `session/load`, everything sent before the response is
history, and fresh snapshots come after it (`:919-928`). The app starts every child replay itself, because child
registrations don't survive a reconnect and only the app knows which children existed. So children are replayed from
the parent's `AgentSession.replayHistory()`, which marks the replay window for the whole subtree.

- The parent's `replayHistory()` calls `announce` for each recorded child, with a factory that replays:
  - the factory sends the child's announcement and recorded history through `context.client` before returning;
  - it announces recorded grandchildren through `context.subagents`, which repeats the same pattern;
  - it returns the live session, or one with empty `events` for a history-only child.
- The SDK treats grants and states sent before the parent's response as history: they don't set `grant`, and they
  don't feed the `unknown` rule.
- The SDK starts collecting a replayed child's `events` after the parent's load response (`afterResponse`). Its
  updates are fresh patches, not a new announcement (`:925-928`).
- If the load fails, drop every registration made during the attempt.
- Children of a resumed parent (`session/resume`, no replay) are announced live, for example from `postInitialize`.
  Snapshots sent before a resume response are current observations, not history (`:930-933`). The client applies
  their grants only after the resume succeeds, which needs nothing on the agent side.
- Ancestor chains: before live traffic for a child, its ancestors must be announced in parent-before-child order
  during this load/resume (`:897-902`). This follows from the parent check in `announce`.

### Examples

In-process child, with separate factories for the live and replay paths:

```kotlin
class Reviewer(private val ctx: AgentSubagentContext, private val work: ReceiveChannel<Task>) : AgentSubagentSession {
    override val events: Flow<SessionUpdate> = channelFlow {
        send(SessionUpdate.SubagentUpdate(ctx.sessionId, title = MaybeUndefined.Value("Reviewer"),
            capabilities = MaybeUndefined.Value(SubagentSessionCapabilities(cancel = SessionCancelCapabilities()))))
        for (task in work) {
            send(SessionUpdate.SubagentUpdate(ctx.sessionId, state = MaybeUndefined.Value(StateUpdate.Running())))
            // ... the child's own updates via send(...); requests via ctx.client
            send(SessionUpdate.SubagentUpdate(ctx.sessionId, state = MaybeUndefined.Value(StateUpdate.Idle(EndTurn))))
        }
    }
    override suspend fun cancel() { /* stop the current task; the loop reports idle(cancelled) */ }
}

class StartReviewer(private val queue: ReceiveChannel<Task>) : AgentSubagentFactory<Reviewer> {
    override suspend fun createSubagent(context: AgentSubagentContext) = Reviewer(context, queue)
}

class RestoreReviewer(private val record: ChildRecord) : AgentSubagentFactory<AgentSubagentSession> {
    override suspend fun createSubagent(context: AgentSubagentContext): AgentSubagentSession {
        context.client.notify(record.announcement)
        record.history.forEach { context.client.notify(it) }
        record.children.forEach { context.subagents.announce(it.id, RestoreReviewer(it)) }
        return if (record.runtimeAlive) Reviewer(context, record.resumeQueue()) else object : AgentSubagentSession {}
    }
}

// in the parent's AgentSession.prompt flow
currentCoroutineContext().subagents?.announce(SessionId("rev-1"), StartReviewer(queue))

// in the parent's AgentSession.replayHistory()
val subagents = currentCoroutineContext().subagents ?: return
store.children(sessionId).forEach { subagents.announce(it.id, RestoreReviewer(it)) }
```

Relay over a native agent process, with flat spawn events and explicit parents:

```kotlin
override suspend fun postInitialize() {
    val subagents = currentCoroutineContext().subagents ?: return
    scope.launch {
        native.spawned.collect { info ->
            subagents.announce(SessionId(info.id), parent = info.parentId?.let(::SessionId)) { ctx ->
                NativeChild(ctx, native, info)
            }
        }
    }
}

class NativeChild(val ctx: AgentSubagentContext, val native: NativeAgent, val info: NativeInfo) : AgentSubagentSession {
    override val events = flow {
        emit(SessionUpdate.SubagentUpdate(ctx.sessionId, title = MaybeUndefined.Value(info.name), ...))
        emitAll(native.events(info.id).map { it.toSessionUpdate(ctx.sessionId) })
    }
    override suspend fun cancel() = native.interrupt(info.id)
}
```

Messages need no API. A parent → child message is the parent's outgoing `session_message` on its own path (prompt flow
or `client.notify`), plus the child's incoming view in the child's `events`. The app links them with
`senderMessageId`/`recipientMessageId`.

## Client side

The client side is carried over from the earlier notes. The user decided its type structure, and confirmed the
delivery and routing details below (open question 7).

### Operations

- `SubagentOperations` is a mixin interface in `common`, in the style of `FileSystemOperations` and
  `ElicitationOperations`, and `ClientSessionOperations` inherits it:

  ```kotlin
  public fun createSubagentOperations(subagent: ClientSubagentSession): ClientSessionOperations =
      throw NotImplementedError(...)
  ```

  Nested children use the same method on the child's operations.

### Types

- `BaseClientSession` is a sealed v1 interface. The name follows the `Base` prefix of `Agent.BaseSessionWrapper`. Its direct subtypes are `ClientSession` and `ClientSubagentSession`.
  - Members: `sessionId`, `client`, `operations`, `subagents` (the children, named the same on both), and the
    effective `cwd`/`additionalDirectories` (inherited from the parent unless set).
  - Shared KDoc lives on `BaseClientSession` and its members. Overrides add KDoc only where their behaviour differs
    in an important way.
  - A new context accessor returns `BaseClientSession`. `coroutineContext.clientSession`
    (`client/ClientSessionImpl.kt:230`) stays as it is.
  - Adding `subagents` to `ClientSession` breaks external implementations of that interface. This is accepted
    because the member is `@UnstableApi`.
- `ClientSubagentSession` has no prompt, mode, model, config or close, and no config or mode state either (open
  question 8). A child's `config_option_update` and `current_mode_update` (RFD `:381-415`, `:274-276`) reach its
  `notify` like any other update, as in v2, where the client keeps no config flow and reports changes as updates.
  `ClientSessionImpl.trackState` is root-only: a child's update must never touch a root's `currentMode` or
  `configOptions`. Its members:
  - `parent: BaseClientSession`, `title`, `description`;
  - `state`, which is unconfirmed until a fresh snapshot arrives;
  - `canCancel: StateFlow<Boolean>`;
  - `cancel()`, which throws `IllegalStateException` locally when no grant is in effect, like `Client.authStatus`.

  `cancel()` is not on the base type.

### Routing and delivery

- **Registration.** The client's `session/update` handler (`client/Client.kt:183`) registers a `ClientSessionHolder`
  and a `ClientSubagentSession` for a newly announced child synchronously, as its first step, before anything that
  can suspend. The `subagent_update` itself is then delivered to the parent as usual, through its prompt flow or
  `notify`.
  - **Why synchronously:** `Protocol` runs each notification handler up to its first suspension point before it
    handles the next message (`protocol/Protocol.kt:431-436`, on the single-threaded `handlerDispatcher`), but it
    doesn't wait for the handler to finish. Delivering to the parent's `notify` runs app code and can suspend. If the
    holder were created after that, the child's next update would go to `globalSessionUpdateHandler` as
    "unconnected", and its next request would fail with "session not found".
  - **Layering:** the check lives in `Client`, which already decodes `SessionNotification` and already creates
    speculative holders there. `Protocol` doesn't change and learns nothing about `SessionUpdate` types. Its only
    ACP-specific knowledge stays `$/cancel_request` and the caller-supplied `SessionId` on outgoing requests.
  - **Guards:**
    - a comment at the registration saying why it must come before anything that suspends;
    - a regression test (see "Tests");
    - the ordering guarantee written into `Protocol`'s KDoc for `setNotificationHandler`, so it's a stated contract
      rather than a comment in the read loop. Note there that an `additionalContext` with a dispatcher would break
      it, because `withContext` dispatches before the handler runs.
- **Setup.**
  - While `createSubagentOperations` runs, the child's holder queues its updates and its requests wait, as in the
    existing factory path.
  - With the `ClientOperationsFactory` overloads, the parent's operations don't exist until the parent's answer, so
    the child's holder waits for its parent first. The main case is `session/load` with a factory: the agent replays
    children before the response, and the factory runs only after it. Then the SDK calls
    `createSubagentOperations` and drains the child's queue into the result, as `drainEventsAndCompleteSession` does.
  - With the overloads that take `ClientSessionOperations`, the parent's operations exist from the start, so the
    child's operations are created as soon as the announcement is delivered.
  - This holds at every depth: a grandchild waits for its child, which waits for the root.
  - Each session's updates keep their order. Follow the single-queue rule for `ClientSessionHolder` in `CLAUDE.md`.
- **After setup.** All of the child's updates go to its `notify`. There's no prompt flow, because the client never
  prompts a child. Its permission, fs, terminal and elicitation requests go to its operations.
- **Failed setup.** If `createSubagentOperations` throws, whether because it isn't overridden or for any other reason,
  only that child's holder fails, through `failSessionHolder` as for a failed `ClientOperationsFactory`:
  - its queued and later updates go to `globalSessionUpdateHandler`;
  - its requests get an error;
  - the failure is logged;
  - the parent and its other children keep working.

  A grandchild of a failed child can't get operations, because they come from its failed parent, so its holder fails
  the same way.

### Effective cancel grant

- The latest stored `capabilities.cancel` is non-null. `null`, `{}` and `{cancel: null}` all mean no grant, and there
  is no grant until `capabilities` has been sent. `unknown` doesn't revoke it.
- **Load or resume of a parent:** grants reset to none.
  - `session/load`: grants before the response are history and never take effect.
  - `session/resume`: grants before the response take effect only once it succeeds.
  - A failed attempt discards everything received during it.

### Pending permission requests (builds on #156)

Each child has its own `PendingPermissionCancellation`:

| Event | Root (#156) | Child |
|---|---|---|
| Work starts | `prompt()` → `startTurn()` | child reports `running` → `startTurn()` |
| Client cancels it directly | `cancel()` → `cancelPending()` | `ClientSubagentSession.cancel()` → `cancelPending()` |
| Ancestor cancelled | n/a | child reports `idle` + `cancelled` → `cancelPending()` (`:1045-1051`) |
| Ancestor closed | `close()` | `close()` on every descendant, and their holders are removed |

### Discovery

- Children are discovered only from announcements. There's no list or query.
- After `session/resume`, the client sees only the children the agent re-announces.
- Child history is available only through the parent's `session/load`.

## Tests

Put them in `acp` `commonTest`, unless they cross transports, in which case they go in `:acp-integration-test`. They
cover externally observable behaviour.

**Model**
- Serialization of every new type: absent versus `null`, unrecognized values, `idle` with `error`.

**Agent**
- Capability gate: `CoroutineContext.subagents` is `null` without `subagents`, and non-null with it.
- First-send rule: a factory that throws, a wrong first send or first item, an empty flow with nothing sent, or a
  flow that throws first all make `announce` throw, and nothing more reaches the wire.
- The announcement may come from the factory (`context.client.notify`) or as the first item of `events`.
- Client operations fail before the announcement.
- `announce` returns only after the announcement has been sent. A parent `session_message` sent right after it
  arrives after the announcement.
- Cancelling a waiting `announce`: with nothing sent, the child is unregistered and its id is free again; with
  the announcement already sent, the child stays registered and its `events` keep flowing.
- Routing:
  - own `SubagentUpdate` → parent's stream;
  - other updates → child's stream;
  - a foreign `SubagentUpdate` is rejected;
  - a root-context `SubagentUpdate` is rejected.
- Nesting:
  - a grandchild announced from the child's context lands on the child's stream;
  - an explicit `parent` works;
  - an unknown or foreign `parent` is rejected;
  - a duplicate id is rejected.
- Cancel routing:
  - routed with a grant, whether it was sent through `events` or `client.notify`;
  - ignored without one, after a revocation, and for a grant seen only in history;
  - still routed after the feed ends;
  - `cancel()` runs in the child's context.
- Feed end:
  - `unknown` after completion or failure while `running`/`requires_action`;
  - nothing when the active state was sent only through `client.notify`;
  - nothing when `idle`, including an `idle(error)` the app emitted before the flow threw;
  - nothing on parent close.
- Subtree cleanup:
  - collections are cancelled, ids are freed, and `AgentSubagents`/client operations fail afterwards;
  - no child update reaches the wire after the `session/close` response;
  - a `close()` that throws leaves the parent and its children registered;
  - the connection ending cleans up every subtree without calling the app's `close()`.
- Other methods on a child id fail with the "is a subagent" error.
- Replay: history sent from the factory (including nested grandchildren) goes out before the load response, live
  `events` starts after it, history grants don't enable cancel, a history-only child with empty `events` works, and
  a replay factory that returns without announcing makes `announce` throw instead of hanging.

**Client**
- Every item above under "Client side", including the #156 table for children.
- Registration race: the agent announces a child and immediately sends `session/request_permission` for it while the
  parent's `notify` is blocked. The request reaches the child's operations instead of failing with "session not
  found". The same for the child's first `session/update`.
- A throwing `createSubagentOperations` fails only that child and its descendants.

**End-to-end**
- An agent and a client from this SDK over STDIO and WebSocket, in `:acp-integration-test`: announce, nested child,
  messages, cancel, close.

## Deliverables

- KDoc for every public type, following the `CLAUDE.md` KDoc rules.
- `apiDump` for `:acp-model` and `:acp`, run separately, with the diff reviewed. Then run `./gradlew check`.
- An update to `CLAUDE.md` ("Runtime and Lifecycle Rules") covering:
  - subagents are announced through `AgentSubagents.announce`, and the first event is the announcement;
  - the routing rule;
  - the SDK reports only `unknown`;
  - the client's per-child holder and grant reset.
- A sample in `:samples:kotlin-acp-client-sample` is optional. If you add one, use only public APIs.

## Looking ahead to v2 (do not implement now)

- v2 has no capability, because subagents are baseline. The child reports state with an ordinary `state_update` on its
  own stream, and the SDK mirrors the same snapshot onto the parent's `subagent_update.state` before collecting the
  next event (`:681-690`). A child `SubagentUpdate` carrying `state` would be rejected.
- v2 merges `_meta` patches by key.
- Replay uses `session/resume` with `replayFrom`.
- The same `AgentSubagentSession` shape should carry over.
- **Provisional:** a root session would likely get its `AgentSubagents` as a separate parameter of
  `AgentSupport.createSession`/`resumeSession`/`forkSession`, possibly nullable with a `null` default.
  - Rejected: a member on `agent.v2.ClientOperations`. Subagents are SDK bookkeeping on the agent side, not
    something the session asks of the client.
  - Also considered: a per-session context object (`client` + `subagents`) replacing the `client` parameter.
  - To check then: a default value doesn't help implementers of an interface method, so a new parameter breaks
    overrides anyway (an overload with a delegating default would not). And v2 subagents are baseline, not an
    optional capability, so what `null` would mean needs a reason.
  - In v2 the session owns its id, so `subagents` needs the same late binding as `ClientOperations.notify`.

## Side findings

- The v2 client's `cancel()` answers every pending permission request, but v2 exempts requests for work in the
  `background` list (`docs/protocol/v2/prompt-lifecycle.mdx`). The v2 runtime doesn't model that list yet.
- **v1 `Agent.kt` registers the `SessionResume` handler twice** (confirmed after the rebase, `agent/Agent.kt:346` and
  `:410`). The second registration replaces the first, so only the one at `:410`, which includes `configOptions` in
  the response, is used. Remove the first one.
- **The Kotlin v2 state model lags behind the subagent schema** (for the v2 work, not now). Compared with Rust at
  `67cde2d5`:
  - `model.v2.StopReason` has no `error` variant. Rust v2 `StopReason` has `Error(ErrorStopReason)`, added on
    2026-10-01 according to the RFD changelog.
  - `model.v2.StateUpdate` has no real `unknown` state. Its `Unknown` variant already means "unrecognized state,
    raw JSON kept", so adding the RFD's `unknown` state means renaming it (Rust uses `Other`), which is a breaking v2
    change.
- **v1 root session state after `session/load` is applied in the wrong order.** The agent replays the history, then
  answers. So the response's `configOptions`/`modes` are newer than every replayed `config_option_update` or
  `current_mode_update` (v2 says this explicitly: `docs/protocol/v2/session-setup.mdx:138`). `ClientSessionImpl`
  starts from the response and then applies the replayed updates on top: the factory path through
  `drainEventsAndCompleteSession` → `trackState`, and the early path (#146) through
  `earlyMode`/`earlyConfigOptions` in `completeEarlySession`. So older history overwrites the response.
  `ClientEarlySessionUpdatesTest` ("mode and config options after an early load equal those after a buffered load")
  pins this.
  - **Done in this branch:** `ClientSession.configOptions` and `ClientSession.currentMode` are deprecated for removal
    with this explanation. The related internals (`_configOptions`, `_currentMode`, `trackState`,
    `earlyConfigOptions`, `earlyMode`, the update in `setConfigOption`) and the tests that assert them carry
    `TODO remove` comments. `configOptionsSupported`, `modesSupported` and `availableModes` are left alone: they
    are read only from the response, so they have no ordering problem.
  - If the tracking ever comes back, it must also keep live updates that arrive after the response but before the
    drain, so it needs to know where the response fell in the queue.
  - **Separate bug:** `_currentMode` is a `lazy` that throws when the response has no `modes`
    (`ClientSessionImpl.kt:42`), and `trackState` writes to it on every `current_mode_update` without a guard. Config
    options are guarded by `configOptionsSupported`.

## Open questions

### Context for resuming the discussion

The design moved through several shapes before landing on the factory-based `announce` above. A new session should
not reopen these without a new reason:

- **No SDK-run turns.** The SDK doesn't start or run a child's work (no `send`, `runTurn` or `PromptResponse` for
  children). A child's work may run inside a native agent process the SDK only wraps. In v1, a child has no prompt and
  no prompt response (`:710`, `:755-757`). The SDK's guarantees come from routing, from owning the collection of
  `events`, and from reporting `unknown` when that feed ends while the child is active.
- **A child may start work on its own.** Work may start and stop many times, with or without an assignment from the
  parent (`:677-679`, `:747-748`). v1 children use the v2 state model (`:731`).
- **One app type, no SDK handle.** The app implements `AgentSubagentSession`. Patches, state and messages are ordinary
  events, so there's no `update(...)` method and no `AgentSubagent` handle. Metadata properties such as `title` on the
  session were dropped, so the announcement is the only source of that data.
- **The announcement is the first send for the child,** enforced by the SDK. `announce` returns only after it's been
  sent, so the parent's `session_message` naming the child can't overtake it.
- **The SDK doesn't rewrite stop reasons.** The RFD says a cancellation racing with already-ended work doesn't
  rewrite its outcome, and cancellation is reported from evidence (`:1007-1013`). So unlike the v1 root, the SDK never
  forces `cancelled`.
- **No duplication in v1.** A child's state exists only as `subagent_update.state` on the parent's stream. In v2 the
  child reports `state_update` on its own stream, and the SDK would mirror it onto the parent (`:681-690`).
- **Replay inside the factory, not a hook.** The v1 root `AgentSession.replayHistory()` exists only because v1's
  `AgentSupport.loadSession` gets no client operations (#150, `e5f8c31`). v2 has no `postInitialize` or
  `replayHistory`: it passes `client: ClientOperations` into `createSession`/`resumeSession`/`forkSession`, and
  `resumeSession` replays before returning. The user wants one subagent API shape for v1 and v2, so the factory
  mirrors v2. A separate `history` flow and a per-child `replayHistory()` hook were both considered and rejected.
- **No `isReplay` flag.** The app calls `announce` from either its replay code or its live code, so it knows the mode,
  as the v2 SDK knows it by calling `resumeSession` or `createSession`.
- **A `fun interface` factory, not a bare lambda type,** following `ClientOperationsFactory`, so the app can implement
  it as a named class with a real method. A factory registered with the SDK (like `AgentSupport`) and a two-step
  `register`/`start` were rejected: the first is a round trip the SDK never triggers, and the second leaves
  half-registered children after failures.

### Questions (verbatim from the last session)

Yes. These are the questions still open, roughly in order of how much they affect the design. Once you've answered them, I'll update the doc with the factory approach and these answers in one pass.

#### Agent API

1. **How the parent gets `AgentSubagents` in both v1 and v2.**
   - **Decided for v1:** a coroutine-context accessor, `currentCoroutineContext().subagents`, next to `.client` and `.agent`. In a child's context it returns the child's own `AgentSubagents`. v1's `ClientSessionOperations` can't carry it, because it lives in `common` and the client side implements it too.
   - **Decided:** the v1 and v2 types share a shape, not declarations (see "Public API").
   - **Provisional, for v2 (decide during the v2 work):** see "Looking ahead to v2". Nothing in v1 depends on it.
2. **Two ways to send for a live child. Decided:**
   - Both paths are allowed: `events` and `ctx.client.notify` (or the context's `client`).
   - Routing, the announcement-first gate and grant tracking apply to every path, because they decide what is valid on the wire.
   - Only `events` feeds the `state` used by the automatic `unknown`, as in the existing v1/v2 runtimes (see "What the SDK tracks per child"). Document it: report state through `events` to get the automatic `unknown`.
3. **A live announcement that never comes. Decided:** `announce` waits for the first item of `events` with no
   timeout. During replay the factory must announce, and a cancelled wait cleans up only if nothing was sent (see
   "`announce` semantics", steps 5 and 7).
4. **Names. Decided:** keep `AgentSubagentSession`, `AgentSubagentFactory.createSubagent`, `AgentSubagentContext`, `AgentSubagents.announce` and `CoroutineContext.subagents`. Replace `isSupported` with a nullable `CoroutineContext.subagents` (see "Public API").

#### Formerly marked [confirm]

5. **A failed flow. Decided:** if `events` throws while the child is `running` or `requires_action`, the SDK reports `unknown`, not `idle(error)`, and logs the exception at `warn` (see "Feed end and failure").
6. **Closing a parent. Decided:** subtree cleanup cancels collections, frees ids and disables operations. It calls no app code for the children. Order: the app's `close()`, then cleanup, then the response. No cleanup if `close()` throws. On connection end, cleanup runs without `close()` (see "Lifetimes").
7. **The client side. Decided:** synchronous registration of a new child in the client's `session/update` handler, with the three guards; a child's holder waiting for its parent's holder; a throwing `createSubagentOperations` failing only that child (and its descendants); the name `BaseClientSession` (see "Client side").

#### Not designed yet

8. **Read-only config for children on the client. Decided:** no config state at all, only notifications, as in v2. A child's `config_option_update` and `current_mode_update` reach its `notify` like any other update (see "Client side", "Types").
9. **Usage and cost.**
   - A child reports `usage` in its `idle` snapshot, and the client must not add up the tree (`:1071-1101`).
   - I'd expect no SDK work beyond the models. Should `ClientSubagentSession` expose the last reported `usage`?
   - **Decided:** no tracking. The only work is the model field `StateUpdate.Idle.usage` (see "Model layer"). The
     `idle` snapshot with its `usage` is already visible through `ClientSubagentSession.state`, a child's
     `usage_update` reaches its `notify` like any other update, and the SDK never adds up usage across the tree.

#### Process

10. **Rebase. Done:** this branch is rebased onto the current #156 head (`9941000`), which includes #155 (see "Prerequisites").
