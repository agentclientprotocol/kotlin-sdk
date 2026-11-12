# v1 subagents: architecture

Status: every question in the "Decision log" at the end is decided, and the decisions are written into the
sections above. "Open questions (from the implementer review)" lists gaps that still need decisions before
implementation. It supersedes the agent-side sections of `2026-11-05-v1-subagents-design.md`, which records the
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

## Implementation order

Read `CLAUDE.md` and this file first. Resolve "Open questions (from the implementer review)" with the user before
starting the parts they affect: question 25 blocks the client side, and 26 affects the model and both sides.
Then:

1. **Model layer** and its serialization tests. Re-read the RFD and the Rust models with `check-specification` first.
   Run `:acp-model:apiDump` and review the diff.
2. **Agent side:** `announce`, routing, per-child tracking, incoming requests on child ids, feed end, lifetimes and
   replay, with the agent tests.
3. **The opening boundary** (see "History and live updates"), as its own PR before the client side. Before it, as
   its own commit: the `clearCancellation()` fix to #156 (see "Pending permission requests"). The PR's first commit
   extracts `ClientSessionRegistry` from `Client` (see "Session registry"): no behaviour or API change, and the
   existing client and `ClientSessionHolderTest` tests pass unchanged. Then: the internal `Protocol` response hook,
   the `Opened` marker in the holder's queue, `SessionEvent` in `ClientSessionOperations.notify`, and the
   one-opening rule for holders (see "Holders"). It's a breaking change to stable v1 API and behaviour, so it needs
   a release note and reviewed API dumps on its own.
4. **Client side:** types, registration, setup and delivery, effective grants and pending permissions, with the
   client tests.
5. **End-to-end** tests in `:acp-integration-test`.
6. **Docs and checks:** the `Protocol` KDoc and `CLAUDE.md` updates, `:acp:apiDump` with a reviewed diff, then
   `./gradlew check` (see "Deliverables").

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

- **Move `MaybeUndefined`** from `model.v2` (`acp-model/.../model/v2/MaybeUndefined.kt`) to
  `com.agentclientprotocol.model`, which v2 models already import from (for example `Usage`). v1 models must not
  import a v2 type.
  - Update every v2 usage in the same change. A deprecated typealias wouldn't keep v2 source-compatible, because
    Kotlin doesn't reach nested classes (`MaybeUndefined.Value`, `.Undefined`) through a typealias, and it wouldn't
    cover top-level extensions either.
  - Regenerate the API dump and review it.
- **New capability:** `ClientCapabilities.subagents: SubagentCapabilities? = null` in `model/Capabilities.kt`,
  appended as the last constructor parameter. `ClientCapabilities` is a data class, so this changes its constructor
  and `copy`; that's accepted for an `@UnstableApi` member. `SubagentCapabilities` is an empty marker with `_meta`.
- **New `SessionUpdate` variants** in `model/SessionUpdate.kt`, with the discriminators `subagent_update`,
  `session_message` and `session_message_chunk`. Add them to the hand-written `SessionUpdateSerializer`, which today
  decodes unknown discriminators as `UnknownSessionUpdate`.
  - `SubagentUpdate`: `sessionId`, then `MaybeUndefined` `title`, `description`, `capabilities`, `state`, `cwd`,
    `additionalDirectories` and `_meta`;
  - `SessionMessage`: `messageId` (the existing v1 `MessageId`), then `MaybeUndefined` `senderSessionId`,
    `recipientSessionId`, `senderMessageId`, `recipientMessageId`, `content: List<ContentBlock>` and `_meta`. For
    `content`, `null` **or `[]`** clears it, and an unparseable block is skipped, not fatal (Rust `VecSkipError`);
  - `SessionMessageChunk`: `messageId`, one `ContentBlock` and `_meta: JsonElement?` (plain optional, not
    `MaybeUndefined`).
- **Lenient decoding (Rust `DefaultOnError`).** A malformed value becomes its default instead of failing the whole
  update:
  - `SubagentUpdate`: `title`, `description`, `capabilities`, `state` and `_meta` become absent. **`cwd` and
    `additionalDirectories` are strict**: a malformed value fails the update.
  - `SessionMessage`: every `MaybeUndefined` field becomes absent. `SessionMessageChunk._meta` becomes `null`.
  - Every field of `SubagentSessionCapabilities`, `SessionCancelCapabilities` and `SubagentCapabilities`, each
    state's `_meta`, `ErrorStopReason.error`, `Idle.usage`, and `ClientCapabilities.subagents` itself.
  - Check what the existing `MaybeUndefined` decoding helper does with errors before reusing it: the strict fields
    must not be swallowed. Re-check each field against `client.rs` with `check-specification`.
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
  - Kotlin shape: `StateUpdate.Idle(stopReason: IdleStopReason? = null, usage: Usage? = null, _meta: JsonElement? =
    null)`; `IdleStopReason` is a sealed class with `EndTurn`, `MaxTokens`, `MaxTurnRequests`, `Refusal`,
    `Cancelled`, `Error(error: JsonRpcError? = null)` and `Other`.
  - Decoding edge cases, all from Rust: a malformed or known-but-invalid `stopReason` decodes as no stop reason.
    `Other` only takes unrecognized discriminators, so a malformed *known* state is not `Other`: through
    `DefaultOnError` on `SubagentUpdate.state` it becomes an absent `state`. `IdleStopReason.Other` keeps its fields
    except `stopReason`, `usage` and `_meta`. Test each case.
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
the child's operations to an app factory, as v2's `AgentSupport.createSession(parameters, client)` does. During the
parent's replay, the factory replays the child's history through those operations before it returns, as v2's
`resumeSession` does.

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
   - otherwise it must be the bound session or a registered, announced descendant of it, or
     `IllegalArgumentException`. "Registered" means not yet removed by subtree cleanup; a child whose feed has ended
     still counts.
   - If the bound session is itself a child, it must already be announced, or `IllegalStateException`. Otherwise a
     factory could announce a grandchild before its own announcement and put traffic on an unannounced stream.
3. Check that `sessionId` is not registered on the connection, root sessions included, or
   `IllegalArgumentException`.
4. Register a `SubagentWrapper` (below) under the parent, and call `factory.createSubagent(context)` in the child's
   context.
5. **Enforce the announcement first.** The first thing sent for the child must be a `SubagentUpdate` whose
   `sessionId` is the child's, whether it goes through `context.client.notify` inside the factory or comes as the
   first item of `events`. Both paths go through the child's operations, so it's one check in one place.
   - Until the announcement has been sent, the child's client operations (permission, fs, terminal, elicitation,
     `notify` of anything else) fail with `IllegalStateException`, so nothing about the child can overtake it.
   - If **nothing has been sent yet** and the factory throws, or `events` completes or throws before emitting, or the
     first item of `events` isn't the child's `SubagentUpdate`: stop, unregister the child, send nothing more, and
     make `announce` throw `IllegalStateException`. The first-item check applies only when the factory sent nothing.
     This includes a `CancellationException` from the app's `events` while the collector's job is active (see "Feed
     end and failure"). `announce` must never pass it on as a `CancellationException`: the caller's coroutine is
     still active, and a foreign one would tear down its scope, usually the parent's prompt flow. That's the bug
     class #131 fixed for a peer's `-32800`.
   - If the factory throws **after** the announcement went out, the child exists on the wire, as in the cancelled
     wait of step 7. A `CancellationException` from a cancelled caller is handled the same way.
     - The child stays registered until subtree cleanup, like a child whose feed ended: its id stays unique and it
       can still be named as a `parent`.
     - `announce` rethrows the factory's exception unchanged. No collection starts, because there is no session.
     - The child's context stays valid: `context.client` and `context.subagents` belong to the child, not to the
       factory call, so work the factory launched before throwing can keep sending.
     - `session/cancel` for the child is logged and ignored, because there is no `cancel()` to call.
     - For a live child, the SDK sends one `SubagentUpdate` for the child that sets `capabilities = null` if a
       grant is in effect, and `state = StateUpdate.Unknown` if the last state the factory sent (see `factoryState`
       in "What the SDK tracks per child") is `Running`, `RequiresAction` or `Other`. If neither applies, it sends
       nothing. The caller can't correct the child itself: a second `announce` fails on the registered id, and the
       root's `notify` rejects any `SubagentUpdate`. The client also learns that cancel no longer works, because
       `session/cancel` is a notification and an ignored cancel can't report an error. Send it from the root's
       supervisor scope, so a cancelled caller doesn't prevent it, and wrap it in `runCatching`, as the feed-end
       `unknown` is. If that scope is already cancelled, nothing is sent.
     - During the parent's replay window, the SDK sends nothing: history grants are never in effect, and the client
       treats the child's state as unconfirmed after the load anyway.
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
- In a root session's context, `session_message` and `session_message_chunk` fail with `IllegalStateException`
  ("Client does not support subagents capability") when the client didn't advertise `subagents`. The RFD says a v1
  agent MUST NOT send the updates it defines without the capability (`:76-78`).
  - The check sits in the root's `RemoteClientSessionOperations.notify`. `client.notify`, the prompt flow's
    `Event.SessionUpdateEvent` (`agent/Agent.kt`, `SessionWrapper.prompt`) and replay all go through it, so one
    check covers every path. It follows the fs, terminal and elicitation checks in the same file, which use
    `error(...)` with the same message format.
  - It throws rather than dropping with a log: the app made an explicit call that breaks a MUST NOT, and a silent
    drop would hide the bug and lose content the app meant to show. Inside a prompt flow the throw fails the turn.
    The app checks first with `currentCoroutineContext().subagents != null`.
  - Children need no check, because a child exists only when the client advertised `subagents`. `subagent_update`
    needs none either: a root's `notify` always rejects it (above).
  - The KDoc of `SessionUpdate.SessionMessage` and `SessionUpdate.SessionMessageChunk` says they need the
    `subagents` client capability.
- The SDK doesn't validate or rewrite patch contents.
- Each send goes through `RemoteClientSessionOperations`/`Protocol` immediately, in emission order, so per-session
  ordering holds.

### What the SDK tracks per child (`SubagentWrapper`)

- **`grant`:** whether cancel is currently granted. It's updated from each `SubagentUpdate` for the child that went
  out live:
  - `capabilities` omitted: unchanged;
  - `capabilities` `null`, or `cancel` null/absent: no grant;
  - `cancel` non-null: granted.

  The grant is recorded **before** the update is sent, so a `session/cancel` that the client sends right after
  receiving the grant is routed.

  The initial value is no grant. Only live updates count; see "Replay" for history. Updates from every path count
  (`events`, `context.client.notify`, the context's `client`), because the grant decides which `session/cancel`
  the client may send.
- **`factoryState`:** the last `state` snapshot the child's operations sent while the factory was running, from any
  path. Used only when the factory throws after the announcement (see "`announce` semantics", step 5).
- **`state`:** the last `state` snapshot sent **through `events`**: none, a state, or cleared. Used only to decide
  whether a feed that ends owes `unknown`. State sent through `client.notify` is not seen. This follows the existing
  runtimes: v1's `SessionWrapper.prompt` and v2's `runTurn` (`terminalIdleSent`) inspect only the flow they collect,
  and `client.notify` goes straight to the wire.
- **Its parent and children,** for nesting checks and subtree cleanup (see "Lifetimes").
- **The child's context.** `SubagentWrapper` extends `Agent.BaseSessionWrapper`, so `.agent` works in the child's
  context. `CoroutineContext.client` doesn't work yet: today it casts to `Agent.SessionWrapper` and reads its
  `clientOperations` (`agent/Agent.kt:582`), and `BaseSessionWrapper` has no `clientOperations`. Make the accessor
  resolve a `SubagentWrapper` too, for example by moving the operations to a shared supertype that NES sessions
  don't implement. `.subagents` needs the same. The child's `client` is a `RemoteClientSessionOperations` for the
  child's id, gated until the announcement is sent, and applies the routing rule.

`Agent` keeps one table per connection, from the child id to its `SubagentWrapper`, next to `_sessions`.

### Incoming requests on a child id

- **`session/cancel`:**
  - If the id is a child whose `grant` is in effect, call `AgentSubagentSession.cancel()` in the child's context,
    inline in the notification handler as the root's `cancel()` is. If it throws, log the exception; a notification
    has no response to fail.
  - Otherwise log and ignore, since a notification can't get an error. This includes a child whose factory threw
    after announcing it: there is no session to call, and the SDK has already revoked the grant on the wire.
  - The SDK doesn't stop the collection, and doesn't report `cancelled` itself. The app reports the actual outcome
    (`:1007-1013`).
  - Cancel is still routed after the feed has ended, because `unknown` doesn't revoke a grant (`:780-783`).
- **Every other session method on a child id** fails with an explicit "session X is a subagent" error through
  `acpFail`. That covers `session/prompt`, `set_mode`, `set_model`, `set_config_option`, `close`, `delete`, `fork`,
  `load` and `resume` (the RFD's mutations, `:954-958`). Today, `getSessionOrThrow` answers "not found" for these.

### Feed end and failure

- If the collection **completes or throws** while the last `state` is `StateUpdate.Running`,
  `StateUpdate.RequiresAction` or `StateUpdate.Other` (an unrecognized state), send
  `SubagentUpdate(sessionId = child, state = StateUpdate.Unknown)`. Log the exception at `warn`: the SDK is
  recovering as the spec intends, and the app's work didn't necessarily fail.
  - Use `runCatching` around the send, as `reportIdle` does in `v2/Agent.kt`: a dead protocol must stay silent.
  - A thrown flow means the SDK lost its view of the child, not that the work failed. In a relay the native child
    may still be running. `idle(error)` would invent an outcome, which the RFD forbids: `unknown` is "not a work
    outcome" (`:735-737`), `error` is only for "a failure that ends the child's foreground work" (`:753-755`), and
    a lost feed is reported as `unknown` "without inventing an outcome" (`:1287`).
  - This differs from v2's `runTurn`, which reports `idle` with no stop reason when the prompt flow throws. There the
    flow is the turn, so a throw proves the turn ended. Here `events` only observes work that runs elsewhere.
  - When the app knows its own work failed, it catches the failure and emits `idle(error)` through `events` before
    the flow ends. The last state is then `idle`, so the SDK sends nothing. Document this in the KDoc. The same
    holds for `idle(cancelled)` after the app's `cancel()` stopped the work.
- If the last `state` is `StateUpdate.Idle`, `StateUpdate.Unknown`, none or cleared, send nothing.
- If the collection is **cancelled** because the parent closed or the connection ended, send nothing (`:1029-1033`).
- A `CancellationException` from `events` counts as cancelled only if the collector's own job is no longer active.
  If the job is still active, the exception came from the app's flow (for example an uncaught `withTimeout`, or a
  channel cancelled elsewhere), and it's a feed failure like any other: log it, send `unknown` under the rule above,
  and let the collector complete normally without rethrowing.
  - The question is not who threw it, but whether the client can still see the child. When the SDK cancels the
    collection, the child is being torn down with its parent or the connection, and the RFD needs no terminal
    notification for that (`:1117-1120`). When the app's flow ends while the parent is alive, the client still shows
    the child, and the RFD says a lost feed on a live connection is reported as `unknown` (`:1375-1376`).
  - The SDK doesn't send `idle(cancelled)` for it, even after a routed `session/cancel`. On the client, a child's
    `idle(cancelled)` marks its tool calls cancelled and answers its pending permission and elicitation requests
    (`:1103-1106`, `:1132-1134`). For a relay, a dead feed doesn't show that the native child stopped, and the RFD
    says to report `unknown` "rather than inventing a cancelled outcome" (`:1098-1101`). An app that knows its work
    was cancelled emits `idle(cancelled)` through `events` before the flow ends, as it does for `idle(error)`.
    Document both in the KDoc.

  ```kotlin
  try {
      session.events.collect { send(it) }
  } catch (e: CancellationException) {
      if (currentCoroutineContext().isActive) feedFailed(e) else throw e
  } catch (e: Throwable) {
      feedFailed(e)
  }
  ```
- After the feed ends, the child stays registered: its id stays unique, cancel routing still works, and it can still
  be named as a parent. It's removed only by subtree cleanup.

### Lifetimes

- Each root `SessionWrapper` owns a supervisor scope for its subtree's collections. It's cancelled when the root
  session is closed, after `AgentSession.close()` returns, and when the connection ends. One child's collection
  failing doesn't affect others.
- The root gets that scope from an internal `Protocol` method, so the connection's end cancels it without a hook:

  ```kotlin
  /**
   * Creates a scope for work that belongs to this connection but outlives any single request.
   * It is cancelled when the protocol closes.
   */
  internal fun newConnectionScope(name: String): CoroutineScope =
      CoroutineScope(scope.coroutineContext + SupervisorJob(protocolJob) + CoroutineName(name))
  ```

  - It's parented on `protocolJob`, which `close()` cancels and which the read loop's end closes (`Protocol.kt:151`).
    That covers an explicit close, the transport's end and the cancellation of the parent scope.
  - It uses the dispatcher of the scope the app passed to `Protocol`, not the handler dispatcher. An in-process
    child's `events` is often the work itself and may run long or do CPU work. On the single handler thread it would
    delay every handler on the connection, `session/cancel` included, and serialize all children with each other.
    No guarantee depends on that thread: per-child order comes from the sequential collection, and the ordering
    points (`announce` returning, grants recorded before the send) are suspensions and atomic state. Using the
    app's dispatcher also keeps test schedulers in effect.
  - It's `internal` because `Agent` is in `:acp` with `Protocol`. It carries no ACP knowledge.
- **Subtree cleanup** is what the SDK does for every descendant of a closed root, at every depth:
  1. cancels the collection of its `events` (one cancel of the root's supervisor scope). No `unknown` is sent,
     because the feed ended by cancellation. For an in-process child whose flow is the work, this also stops the
     work. For a relay it only stops forwarding;
  2. removes it from the child table: its id is free, `session/cancel` for it is ignored, and it can't be named as
     a `parent`;
  3. makes its `AgentSubagents` and client operations fail with `IllegalStateException`.

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
  - **The connection ends:** subtree cleanup without calling the app's `close()`. Only step 1 needs work, and the
    connection scope's cancellation does it. Steps 2 and 3 follow on their own: no request can arrive any more, and
    sends fail on a closed protocol. Today v1 calls no app code on disconnect, for roots either. A "connection
    closed" hook for apps would be a separate change for roots and children together.
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
  - `afterResponse` first launches the replayed children's collections into the root's connection scope, without
    waiting for them, and then calls the root's `postInitialize()`. In the other order, a slow `postInitialize()`
    would keep every child's state unconfirmed and its cancel disabled on the client (`:972-978`).
  - A child's fresh `SubagentUpdate`s and the parent updates from `postInitialize()` share the parent's stream and
    may interleave. The SDK orders only each sender's own updates, and both come after the response.
  - If `postInitialize()` throws, the started collections keep running. If the protocol is closing, the launch into
    the cancelled scope does nothing.
  - If the response never reaches the transport, `afterResponse` doesn't run (`Protocol.kt:533`), and the replayed
    children stay registered with no collection. The root session also stays registered in that case today, so
    children follow it.
  - `session/new` and `session/resume` have no replayed children, so this order applies only to `session/load`.
- If the load fails, drop every registration made during the attempt.
- Children of a resumed parent (`session/resume`, no replay) are announced live, for example from `postInitialize`.
  Snapshots sent before a resume response are current observations, not history (`:930-933`). The client applies
  their grants only after the resume succeeds, which needs nothing on the agent side.
- Ancestor chains: before live traffic for a child, its ancestors must be announced in parent-before-child order
  during this load/resume (`:897-902`). This follows from the parent check in `announce`.
- **Forks need no SDK work.** A v1 `session/fork` replays nothing (`createSession` runs no `beforeResponse`), and
  copying history "does not itself create live child runtimes" (`:1058-1065`), so a fork starts with no children and
  the source keeps its own. If the app's fork copies child history, the app MUST remap the copied ids in message
  participant fields and in `session` tool call content, including references to the forked root. The SDK doesn't
  rewrite update contents, so the remap stays with the app. The copied children reach the wire only when the fork
  is loaded later, from its `replayHistory()` through `announce`. The duplicate-id check in `announce` catches a
  missing remap only when both trees are loaded on one connection.
  - KDoc on `AgentSupport.forkSession` (documentation only, stable API) states both points: the remap, and that
    copied children are announced from `replayHistory()` when the fork is loaded.

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
            val idle = StateUpdate.Idle(IdleStopReason.EndTurn)
            send(SessionUpdate.SubagentUpdate(ctx.sessionId, state = MaybeUndefined.Value(idle)))
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
delivery and routing details below (decision log, question 7).

### Operations

- `SubagentOperations` is a client-only interface in `com.agentclientprotocol.client` (decision log, question 20):

  ```kotlin
  @UnstableApi
  public interface SubagentOperations {
      public suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations
  }
  ```

  - `ClientSessionOperations` doesn't inherit it. The app's operations implement it to support children, and the
    client checks `operations as? SubagentOperations`, as it checks `as? FileSystemOperations` today
    (`client/Client.kt:122`). Nested children work through the same interface on the child's operations.
  - Not in `common`: `common` imports nothing from `client` or `agent` today, and `ClientSessionOperations` is also
    the agent's view of the client (`RemoteClientSessionOperations`), where the method would compile and throw. The
    fs, terminal and elicitation mixins live in `common` because both sides implement them; this one has no agent
    counterpart.
  - It's `suspend`, like `ClientOperationsFactory.createClientOperations` and every operations method.
  - It gets only the child's id, as `RemoteClientSessionOperations` does on the agent side. The operations don't hold
    their session: inside every call, the context names it, as for roots. The SDK builds the `ClientSubagentSession`
    from the result, so the child's `operations` is valid for the object's whole life (see "Types").
  - Its KDoc, and the KDoc of `ClientSubagentSession` and of the `subagents` client capability, point to each other,
    because nothing in `ClientSessionOperations` hints at it. It also warns that the parent's stream waits while it
    runs, so it must not wait for a later event of that stream (see "Routing and delivery").
  - Unlike `ClientOperationsFactory`, which is to be deprecated (see "Side findings"), this factory is needed: the
    agent creates children, so the app learns about one only when it appears.

### Types

- `BaseClientSession` is a sealed v1 interface. The name follows the `Base` prefix of `Agent.BaseSessionWrapper`.
  Its direct subtypes are the `ClientSession` interface and the `ClientSubagentSession` class.
  - Members: `sessionId`, `client`, `operations`, `subagents` (the children, named the same on both), and the
    effective `cwd`/`additionalDirectories` (inherited from the parent unless set).
  - `subagents: List<ClientSubagentSession>` lists the children in announcement order. It only grows: a child that
    failed setup is never in it (see "Failed setup"), and what close does to it is question 25. A load needs a
    closed session (question 19), so a reloaded parent is a new object with a new list; the old object's list stays
    as it was. The list lives in the parent's holder, because on the early path children are added before the
    root's `ClientSession` exists. Its KDoc says that a `subagent_update` whose child isn't in `subagents` is one
    the SDK didn't accept: the child's setup failed, or the update was rejected (see "Malformed announcements").
  - `cwd: String` and `additionalDirectories: List<String>` are computed on read: the child's own value if it has
    one, otherwise its parent's effective value (RFD `:314-320`). A root's come from its `parameters`, with
    `additionalDirectories ?: emptyList()`. The root's parameters are known when the opening call is made, so the
    holder keeps them and a child's values work on the early path too. Clearing either field with `null` makes the
    child inherit it again (`:146-148`), and the two are inherited independently.
  - `operations` is valid on both kinds for the object's whole life, because both are built after their operations
    exist: a root from the opening call, a child from `createSubagentOperations` (question 20). It's the app's link
    from a session to its own per-session object, so the app needs no map.
  - Shared KDoc lives on `BaseClientSession` and its members. Overrides add KDoc only where their behaviour differs
    in an important way.
  - A new context accessor returns `BaseClientSession`. `coroutineContext.clientSession`
    (`client/ClientSessionImpl.kt:230`) stays as it is.
  - Adding `subagents` to `ClientSession` breaks external implementations of that interface. This is accepted
    because the member is `@UnstableApi`.
- `ClientSubagentSession` has no prompt, mode, model, config or close, and no config or mode state either (decision
  log, question 8). A child's `config_option_update` and `current_mode_update` (RFD `:381-415`, `:274-276`) reach its
  `notify` like any other update, as in v2, where the client keeps no config flow and reports changes as updates.
  `ClientSessionImpl.trackState` is root-only: a child's update must never touch a root's `currentMode` or
  `configOptions`.
- `ClientSubagentSession` is a final class with an `internal` constructor, not an interface with an `Impl`
  (decision log, question 18). Only the SDK creates children, when the agent announces them; v2's `ClientSession`
  sets the precedent; and adding a member to a final class is non-breaking. The cost: app tests can't fake a child.
  The parent's consumer builds it when it reaches the child's announcement, from the operations that
  `createSubagentOperations` returned and the announcement's patch, so it never exists without operations.
  Registration creates only the internal holder. Its public members:
  - `parentSessionId: SessionId`. There's no `parent` object: on the early path a child is built while its root's
    history is delivered, before the root's `ClientSession` exists (it's built from the response), so a `parent`
    getter would have nothing to return then. The app already links the two in its own objects, because the
    parent's operations created the child's;
  - `title: String?` and `description: String?`, `null` when not reported or cleared;
  - `state: StateUpdate?`. `null` means no confirmed current state: before a fresh snapshot arrives after the
    boundary (see "History and live updates"), and after a live `state: null` patch, which the RFD defines as
    "unconfirmed, not idle" (`:146-147`). `StateUpdate.Unknown` is different: the agent confirmed that observability
    is lost. There's no last-known value: a history snapshot is never shown as current;
  - `canCancel: Boolean`, the effective cancel grant (see "Effective cancel grant");
  - `cancel()`, which throws `IllegalStateException` locally when no grant is in effect, like `Client.authStatus`.
    It checks the grant itself, so it never acts on a stale read of `canCancel`. It answers the child's pending
    permission requests (see "Pending permission requests").

  **These members, and `subagents` and the effective directories on both kinds, are plain getters, not
  `StateFlow`s** (decision log, question 21). Every change to them reaches the app as an event in a stream it
  already receives: a patch or an announcement in the parent's `notify`, applied before that `notify` runs
  (question 18), or the parent's `Opened`, at which held-back values are promoted or dropped. A flow beside the
  stream would be a second change channel with different timing: a collector on another thread could see a value
  before the event that caused it, and conflation would skip values `notify` sees. That's the mixed model question
  18 rejected, and the reason `currentMode` and `configOptions` are deprecated.
  - **Contract:** inside a `notify` of the parent's operations, the getters reflect every event delivered so far,
    including the current one. Elsewhere they return the latest applied value. Each value is read atomically, so
    any thread may read them; a computed `cwd` may combine ancestors' values from different moments.
  - A UI that wants a flow updates its own from `notify`, for example `roster.value = parent.subagents`, so it
    follows stream order.

  Both `state` and `canCancel` stay public: the SDK computes them anyway to enforce the grant and to drive pending
  permissions, and the app would otherwise have to copy the RFD's grant rules. `cancel()` is not on the base type.
  Its internal members `applyPatch` and `onParentOpened` are how the parent's consumer updates it (see "History and
  live updates").

### History and live updates (the opening boundary)

**The problem.** During `session/load` the agent replays history as `session/update` before it answers, and the
answer carries the current state. The RFD makes the successful response the freshness boundary for child work state
and grants when replay is requested (`:989-997`). Without replay (v1 `session/resume`), updates before the response
are current, but controls wait for success (`:1008-1012`). A failed attempt invalidates whatever arrived during it
(`:976-979`). The client has to know where the response fell among the updates, and v1 can't tell the app today:
the factory gets the response before the older history drains into `notify`, and the early overloads return it only
after delivering some live updates. The same gap is behind the deprecated root `configOptions`/`currentMode` (see
"Side findings").

**Public API: the response goes into the stream** (a breaking change, decision log, question 18). It follows the v1
prompt `Event` (`common/Event.kt`) and Rust's `SessionMessage::PromptResponse`:

```kotlin
public sealed class SessionEvent {
    public class Update(public val update: SessionUpdate, public val _meta: JsonElement? = null) : SessionEvent()

    /**
     * Marks the point from which this stream is live: updates before it came before the answer of the call that
     * opened the root session, and updates after it are current.
     *
     * @property response the answer of the call that opened this session, with its state at this point; `null` for
     * a subagent, which no call opens: its state starts unconfirmed and comes from later updates
     */
    public class Opened internal constructor(public val response: AcpCreatedSessionResponse?) : SessionEvent()
}

// ClientSessionOperations
public suspend fun notify(event: SessionEvent)

// A non-overridable extension, so agent code keeps calling client.notify(update)
public suspend fun ClientSessionOperations.notify(notification: SessionUpdate, _meta: JsonElement? = null) {
    notify(SessionEvent.Update(notification, _meta))
}
```

- `SessionEvent` lives in `common` next to `Event`, and is stable API, because a stable signature uses it.
- **One rule for every stream:** every operations object, root or child, gets exactly one `Opened`, before any live
  update. Everything after it is live. If the opening call fails, no stream gets one.
- **The shared interface.** `ClientSessionOperations` is also the agent's view of the client, so the change must not
  leak there:
  - `Opened` has an `internal` constructor, so agent code can't send it. `RemoteClientSessionOperations.notify`
    throws `IllegalStateException` for it, since only an SDK bug could reach that branch. Its KDoc says only the
    client runtime delivers it.
  - The extension must not be a member with a default body: a client implementer could override the wrong
    `notify` and miss `Opened`. Agent call sites stay source-compatible but need recompiling.
- **Whether history was replayed stays internal.** It's a fact about the client's own request, the app already
  knows it, and `Opened` arrives after the events it would describe. Adding a property to `Opened` later is
  non-breaking.

**Where the response is observed: an internal `Protocol` hook.** `Protocol` runs a hook synchronously in the read
loop when a successful response arrives, before it reads the next message. It knows nothing about ACP. Like Rust's
ordered response handling, but without a barrier: Kotlin's read loop calls it inline.
- `OutgoingRequest` (`protocol/Protocol.kt:614`) gains `onResponse: ((JsonElement) -> Unit)? = null`. The entry goes
  into `pendingOutgoingRequests` before `sendFrame`, so the hook is registered before the request is sent. The batch
  path keeps `null`.
- `handleResponse` (`Protocol.kt:576`) calls it in the `JsonRpcSuccessResponse` branch only, just before
  `result.complete(...)`, inside `runCatching`: a failing hook is logged, and the caller still gets its response.
- An `internal` `sendRequestRaw(method, params, sessionId, onResponse)` overload on `Protocol`. The public override
  delegates to the same body with `null`. `RpcMethodsOperations` doesn't change.
- An `internal` typed `Protocol.sendRequest(method, request, onResponse: (TResponse) -> Unit)` in a new
  `protocol/Protocol.extensions.kt`. `RpcMethodsOperations.extensions.kt` stays generic and doesn't know
  `Protocol`. It decodes the response once, inside the raw hook, calls `onResponse` with it, and returns the same
  value. A decoding failure skips `onResponse`, and the caller sees the same error as today. The encode,
  `JsonNull`→`{}` and decode steps become an `internal` helper in `RpcMethodsOperations.extensions.kt` that both
  overloads use.
- The hook must not suspend, block or throw. Waiting on it would hold every session's inbound traffic and deadlock on
  any hook that waits for the connection.
- Rejected:
  - `await()` on the response: the caller resumes through a dispatch, after later notifications may have been
    queued;
  - `invokeOnCompletion`: it works, but the ordering is hidden, it also runs on errors and cancellation, and a
    failing handler goes to the coroutine exception handler;
  - `RequestOutcome.afterResponse`: it's for this side's reply to an incoming request, and it's launched, so
    dispatched;
  - a suspending hook: no benefit here, and it blocks the connection and can deadlock;
  - a public hook on `RpcMethodsOperations`: binary-breaking for callers and breaking for implementers, and its
    contract is easy to misuse. It can become public later as an additive overload.

**Where the marker goes.** The opening calls (new, load, resume, fork, `client/Client.kt:417-610`) use the typed
overload:
- For load and resume, the hook calls `markOpened(response)` on the root's holder. For new and fork, the id first
  appears in the response, so the hook finds the holder by `response.sessionId`, claims it and marks it in one step.
- `markOpened` enqueues `Opened(response)` into the root's queue with `trySend`, sets the root holder's atomic
  `answered` flag, and enqueues `Opened(null)` into every open descendant holder.
- The hook runs on the read loop's coroutine, not on `handlerDispatcher`, so it touches only the queues and the
  atomic registry.
- A child registered after `answered` is set gets `Opened(null)` as its first queue item, enqueued by the
  `session/update` handler at registration. The handler and the hook both run in read-loop order, so each child gets
  its marker exactly once.
- **Invariant:** when the hook runs, every descendant holder is still queuing. A child's queue closes only after
  it has delivered its `Opened`, and one opening per holder means the root's queue is open whenever its response
  can arrive (see "Holders"). So the marker never has to go through the synchronous path or `lateDeliveries`.
- With a `ClientOperationsFactory`, the order is still correct: the factory runs after the response, then the drain
  delivers history, `Opened` and live updates in queue order. Only the factory's `sessionResponse` argument stays
  early, which is why the factory is to be deprecated (see "Side findings").

**Replay requested.** The opening call claims the root's holder with `claimForOpening(replayRequested)`: `true` for
`session/load`, `false` for resume, new and fork. Only a root has it; child holders read it through the root. It's
read only at `Opened`. It describes the request, not the method: in v2 it would be `replayFrom != null`.

**Applying child state, in queue order.** A child's work state and grants come from `subagent_update` patches in its
*parent's* stream. The holder of that stream, not `ClientSessionImpl`, applies them, because on the early path its
consumer runs before any `ClientSessionImpl` exists (`ClientSessionHolder.deliverEarly`). For each queued item, the
consumer applies it before handing it to the prompt flow or `notify`, so the app never sees an update whose effect
isn't visible yet, and never sees state ahead of the stream. The consumer also keeps an `opened` flag that only it
writes, so no lock and no flag shared across threads is needed. Once the session is complete, delivery is
synchronous in the handler, so queue order and arrival order are the same.

| Item | Before `Opened` | After `Opened` |
|---|---|---|
| `title`, `description`, `cwd`, `additionalDirectories` | applied (the RFD lets historical data stay visible) | applied |
| `capabilities`, `state` | held back on the child | applied: `canCancel` becomes `capabilities.cancel != null`, and `state` becomes a confirmed snapshot |
| `Opened` itself | n/a | `opened = true`, then `onParentOpened(promote = !replayRequested)` on each child: drop the held-back values after a load, promote them otherwise |

If the opening call fails, nothing gets `Opened`, and the held-back values go away with the failed holders: the
root's failure cascades to every descendant (see "Holders").

A child's pending permission requests are the exception to applying in queue order: they follow wire order, at
arrival (see "Pending permission requests").

### Routing and delivery

- **Registration.** The client's `session/update` handler (`client/Client.kt:183`) calls
  `ClientSessionRegistry.deliverSessionUpdate` (see "Session registry"). That function sorts each update when it
  arrives, synchronously, before anything that can suspend, and registers a `ClientSessionHolder` for a newly
  announced child as part of it. The update is then enqueued into the parent's holder, tagged with the result (see
  "Sorting at arrival"). The parent's consumer creates the `ClientSubagentSession` when it reaches the announcement
  (see "Setup"), and applies later patches just before they're delivered (see "History and live updates").
  - **Why synchronously:** `Protocol` runs each notification handler up to its first suspension point before it
    handles the next message (`protocol/Protocol.kt:431-436`, on the single-threaded `handlerDispatcher`), but it
    doesn't wait for the handler to finish. Delivering to the parent's `notify` runs app code and can suspend. If the
    holder were created after that, the child's next update would go to `globalSessionUpdateHandler` as
    "unconnected", and its next request would fail with "session not found".
  - **Layering:** the check lives in `ClientSessionRegistry`, which `Client`'s handler calls with the decoded
    `SessionNotification`, and which already creates speculative holders. `Protocol` learns nothing about
    `SessionUpdate` types. Its only addition is the ACP-agnostic internal response hook (see "History and live
    updates"), and its only ACP-specific knowledge stays `$/cancel_request` and the caller-supplied `SessionId` on
    outgoing requests.
  - **Guards:**
    - the lookup and the sorting are functions that aren't `suspend`, so the compiler keeps them from suspending,
      and a comment on `deliverSessionUpdate` says why they must stay that way;
    - a regression test (see "Tests");
    - the ordering guarantee written into the KDoc of `RpcMethodsOperations.setNotificationHandlerRaw`
      (implemented at `Protocol.kt:321`) and of the `setNotificationHandler` extension
      (`RpcMethodsOperations.extensions.kt:124`), so it's a stated contract
      rather than a comment in the read loop. Note there that an `additionalContext` with a dispatcher would break
      it, because `withContext` dispatches before the handler runs.
- **Sorting at arrival.** For an update on parent `P`'s stream, the handler finds `P`'s holder as today (it may
  create a speculative one; with none, the update is unconnected and nothing is registered). It then gives the
  update one tag:

  | Tag | When | Parent's consumer |
  |---|---|---|
  | `Patch(update, child)` | a `subagent_update` whose `sessionId` is in `P`'s children, failed or not | applies the patch to the child object, then delivers the update; if the child's setup failed, there's no object, and it only delivers it |
  | `Announcement(update, child)` | a `subagent_update` whose `sessionId` has no blocking holder (see "Holders"); `registerChild` creates `child` | runs "Setup" with the holder from the tag |
  | `Update(update)` | every other update, and a rejected `subagent_update` | delivers it |

  - The consumer acts on the tag and never reads the session map again. It runs much later than the handler (it
    waits for the factory, for the app's `notify`, and on the factory path for the response), and the map changes
    meanwhile. For example: an announcement for an id with a speculative holder is rejected; if that holder were
    reaped before the consumer reached the announcement, a consumer that checked again would see a new id and
    register it off the handler thread, after the id's earlier traffic had gone to `globalSessionUpdateHandler`.
  - The handler runs on the single-threaded `handlerDispatcher`, so the tags follow arrival order, which is also
    queue order. Only the handler adds children, so the tree has one writer.
  - A second `subagent_update` for a child that the consumer hasn't built yet is already in `P`'s children, so it's
    a `Patch`. The queue order means the consumer builds the child before it applies the patch. "The first
    `subagent_update` for an unknown id announces" (RFD) works because "first" is decided at arrival.
  - Once the holder is complete, the handler delivers the item directly, through `lateDeliveries`. The tag and the
    code that handles it are the same.
- **Malformed announcements.** A child id is "unique within the ACP connection" and "MUST NOT be reused for an
  unrelated session", and the association "cannot reparent an existing session, refer to itself, or create a
  cycle" (RFD `:122-124`, `:165-169` at `c392d7e9`). The RFD doesn't say what a client does with a violation.
  - A `subagent_update` whose `sessionId` has a blocking holder and isn't in `P`'s children is rejected. It's
    delivered to `P`'s `notify` as a plain update, it isn't applied to any child, it registers nothing, and the SDK
    logs a warning. The holder that owns the id keeps its own traffic.
  - This one rule covers an update that names its own parent, an ancestor (a cycle), another parent's child (a
    reparent), a root that is open or being opened, and an id with a speculative holder (announcements precede
    child traffic, so a speculative holder means a root is being opened under that id). The tree is never walked
    to look for a cycle.
  - An unknown parent and a failed parent are already covered: nothing is registered, and the update goes the way
    of any update for that parent (see "Holders", "Creation").
  - Failing the connection was rejected: `session/update` is a notification, so there's nothing to answer, and the
    parent's other traffic is fine. Dropping the update was rejected: the app would lose the parent's view of it.
- **Setup.** When the parent's consumer reaches a child's announcement, in queue order, it:
  1. calls `createSubagentOperations(childId)` on the parent's operations;
  2. builds the `ClientSubagentSession` from the result and the announcement's patch;
  3. adds it to the parent's `subagents`;
  4. starts the child's consumer (see "Holders");
  5. delivers the announcement to the parent's prompt flow or `notify`.

  So inside the parent's `notify` for the announcement, the child exists, with its operations and its state.
  - The parent's stream waits while the factory runs, as a root's stream waits for `ClientOperationsFactory`. A
    factory that waits for a later event of the parent's own stream never returns, and the KDoc says so.
  - Meanwhile the child's holder queues its updates and its requests wait.
  - The parent's consumer is the one that has the parent's operations: on the early path it's `deliverEarly`, so
    a replayed child gets its operations as soon as its announcement is delivered; on the factory path it's the
    drain after the factory, so the child waits for its parent's answer first. The main case is `session/load` with
    a factory: the agent replays children before the response, and the factory runs only after it.
  - This holds at every depth: a grandchild waits for its child, which waits for the root.
  - Running the factory in the child's own consumer was rejected: the child would appear only after the parent's
    `notify` saw its announcement, so the app couldn't look it up there.
  - Each session's updates keep their order. Follow the single-queue rule for `ClientSessionHolder` in `CLAUDE.md`.
- **After setup.** All of the child's updates go to its `notify`. There's no prompt flow, because the client never
  prompts a child. Its permission, fs, terminal and elicitation requests go to its operations.
- **Failed setup.** If the parent's operations don't implement `SubagentOperations`, or `createSubagentOperations`
  throws, no `ClientSubagentSession` is created, and only that child's holder fails, through `failSessionHolder` as
  for a failed `ClientOperationsFactory`:
  - its queued and later updates go to `globalSessionUpdateHandler`;
  - its requests get an error;
  - the failure is logged, naming the missing interface when that's the cause;
  - the parent's `notify` still gets the announcement and later patches as plain updates, and the child never
    appears in `subagents`;
  - the parent and its other children keep working.

  A `CancellationException` from the factory while the consumer is still active counts as a failure, as
  `withEarlyInitializingSession` treats one from `notify`. A real cancellation, such as the connection ending, is
  rethrown.

  A grandchild of a failed child can't get operations, because they come from its failed parent, so its holder fails
  the same way, through the cascade in "Holders".

### Holders

Every session id, root or child, has one `ClientSessionHolder` (`client/ClientSessionHolder.kt`). Today's holder
can't host a child: `getOrCreateSessionHolderOrNull` creates one only while an opening is in progress
(`client/Client.kt:60-90`), the reaping at the end of the last opening fails every holder that hasn't completed
(`:845-866`), and the holder is typed to `ClientSessionImpl`. The rules below fix that and keep the invariant of
"History and live updates": when the root's response hook runs, every descendant holder is still queuing.

- **Type.** The holder holds a `BaseClientSession`, and request handlers look sessions up as `BaseClientSession` and
  call its `operations`.
  - Kotlin doesn't allow `internal` members in an interface (checked by compiling: "Modifier 'internal' is not
    applicable inside 'interface'"), so the sealed type can't carry the runtime's hooks.
  - The few behaviours that differ by type become internal extension functions with an exhaustive `when` over the
    sealed type: delivering an event (a root's goes to its prompt flow or `notify`, a child's to `notify`) and
    answering a permission request through the session's `PendingPermissionCancellation` (a root's) or
    `SubagentPendingPermissions` (a child's).
  - `executeWithSession` and the context element move to `BaseClientSession`.
- **One opening per holder.** `claimForOpening` succeeds only for a holder that has no owner and hasn't completed,
  for every opening overload. Opening an id that is open or being opened fails before the request with "Session …
  is already open or being opened". To load it again, close it first. The `Opener` enum goes away.
  - Why: the response hook can put `Opened` only into an open queue, and it must not suspend, so a completed holder
    can't take a second marker. Two concurrent openings of one id would race for the same queue.
  - Today a factory call may claim a holder that a factory call already owns, even a completed one
    (`ClientSessionHolder.claim`, `ClientSessionHolderTest.kt:130`). Before #146, holders had no owner, and nothing
    stopped a second opening; #146 kept that for factory calls to limit its scope. Such a reopen returns a session
    the client never registers, and the replay goes to the open session as if it were live.
  - It's a behaviour change for factory callers, so it goes into the release note of step 3 (see "Implementation
    order").
- **Creation.** A child's holder is created only by the registration in the `session/update` handler (see
  "Routing and delivery"), through its own path, not through `getOrCreateSessionHolderOrNull`. It doesn't depend on
  `initializingSessionsCount`. It needs a parent holder that hasn't failed. A speculative parent counts, because a
  new or forked session can send updates before its response. Child ids never get speculative holders, and
  malformed announcements are rejected (see "Routing and delivery").
- **The tree.** Each holder keeps its children in an atomic map keyed by id, failed children included, and closes
  it when it fails. Announcement order is kept separately, in the public `subagents`, which the consumer builds.
  `registerChild` first adds the child to its parent's children, and registers nothing if they're closed, so a
  parent that fails at the same moment can't miss a child. It then checks the id and puts the child's holder into
  the session map in one compare-and-set on the map, because openings, close and reaping change the map from other
  threads. If the id turns out to be blocked, it removes the child from the parent's children again and rejects the
  update. Only the handler adds children, so only a cascade can see that entry, and failing a holder that never
  reached the map does no harm. `markOpened`, the failure
  cascade and close (question 25) walk this list.
- **One consumer per child, until `Opened`.** Once the parent's consumer has built the child (see "Setup"), one
  consumer delivers the child's queue to its operations, setting up grandchildren the way the parent's consumer
  sets up the child. After it has delivered `Opened`, it closes the queue, delivers the rest and completes the
  holder. From then on, delivery is synchronous, and late updates wait in `lateDeliveries`, as for roots.
  - The parent's consumer launches it into a scope from `Protocol.newConnectionScope` (see "Lifetimes" on the
    agent side), which `Client` creates once. So it ends with the connection, and it doesn't run on the handler
    thread.
  - Running it in the coroutine of the `session/update` handler that registered the child was rejected (it was the
    first version of question 19). For a parent that is already open, that coroutine is the one delivering the
    announcement to the parent's `notify`, so the child's history and requests would wait until that `notify`
    returned.
  - A child registered after the root's answer finds `Opened(null)` at the head of its queue, so its consumer
    completes the holder right after delivering what's queued. A child registered before the answer, on the early
    path, gets its history as it arrives, like the root's `deliverEarly`, until the root's response adds `Opened`.
  - Requests on a child id wait until its holder completes, so during a load they wait until after the root's
    response, as the root's own requests do (`getSessionOrThrow`).
  - Switching a child to synchronous delivery as soon as it has operations was rejected: on the early path that
    happens before the root's response, and the hook would find a closed queue.
- **Reaping and failure.** Holders with a parent are exempt from the reaping in `withInitializingSession`, which
  would otherwise fail a child still waiting for its operations whenever an unrelated opening finished. A child
  holder ends only when:
  - its own setup fails: `createSubagentOperations` or its `notify` throws, or the consumer's coroutine is
    cancelled;
  - its parent's holder fails: the failure cascades down the tree, parent first, and each queue goes to
    `globalSessionUpdateHandler` through `failSessionHolder`. A speculative parent that gets reaped takes its
    children with it;
  - an ancestor closes, or the connection ends (question 25).

  **A failed child holder stays in the session map while its parent's holder is live,** so its id stays taken:
  its later patches are sorted as patches of a failed child, `getOrCreateSessionHolderOrNull` doesn't replace it
  with a speculative holder, and an opening of that id is refused under the one-opening rule. Once its parent's
  holder fails or is removed, it can be replaced like any failed holder, so a retried load can register the same
  child ids again. A holder *blocks* an id unless it has failed and isn't a child of a live holder.

### Session registry

`ClientSessionRegistry` is a new internal class in `client`. It owns the session map and every rule about which
holder owns an id, and it delivers `session/update`s from start to end.

- **Moved from `Client`:** `SessionsStorage` and `_sessions`, `getOrCreateSessionHolderOrNull`,
  `getOrCreateSessionHolder`, `removeSessionHolder`, `claimFactorySessionHolder`, `claimOperationsSessionHolder`,
  `withInitializingSession` with its reaping, `failSessionHolder` and `handleUnconnectedSessionUpdate`. The holder
  lookup in the request handlers (`client/Client.kt:236`) and in `getSessionOrThrow` (`:787`) becomes a registry
  lookup, so nothing outside it touches the map.
- **New:** `deliverSessionUpdate`, the sorting, `registerChild`, the blocking rule, the failure cascade over the
  tree, and the close order of question 25.
- **Its constructor takes the `GlobalSessionUpdateHandler?`.** `Client` passes on its `globalSessionUpdateHandler`.
  That property stays, because it's public API, but nothing else in `Client` reads it.

  ```kotlin
  internal class ClientSessionRegistry(
      private val globalSessionUpdateHandler: GlobalSessionUpdateHandler?,
  ) {
      suspend fun deliverSessionUpdate(sessionId: SessionId, update: SessionUpdate, _meta: JsonElement?) {
          val holder = getOrCreateSessionHolderOrNull(sessionId)
              ?: return handleUnconnectedSessionUpdate(sessionId, update, _meta)
          val item = queuedItemFor(holder, update, _meta)
          if (!holder.handleOrQueue(item)) handleUnconnectedSessionUpdate(sessionId, update, _meta)
      }
  }
  ```

  `Client`'s handler becomes `sessions.deliverSessionUpdate(params.sessionId, params.update, params._meta)`.
- **`Client` keeps** the protocol handlers, the public opening API, and `withFactoryInitializingSession` and
  `withEarlyInitializingSession`, which call the registry to claim and to fail holders.
- **Why a separate class:** the subagent work adds rules that are all about the map (the child path, the tree, the
  reaping exemption, the blocking rule, the cascade, the close order), and `Client.kt` is already 875 lines. They can
  be tested without a `Protocol`.
- **`QueuedItem`.** The holder's queue (`Channel<Pair<SessionUpdate, JsonElement?>>`, `ClientSessionHolder.kt:48`)
  gets an internal sealed item type in `ClientSessionHolder.kt`: `Update(update, meta)`, `Announcement(update,
  meta, child)`, `Patch(update, meta, child)` and `Opened(response)`. Step 3 adds it with `Update` and `Opened`;
  the client side adds the other two. The consumers, `failAndDrainQueue` and the direct path through
  `lateDeliveries` switch over it.

### Effective cancel grant

- The latest stored `capabilities.cancel` is non-null. `null`, `{}` and `{cancel: null}` all mean no grant, and there
  is no grant until `capabilities` has been sent. `unknown` doesn't revoke it.
- **Load or resume of a parent:** grants reset to none. Grants before `Opened` are held back (see "History and live
  updates").
  - `session/load` (replay requested): they are history and never take effect.
  - `session/resume` (no replay): they take effect at `Opened`, when the call has succeeded.
  - A failed attempt discards everything received during it.

### Pending permission requests (builds on #156)

The RFD (`c392d7e9`): when the client cancels a session's work, it MUST answer that session's pending permission
requests with `cancelled` (`:1163-1166`). A descendant's requests are cleaned up once it reports `idle` with
`cancelled`, "as if it had cancelled the descendant directly"; until then the user can still answer them, and "the
Client never denies a tool use of work that continues" (`:1169-1173`). "Idle alone is not a cancellation of
unrelated background requests" (`:1184-1185`). A v1 agent MUST report `running` or `idle` when it sees foreground
work start or stop (`:857-858`), and a cancel that races with ended work "does not rewrite that work's outcome"
(`:1132-1133`), so no new `idle` follows it. This covers `session/request_permission` only; fs, terminal and
elicitation requests are unaffected (see "Side findings" for elicitation).

| Event | Root (#156) | Child |
|---|---|---|
| Work starts | `prompt()` → `clearCancellation()` | any live `state` patch → `clearCancellation()` |
| Client cancels it directly | `cancel()` → `cancelPending()` | `cancel()` → `cancelPending()`, then `clearCancellation()` unless the child has confirmed foreground work |
| Its work is cancelled, by anyone | n/a | a live `idle` + `cancelled` → `cancelPending()`, then `clearCancellation()` |
| Ancestor closed | `close()` | `close()` on every descendant, and their holders are removed |

- **Every live `idle(cancelled)` answers the pending requests,** not only one that follows an ancestor's cancel. The
  client can't tell who cancelled the child, and doesn't need to: the snapshot confirms that the work was cancelled,
  and a late answer can't restart cancelled work (`:1187-1189`), so the requests no longer apply (`:1181-1183`). An
  `idle` with any other reason leaves them alone.
- **After the client's `cancel()`, later requests are answered `cancelled` without reaching the app only while the
  child has confirmed foreground work**, and only until its next live `state` patch of any kind. `cancel()` always
  answers the requests pending at that moment. "Confirmed foreground work" means the last live state was
  `running` or `requires_action`:
  - then a later request belongs to the work being cancelled, as for a root before its prompt answers, and the
    agent's next `idle` ends the window;
  - after `idle`, the cancel raced with ended work and no new `idle` will come, so blocking would deny every
    background request until the next `running`;
  - with an unconfirmed state or `unknown`, the client can't tell, so it doesn't deny work that may continue.
  An agent that never reports state loses later requests only while its state is confirmed as active.
- **`clearCancellation()` replaces `startTurn()`** in `PendingPermissionCancellation`, for roots and children. It
  replaces only a signal that a cancel has completed:

  ```kotlin
  /**
   * Lets later requests reach the app again after a cancel. A signal that no cancel has completed is kept, so
   * requests already waiting on it are still answered by the next cancel.
   */
  fun clearCancellation() {
      signal.update { current -> if (current.isCompleted && current !== CLOSED) CompletableDeferred() else current }
  }
  ```

  `answerUnlessCancelled` reads the signal once, when the request starts. Today's `startTurn()` always replaces
  it, so a request already waiting keeps the old signal and no later cancel reaches it. For a child, a request is
  pending exactly when `requires_action` arrives, so cancelling a child that waits for permission would break the
  MUST. For a root, it happens when an agent ends a turn with a request still waiting and `prompt()` starts the next
  one. A signal no cancel has completed is equivalent to a fresh one, so nothing else changes for roots. The name
  says what changes: the completed cancel stops applying to new requests. This is a fix to #156 on its own, so it
  lands as its own commit before step 3 (see "Implementation order").
- **A child's requests follow wire order.** The parent's consumer applies visible state in queue order (question
  18), and it can lag behind by as long as the app's `notify` takes. If permissions followed it, a `running` after
  a cancel followed by a request would get that request answered `cancelled` while the consumer is still in an
  earlier `notify`. So the sorting in `ClientSessionRegistry` drives a child's permissions at arrival, on the handler
  thread, through the child's holder from the `Patch` or `Announcement` tag. The child object may not exist yet then,
  because the consumer builds it later. `state` and `canCancel` keep their one writer, the consumer; the
  permission state isn't visible to the app, so updating it at arrival shows nothing ahead of the stream.
- **`SubagentPendingPermissions`**, an internal class, holds a child's `PendingPermissionCancellation` and an atomic
  `activeWork` flag, and owns the rules above:

  ```kotlin
  internal class SubagentPendingPermissions {
      private val cancellation = PendingPermissionCancellation()
      private val activeWork = atomic(false)

      fun onState(state: StateUpdate?, live: Boolean)  // from the sorting, at arrival
      fun onOpened(promote: Boolean)                    // from markOpened, at the root's response
      fun cancelByClient()                              // from ClientSubagentSession.cancel()
      suspend fun <T> answerUnlessCancelled(cancelledAnswer: () -> T, answer: suspend () -> T): T
      fun close()
  }
  ```

  - `onState` with `live = true`: `idle(cancelled)` calls `cancelPending()`; every patch, including `state: null`,
    then calls `clearCancellation()` and sets `activeWork` to whether the state is `running` or
    `requires_action`. With `live = false` it only remembers that value, held back as in "History and live
    updates". "Live" comes from question 18's `answered` flag, which the response hook sets in wire order.
  - `onOpened(promote)`: after a resume, `activeWork` takes the held-back value; after a load, it stays `false`.
    `markOpened` calls it on every open descendant as it walks the tree. No request is pending then, because
    requests on a child wait until its holder completes.
  - `cancelByClient()`: `cancelPending()`, then `clearCancellation()` unless `activeWork`. A cancel that races with
    a state arriving at the same moment ends with later requests blocked until the next state report, in either
    order.
  - **Ownership:** the child's holder creates it at registration, so a load or resume, which needs new holders
    (question 19), starts with a fresh one. The holder keeps a reference for the sorting and `markOpened`, and hands
    it to the `ClientSubagentSession` constructor at setup, for `cancel()` and the permission handler. The holder
    never reads it back through the child object. The child object doesn't get the holder: it would expose the
    queue, the tree and the consumer.
  - The root keeps using `PendingPermissionCancellation` directly: its only inputs are `prompt()`, `cancel()` and
    `close()`, which are local calls with no wire order to follow.

### Discovery

- Children are discovered only from announcements. There's no list or query.
- After `session/resume`, the client sees only the children the agent re-announces.
- Child history is available only through the parent's `session/load`.

## Tests

Put them in `acp` `commonTest`, unless they cross transports, in which case they go in `:acp-integration-test`. They
cover externally observable behaviour.

**Model**
- Serialization of every new type: absent versus `null`, unrecognized values, `idle` with `error`.
- `StateUpdate.Unknown` (`{"state": "unknown"}`) versus `StateUpdate.Other` (any unrecognized state).
- `idle` with `usage`, and a malformed `usage` that decodes as `null` without failing the snapshot.

**Agent**
- Capability gate: `CoroutineContext.subagents` is `null` without `subagents`, and non-null with it.
- Without `subagents`, a root's `client.notify` and its prompt flow both fail on `session_message` and
  `session_message_chunk` with `IllegalStateException`, and nothing reaches the wire. With `subagents`, both are
  sent.
- First-send rule: a factory that throws, a wrong first send or first item, an empty flow with nothing sent, or a
  flow that throws first all make `announce` throw, and nothing more reaches the wire.
- Factory failure after the announcement:
  - announced with `running` and a cancel grant, then throws: `announce` rethrows the same exception, the child
    stays registered (announcing the id again fails), one `SubagentUpdate` with `capabilities = null` and
    `state = unknown` goes out, and a later `session/cancel` is ignored;
  - announced with `idle` and no grant: nothing more is sent;
  - the caller is cancelled while the factory runs after announcing: the same revoking update still goes out;
  - during the parent's replay: nothing more is sent.
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
  - `unknown` after completion or failure while `running`, `requires_action` or an unrecognized state;
  - nothing when the active state was sent only through `client.notify`;
  - nothing when `idle`, including an `idle(error)` the app emitted before the flow threw;
  - nothing on parent close.
  - `events` throws `TimeoutCancellationException` while `running`: `unknown` goes out and the child stays
    registered; the same with `idle` as the last state: nothing is sent; closing the parent while `events` is
    suspended: nothing is sent;
  - after a routed `session/cancel`, `events` throws a `CancellationException`: `unknown`, not `idle(cancelled)`;
  - a live `events` that throws a `CancellationException` before its first item makes `announce` throw
    `IllegalStateException`, and the caller's coroutine stays active.
- Subtree cleanup:
  - collections are cancelled, ids are freed, and `AgentSubagents`/client operations fail afterwards;
  - no child update reaches the wire after the `session/close` response;
  - a `close()` that throws leaves the parent and its children registered;
  - the connection ending cleans up every subtree without calling the app's `close()`: a hot `events` that never
    completes stops being collected, both on `Protocol.close()` and on the transport's end;
  - with a `Protocol` on a multi-threaded dispatcher, a child whose `events` blocks a thread doesn't delay the
    handling of a `session/cancel` for another session. The control case is the same test with a child whose
    `events` only suspends.
- Other methods on a child id fail with the "is a subagent" error.
- Replay: history sent from the factory (including nested grandchildren) goes out before the load response, live
  `events` starts after it, history grants don't enable cancel, a history-only child with empty `events` works, and
  a replay factory that returns without announcing makes `announce` throw instead of hanging.
- Fork: forking a root with live children sends no `subagent_update` for the fork, and the source's children keep
  routing, cancel included. A later load of the fork that replays remapped children works on the same connection
  as the loaded source.
- Replay order: with a `postInitialize()` that suspends until the test releases it, a replayed child's first live
  `events` item reaches the client after the load response and while `postInitialize()` is still suspended.

**Protocol (the response hook)**
- The hook runs before a notification that follows the response, both in the same batch frame and in the next frame.
- It isn't called for an error response, a local cancel or `close()`.
- A throwing hook is logged, and the caller still gets its response.
- A decoding failure doesn't call the typed hook and reaches the caller.

**Client: the opening boundary**
- `session/load`, on the early and the factory path: updates before the answer, then exactly one
  `Opened(response)`, then live updates, in wire order. An update that arrives after the answer but before the end of
  early delivery comes after `Opened`.
- `session/new` and `session/fork`: the buffered updates, then `Opened`, then live updates.
- A failed open delivers no `Opened`, and its updates go to `globalSessionUpdateHandler`.
- A child announced during history gets `Opened(null)` at the position of the root's answer; a child announced after
  it gets `Opened(null)` as its first event; a grandchild too. Each stream gets exactly one.
- Agent side: `client.notify(update)` still sends the update; `RemoteClientSessionOperations.notify` throws
  `IllegalStateException` for `Opened` (an in-module test).

**Client**
- Every item above under "Client side", including the #156 table for children.
- Pending permissions for children:
  - a live `idle(cancelled)` answers the child's pending requests, whether or not the client cancelled anything; a
    live `idle` with another reason and an `unknown` don't;
  - `cancel()` while `requires_action` answers the pending request and blocks later ones until the next live
    state; `cancel()` while `idle`, unconfirmed or `unknown` answers the pending ones and blocks nothing;
  - a request that arrives after a live `running` reaches the app while the parent's `notify` for an earlier
    update is still blocked;
  - a history `idle(cancelled)` touches nothing; after a resume, a held-back `running` counts as active work for
    `cancel()`; after a load it doesn't;
  - `SubagentPendingPermissions` unit tests for the same rules, without a `Client`.
- `clearCancellation()` regression tests: a root request from turn 1 still waiting when `prompt()` starts turn 2 is
  answered by `cancel()`; a child request still waiting when `requires_action` arrives is answered by `cancel()`.
- Grants: after a load, a history grant never enables cancel, and a grant after `Opened` does. After a resume, a
  grant before the answer makes `canCancel` true only once `Opened` is delivered. A failed resume discards it.
- State: a history snapshot leaves `state` unconfirmed and doesn't touch pending permissions (a replayed
  `idle(cancelled)` doesn't answer a pending request); a live snapshot is confirmed.
- Inside the parent's `notify` for a live `subagent_update`, the child's `canCancel` and `state` already reflect it.
- Member types: `state` is `null` after a load until a live snapshot arrives, and again after a live `state: null`;
  a live `unknown` gives `StateUpdate.Unknown`. Clearing `title` or `description` gives `null`. `subagents` is in
  announcement order on a root and on a child.
- Effective directories: a child without its own values gets the root's `parameters` (`additionalDirectories`
  `null` gives an empty list); a child's `cwd` patch doesn't change its `additionalDirectories`; clearing `cwd`
  inherits again; a grandchild follows a later `cwd` patch of its parent; on the early path, a child read during
  the root's history already gets the root's values.
- Registration race: the agent announces a child and immediately sends `session/request_permission` for it while the
  parent's `notify` is blocked. The request reaches the child's operations instead of failing with "session not
  found". The same for the child's first `session/update`.
- A throwing `createSubagentOperations` fails only that child and its descendants. Parent operations that don't
  implement `SubagentOperations` do the same, with an error naming the interface. Either way, the child never
  appears in `subagents`, and the parent's `notify` still gets the announcement.
- `createSubagentOperations` gets the child's id. Inside the parent's `notify` for the announcement, the child is in
  `subagents`, and its `operations` is the factory's result. Inside a call to the child's operations, the context
  names the child.
- A factory that suspends delays the parent's later updates, not other sessions', and the child's updates and
  requests wait for it.
- The registration race for a parent that is already open: the parent's `notify` blocks on the announcement, and the
  child's next update and `session/request_permission` still reach the child's operations.
- Malformed announcements: a `subagent_update` naming its own parent, an ancestor, another parent's child, an open
  root and an id with a speculative holder each reach the parent's `notify`, register nothing, change no child,
  and leave the owning holder's traffic where it was.
- A second `subagent_update` for a child, sent before the parent's consumer reaches the announcement, is applied
  as a patch after setup, and the factory runs once.
- A failed child's id stays taken: a later patch for it reaches the parent's `notify` and doesn't call the factory
  again; an update on the child's id during an unrelated opening goes to `globalSessionUpdateHandler`; a
  `loadSession` of that id is refused. After a failed load is retried, the same child ids register again.

**Client: holders**
- One opening per holder: a second opening of an open session fails before its request, for factory after factory,
  factory after operations and operations after factory; so does a second opening while the first is in flight.
  After `close()`, a load of the same id works. `ClientSessionHolderTest.kt:130` changes to expect the refusal.
- A child announced on an open parent, with no opening in progress, gets a holder: its updates and requests reach
  its operations.
- A child waiting in `createSubagentOperations` survives an unrelated `newSession` that finishes meanwhile.
- A child under a speculative parent that no opening claims fails with it: the parent's queued updates, then the
  child's, go to `globalSessionUpdateHandler`.
- A failed load fails every descendant holder, and their queued updates go to `globalSessionUpdateHandler`.
- Early path: a child's history reaches its operations before the root's answer, then `Opened(null)`, then live
  updates. A child's `session/request_permission` during the load is answered only after the root's answer.
- A registry unit test, without a `Protocol`: a child registered while its parent fails is either failed by the
  cascade or not registered, never left registered under a failed parent; and a child id claimed by an opening at
  the same moment ends up with one holder.

**End-to-end**
- An agent and a client from this SDK over STDIO and WebSocket, in `:acp-integration-test`: announce, nested child,
  messages, cancel, close.

## Deliverables

- KDoc for every public type, following the `CLAUDE.md` KDoc rules.
- `apiDump` for `:acp-model` and `:acp`, run separately, with the diff reviewed. Then run `./gradlew check`.
- An update to `CLAUDE.md` ("Runtime and Lifecycle Rules") covering:
  - subagents are announced through `AgentSubagents.announce`, and the first event is the announcement;
  - the routing rule;
  - the SDK reports only `unknown`, and only from state sent through `events`;
  - subtree cleanup and its order on `session/close`;
  - the client's per-child holder, its synchronous registration and grant reset;
  - `Protocol`'s ordering guarantee for notification handlers, which the registration relies on;
  - `Protocol.newConnectionScope` for work that outlives a request, and its dispatcher;
  - `Protocol`'s internal response hook and its rules, and the `Opened` marker in the holder's single queue,
    added to the existing holder rule;
  - one opening per holder, the holder tree, the exemption from reaping, and the child's consumer that stops at
    `Opened` (see "Holders");
  - the child's operations created by the parent's consumer before the announcement is delivered, and its consumer
    in the connection scope;
  - child members are plain getters, updated by the parent's consumer before the event that changes them is
    delivered: don't add a `StateFlow` beside the stream;
  - `ClientSessionRegistry` owns the session map and `session/update` delivery; each update is sorted at arrival,
    and the consumer acts on its tag;
  - a child's pending permissions follow wire order through `SubagentPendingPermissions`, and the existing
    pending-permission rule is updated for `clearCancellation()`.
- A release note for the breaking `ClientSessionOperations.notify(SessionEvent)` change, and the samples moved to
  it. It also says that opening a session that is already open, or being opened, now fails for the factory
  overloads too, so a client must close a session before loading it again.
- KDoc for `setNotificationHandlerRaw` and the `setNotificationHandler` extension stating that ordering guarantee
  (see "Routing and delivery").
- Run the `check-specification` skill before writing the models (see "Sources").
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
- **The opening boundary in v2** (decision log, question 18): v2's `ClientSession` has the same gap, a
  `configOptions` snapshot from the response next to an `updates` flow that contains replayed history. Give it the
  same shape and names as v1's `SessionEvent`, as a separate v2 type: the payloads differ, and a shared sealed type
  would tie stable v1 to unstable v2. `updates` becomes a flow of events, the snapshot goes away, and replay is
  requested when `replayFrom != null`. New, resume and fork have separate response types, so `Opened` needs a shared
  supertype or just the state.
- **Operations on v2 sessions:** v2's `ClientSession` keeps its operations in a private constructor field
  (`client/v2/ClientSession.kt:44`), while v1 exposes `operations` on both roots and children (question 20). Decide
  in the v2 work whether v2 children follow v1 or v2 roots.

## Side findings

- **Neither v1 client cancels a cancelled session's elicitations.** The subagents RFD says the client SHOULD dismiss
  a cancelled session's elicitation controls and answer them with the `cancel` action (`:1166-1167` at
  `c392d7e9`). Only permission requests are answered on `cancel()` today, and `ElicitationSessionStore` has no
  cancel path. Children follow roots here; fix both together, separately from subagents.
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
    drain, so it needs to know where the response fell in the queue. `SessionEvent.Opened` (question 18) gives the
    app that position, so the properties stay deprecated: the app folds the stream itself.
  - **Separate bug:** `_currentMode` is a `lazy` that throws when the response has no `modes`
    (`ClientSessionImpl.kt:42`), and `trackState` writes to it on every `current_mode_update` without a guard. Config
    options are guarded by `configOptionsSupported`.

- **`ClientOperationsFactory` should be deprecated** (a follow-up PR, not this change; the plan is in a `TODO`
  comment in `client/ClientOperationsFactory.kt`). Roots don't need a factory. `session/new` and `session/fork` only
  need the client to buffer updates until the answer names the session id, and the holder does that either way. The
  factory's arguments aren't needed either:
  - `sessionId`: every operations call runs with the session in its context (the handlers in `client/Client.kt:113-176`
    wrap it in `executeWithSession`), and the early load/resume overloads rely on the caller knowing the id.
  - `sessionResponse`: it's harmful. The factory gets it before the buffered history, which is older, so state built
    from the response and then from `notify` ends up stale, as in the finding above.

  The only v1 sample ignores both (`samples/.../TerminalClientSupport.kt:165`), and v2 takes `operations` directly in
  every opening call. The fix: add `newSession` and `forkSession` overloads that take `ClientSessionOperations`, like
  the load/resume ones from #146, then deprecate the interface and the four factory overloads. This doesn't solve
  history versus live updates on its own: that's `SessionEvent.Opened` (question 18), which also keeps the factory
  path in the right order. Children keep a genuine factory, `createSubagentOperations`, because the agent creates
  them.

- **Both root runtimes treat a `CancellationException` thrown by the app's own flow as a cancellation** (a
  follow-up PR, not this change; the fix ideas are in `FIXME` comments in `SessionWrapper.prompt` in
  `agent/Agent.kt` and in `runTurn` in `agent/v2/Agent.kt`). The v1 `SessionWrapper.prompt` rethrows it, so the turn's job counts as cancelled and
  `session/prompt` answers `stopReason: cancelled` (`agent/Agent.kt:137`, `:157`). The v2 `runTurn` reports
  `idle(cancelled)` for any `CancellationException` and rethrows it (`agent/v2/Agent.kt`, the
  `is CancellationException` branch). So an uncaught `withTimeout` inside a root's flow tells the client the user
  cancelled the turn. #131 fixed only the SDK-made case: a peer's `-32800` response is now
  `AcpRequestCancelledException`, not a `CancellationException`. The fix differs per runtime:
  - v1: a CE while the turn's job is still active becomes the turn's failure, wrapped in a non-cancellation
    exception, because `Protocol` maps a thrown CE to `-32800`. The existing `isCancelRequested` logic then still
    answers `cancelled` after a client cancel.
  - v2: the job check alone would regress the client-cancel case, because v2's `cancel()` only calls the app's
    `cancel()` and never cancels the turn's job. It needs a per-turn cancel-requested flag, like v1's, and must
    keep its own "cancelled before insertion" CE apart.

## Open questions (from the implementer review)

A review of this spec as a fresh implementer found these gaps. They are not decided yet. Each has a recommendation to
start the discussion from. Resolve them one by one, write the answers into the sections above, and move each to the
decision log.

### Client side

25. **Close order on the client.** When the root closes, in what order do descendant `close()` calls and holder
    removal happen relative to the close response, and what happens to the children when the connection ends?
    *Recommendation:* close descendants bottom-up and remove their holders before `close()` returns; on connection
    end, close every session the same way.

### Spec drift since `67cde2d5`

26. **RFD changes after the pinned revision.** At `ab825390`, `docs/rfds/subagents.mdx` has three newer commits:
    - `7ec5185c` allows `tool_call/cancel` and `terminal/cancel` with a child's `sessionId` when the item advertises
      its own `capabilities.cancel`, whether or not the child grants `cancel` (`:1042-1045`). This contradicts
      "Every other session method on a child id fails" in "Incoming requests on a child id", and the client may need
      a way to send them for a child.
    - `b3f6123f` adds session tool call content and clarifies one-shot subagents.
    - `14560463` recommends `session_message` for a child's initial task.

    *Recommendation:* re-read the RFD and the Rust models with `check-specification`, move the pinned revision
    forward, and update the model, routing and client sections for each change.

After these are decided, expand the **Client** list under "Tests": grant reset on load and resume, history versus
fresh state, a nested child queued behind a pending parent, and each case above.

## Decision log

Every question below is decided. The sections above already reflect the decisions; this log records what was chosen
and why, so that a new session doesn't reopen them without a new reason.

### Rejected shapes

The design moved through several shapes before landing on the factory-based `announce` above:

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

### Decided questions

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

#### Client state and usage

8. **Read-only config for children on the client. Decided:** no config state at all, only notifications, as in v2. A child's `config_option_update` and `current_mode_update` reach its `notify` like any other update (see "Client side", "Types").
9. **Usage and cost. Decided:** no tracking. A child reports `usage` in its `idle` snapshot, and the client must
   not add up the tree (`:1071-1101`). The only work is the model field `StateUpdate.Idle.usage` (see "Model
   layer"). The `idle` snapshot with its `usage` is already visible through `ClientSubagentSession.state`, a child's
   `usage_update` reaches its `notify` like any other update, and the SDK never adds up usage across the tree.

#### Process

10. **Rebase. Done:** this branch is rebased onto the current #156 head (`9941000`), which includes #155 (see
    "Prerequisites").

#### Model names

11. **State types. Decided:** `StateUpdate` and `IdleStopReason`, as in Rust v1, with `Other` for unrecognized values
    (see "Model layer").

#### Implementer review

12. **A factory that throws after the announcement. Decided:** the child stays registered, `announce` rethrows the
    exception unchanged, no collection starts, the child's context stays valid, and `session/cancel` for it is
    ignored. For a live child, the SDK sends one update that revokes a grant in effect and sets `unknown` if the
    factory left the child active (see "`announce` semantics", step 5).
    - Unregistering was rejected: the client already knows the id.
    - Failing the cancel was rejected: `session/cancel` is a JSON-RPC notification (`CancelNotification` in
      `schema/v1/schema.json`), so it has no response to carry an error. The RFD defines no error or idempotency
      rule for an ungranted cancel (`:1069-1070`, `:1094-1095`). The client learns whether it can cancel only
      from the grant, so the SDK withdraws the grant.
    - Leaving recovery to the app was rejected: after the throw, the caller has no handle that can still send a
      `SubagentUpdate` for the child.
13. **How the agent learns that the connection ended. Decided:** each root's subtree supervisor comes from an
    internal `Protocol.newConnectionScope(name)`, parented on `protocolJob` and running on the dispatcher of the
    scope the app passed to `Protocol` (see "Lifetimes").
    - Calling the app's `close()` on disconnect was rejected. It doesn't stop the SDK's own collections, and a hot
      `events` never completes. `AgentSupport` has no `close`, and `AgentSession.close` is the unstable
      `session/close` handler, which throws by default. v1 calls no app code on disconnect today, so a hook would
      be a separate change for roots too.
    - A callback on `protocolJob` was rejected: the job's cancellation already stops the scope.
    - The handler dispatcher was rejected: a busy in-process child would delay every handler on the connection, and
      no guarantee depends on that thread.
14. **A `CancellationException` from the app's `events`. Decided:** it counts as cancelled only when the
    collector's own job is no longer active. Otherwise it's a feed failure: `unknown` if the child is active, then
    the collector completes normally (see "Feed end and failure"). Before the first item, `announce` throws
    `IllegalStateException`, never the CE.
    - Treating every CE as an SDK cancel was rejected. An SDK cancel of the collection is silent because the child
      is being torn down, so the same silence on a live parent would leave a `running` child on the client for
      good. Passing the CE through `announce` would also cancel the caller's active scope.
    - Sending `idle(cancelled)`, including after a routed `session/cancel`, was rejected: it has client-side
      effects (tool calls and pending requests), and for a relay it invents an outcome (`:1098-1101`). The app
      emits `idle(cancelled)` itself when it knows.
    - The root runtimes have the same problem. It's recorded in "Side findings" and as `FIXME` comments, for a
      follow-up PR. #131 fixed only the peer's `-32800`, through `AcpRequestCancelledException`.
15. **`session_message`/`session_message_chunk` without the capability. Decided:** a root's
    `RemoteClientSessionOperations.notify` throws `IllegalStateException` for them when the client didn't
    advertise `subagents`, as the fs, terminal and elicitation operations do for their capabilities (see
    "Routing"). Dropping them with a log was rejected: it hides an app bug that breaks a MUST NOT (`:76-78`) and
    loses content silently.
16. **The order of `afterResponse` work after `session/load`. Decided:** launch the replayed children's
    collections first, then call `postInitialize()` (see "Replay"). The reverse order was rejected: the launch
    doesn't block, but a slow `postInitialize()` would delay every child's first fresh snapshot.
17. **Forking a parent that has children. Decided:** no SDK work, a KDoc note on `AgentSupport.forkSession`, and
    a test (see "Replay"). The RFD puts the remap of copied ids on the agent (`:1058-1065`), and those ids live in
    update contents, which the SDK doesn't rewrite.
18. **History versus live updates on the client. Decided:** a breaking change puts the opening response into the
    stream as `SessionEvent.Opened`, with `ClientSessionOperations.notify(SessionEvent)`, and the SDK uses the same
    boundary for child state (see "History and live updates").
    - Why breaking: v1 hands the app the response and the updates through separate channels, so no non-breaking
      shape can place the response. The workarounds were rejected:
      - per-update history/live tags repeat the position, and are wrong for new and resume;
      - an extra "opened" callback makes every app keep the same flag, is unclear for late children, and leaves
        two copies of the response;
      - SDK-owned state for a few values is a mixed model, because `notify` still delivers their raw, possibly
        stale updates.
    - The shared interface: an `internal` constructor on `Opened` and a non-overridable `notify(update, _meta)`
      extension keep agent code unchanged. A client-only `onEvent` mixin was rejected: two update methods, one of
      them abstract but unused.
    - A child's `Opened` has a `null` response, and every stream gets exactly one. The root's response for children
      and a separate child variant were rejected.
    - Whether history was replayed stays internal, on the root's holder, set by the opening call. A public
      `Opened.historyReplayed` was rejected for now: it arrives after the events it describes, the app knows it
      already, and the protocol may refine replay (`replay_complete`, agent-client-protocol#2407; v2 replay modes).
      Recording the opening method was rejected too: the RFD distinguishes only replay versus no replay.
    - The response is observed through an internal, synchronous, success-only hook in `Protocol`, not `await()`,
      `invokeOnCompletion` or `RequestOutcome`, and not a public or suspending hook.
    - Child state is applied by the parent's consumer in queue order, before delivery, not in the handler at
      arrival: it matches the stream the app sees, and has one writer.
    - `ClientSubagentSession` is a final class with an `internal` constructor, and its `state` and `canCancel` stay
      public.
    - v2 gets the same shape as a separate type, in the v2 work.
19. **Holder lifecycle for children. Decided:** the holder holds a `BaseClientSession`; one opening per holder for
    every overload; child holders get their own creation path, live in a tree of holders, are exempt from reaping
    and end through a cascade; and each child has one consumer until it has delivered `Opened` (see "Holders"). The
    consumer first ran in the child's registration handler; question 20 moved it to a connection scope.
    - Keeping the factory re-claim from #146 was rejected: the response hook can't mark a completed holder, and the
      reopened session is never registered.
    - Letting a reopen join the open session, as the unmerged #148 proposed for JetBrains Air (which reopens a
      session when a tab is closed and opened again), was rejected: the replay would reach the open stream after its
      `Opened` and look live, and the RFD requires invalidating child state and grants on every load or resume
      (`:971-980`).
    - Letting a reload replace the open session (detach the old one without `session/close`, then open a fresh
      holder) was considered and not chosen: the strict rule is simpler, and a reload can be added later as an
      explicit API.
    - Internal members on `BaseClientSession` were rejected because Kotlin forbids them in interfaces.
20. **How the client gets a child's operations. Decided:** a client-only `SubagentOperations` with
    `suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations`, which the parent's
    consumer calls when it reaches the announcement; the SDK builds the `ClientSubagentSession` from the result, so
    `operations` stays on `BaseClientSession` and is always valid (see "Operations", "Types" and "Routing and
    delivery"). This also answers the former question 23: the operations are created in the parent's consumer,
    before the parent's `notify` sees the announcement. Objects are never reused across loads, because a load needs
    a closed session (question 19).
    - Inheriting it in `common.ClientSessionOperations`, as the earlier notes decided
      (`2026-11-05-v1-subagents-design.md`), was rejected: `common` would import from `client`, and agent code could
      call it on `RemoteClientSessionOperations` and get `NotImplementedError`.
    - The child as the factory's argument was rejected: the child would exist before its operations, so either its
      `operations` getter would be filled in late and throw inside the factory, or it would have no getter and the
      app would keep a map. With the id, the operations get their session from the context, as roots do.
    - Running the factory in the child's own consumer was rejected: the child would appear after the parent's
      `notify` saw its announcement. The cost of the chosen order is that the parent's stream waits for the
      factory, as a root's waits for `ClientOperationsFactory`.
    - An attach model (`child.attach(operations)`) was rejected: a child the app never attaches queues without
      bound and stalls the agent. Delivering the whole tree to the root's operations was rejected: every call would
      dispatch on an implicit context value.
    - The child's consumer moved from its registration handler's coroutine to a scope from
      `Protocol.newConnectionScope`: for an open parent, that handler is busy in the parent's `notify`.
21. **Types of the client members. Decided:** plain getters: `subagents: List<ClientSubagentSession>` in
    announcement order, only growing; `title`/`description: String?`; `state: StateUpdate?`, with `null` for no
    confirmed current state and no last-known value; `canCancel: Boolean`; `cwd: String` and
    `additionalDirectories: List<String>` computed on read from the tree; and `parentSessionId` instead of `parent`
    (see "Types").
    - `StateFlow`s, the earlier recommendation, were rejected: every change already arrives as an event in the
      parent's stream, applied before delivery, and a flow would be a second channel that can run ahead of the
      stream and conflate. This changes `canCancel` from the `StateFlow<Boolean>` carried over from the earlier
      notes.
    - A plain getter for the directories and flows for the rest, which was the first version of this answer, was
      rejected as inconsistent: the argument for one holds for all.
    - An explicit confirmed/unconfirmed wrapper for `state` was rejected: there's no last-known value to wrap, so
      `null` says it.
    - `parent: BaseClientSession` was rejected: on the early path the root's object doesn't exist yet when its
      children are built.
22. **Malformed or abusive announcements. Decided:** each `subagent_update` is sorted at arrival into a patch, an
    announcement or a plain update, and the consumer acts on the tag; one with a blocking holder that isn't a child
    of its parent is rejected, delivered to the parent as a plain update and logged; a failed child holder keeps its
    id taken while its parent is live (see "Routing and delivery" and "Holders"). The map and the delivery move to a
    new internal `ClientSessionRegistry`, which takes the `GlobalSessionUpdateHandler` (see "Session registry").
    - Sorting in the consumer was rejected: it runs after the map has changed, and it would register off the
      handler thread.
    - Failing the connection, and dropping the update, were rejected (see "Malformed announcements").
    - "A failed child holder is dropped and can be replaced", from question 19, was replaced: the next patch for
      that id would have announced the child again and called the factory again.
    - Private functions in `Client` were rejected in favour of the registry: they'd spread the map rules through a
      large class, and testing them would need a full `Client`.
    - Returning the holder and the item to the handler was rejected: the registry already needs the global handler
      for the cascade, so it can deliver too. `Client.globalSessionUpdateHandler` stays as public API; `Client`
      only passes it on.
24. **Pending permissions for children. Decided:** every live `idle(cancelled)` answers the child's pending
    requests; after the client's `cancel()`, later requests are blocked only while the child has confirmed
    foreground work, until its next live state; `startTurn()` becomes `clearCancellation()`, which replaces only a
    completed signal; and a child's permissions follow wire order through `SubagentPendingPermissions`, owned by its
    holder and driven by the sorting (see "Pending permission requests").
    - Answering only an `idle(cancelled)` that follows an ancestor's cancel was rejected: the client can't tell, and
      the requests of cancelled work no longer apply either way.
    - Blocking until the next `running`, the earlier recommendation, was rejected: after a cancel that raced with
      ended work no new state comes, so it would deny every background request.
    - Keeping `startTurn()` was rejected: it cuts a waiting request off from every later cancel. The name
      `rearm()` was rejected as not descriptive.
    - Driving permissions from the consumer was rejected: it lags behind the wire by the app's `notify`.
    - Keeping the child's signal only in the child object was rejected: patches can arrive before it exists. Giving
      the child object its holder was rejected: it would expose more than it needs.
