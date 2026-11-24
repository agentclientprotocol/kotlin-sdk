# v1 subagents: architecture

Status: complete. Every question in the "Decision log" at the end is decided, including the gaps an implementer
review found ("Open questions"), and the decisions are written into the sections above. This file is the only
design document for v1 subagents; the earlier discussion notes were removed, and are in commit `9d1dafb`.

## Sources

- Spec: `agent-client-protocol` at `01a54c84` (2026-11-22). The subagents RFD is Completed (2026-11-20), and its v1
  rules are on the stable v1 pages, which are normative and are what this file cites. Short forms, all in
  that repository, with line numbers at `01a54c84` (valid for any later revision until the file changes):
  - `sub:N-M` = `docs/protocol/v1/subagents.mdx`;
  - `pt:N-M` = `docs/protocol/v1/prompt-turn.mdx`;
  - `tc:N-M` = `docs/protocol/v1/tool-calls.mdx`;
  - `init:N-M` = `docs/protocol/v1/initialization.mdx`;
  - `rfd:N-M` = `docs/rfds/subagents.mdx`, only for what the stable pages do not carry;
  - `v2sub:N-M` = `docs/protocol/v2/subagents.mdx`.

  The design was written against the RFD at `c392d7e9`, then `13b76582` (Preview, v1 behind `unstable_subagents`).
- Rust v1 models at `13b76582`, behind the `unstable_subagents` feature at that revision (not re-derived at
  `01a54c84`):
  - in `agent-client-protocol-schema/src/v1/client.rs`:
    - `SessionMessageChunk` (`:210`) and `SessionMessage` (`:256`);
    - `SubagentUpdate` (`:974`);
    - `SubagentSessionCapabilities` (`:1141`) and `SessionCancelCapabilities` (`:1197`);
    - `StateUpdate` (`:1244`), `IdleStateUpdate` (`:1308`) and `IdleStopReason` (`:1420`);
    - `ClientCapabilities.subagents: Option<SubagentCapabilities>` (`:3526`, in the struct at `:3492`);
  - in `agent-client-protocol-schema/src/v1/tool_call.rs`: `ToolCallContent::Session` (`:564-565`) and
    `SessionReference` (`:598`).
- Use the `check-specification` skill to re-read the subagents pages and the Rust models before writing models or
  serializers.
  Do not rely on this summary for wire shapes.

## Prerequisites

- This branch (`eugenethedev/subagents`) is stacked on PR #156 (`9941000`, branch
  `eugenethedev/permission-cancellation-fix`). #156 makes the v1 client answer pending permission requests with
  `cancelled` on `cancel()`/`close()`, through `PendingPermissionCancellation.kt`, which is now
  `client/internal/PendingPermissions.kt`.
- #156 is based on master with PR #155 (`f858693`: one v1 turn at a time, cancel targets its own turn), so this
  branch includes #155. If #156 changes again, rebase onto its new head.
- Scope: protocol v1 only. v2 follows separately (see "Looking ahead to v2"). Do not change v2 runtimes.

## Implementation order

Read `CLAUDE.md` and this file first. Resolve "Open questions (from the implementer review)" with the user before
starting the parts they affect. All of them are decided now.
Then:

1. **Model layer** and its serialization tests. Re-read the subagents pages and the Rust models with `check-specification` first.
   Run `:acp-model:apiDump` and review the diff.
2. **Agent side:** `announce`, routing, per-child tracking, incoming requests on child ids, feed end, lifetimes and
   replay, with the agent tests.
3. **The opening boundary** (see "History and live updates"), as its own PR before the client side. Before it, as
   its own commit: the `clearCancellation()` fix to #156 (see "Pending permission requests"). The PR's first commit
   extracts `ClientSessionRegistry` from `Client` (see "Session registry"), replaces `ClientSessionImpl`'s call to
   `client.removeSessionHolder` with the close callbacks (see "Close and connection end"), and adds the
   session-request helper (see "Session registry"): no behaviour or API change, and the existing client and
   `ClientSessionHolderTest` tests pass unchanged. A second commit deprecates `ClientSession.client`. Then: the
   internal `Protocol` response hook, the `Opened` marker in the holder's queue, `SessionEvent` in
   `ClientSessionOperations.notify`, and the one-opening rule for holders (see "Holders"). It's a breaking change to
   stable v1 API and behaviour, so it needs a release note and reviewed API dumps on its own.
4. **Client side:** types, registration, setup and delivery, effective grants and pending permissions, close, with
   the client tests.
5. **End-to-end** tests in `:acp-integration-test`.
6. **Docs and checks:** the `Protocol` KDoc and `CLAUDE.md` updates, `:acp:apiDump` with a reviewed diff, then
   `./gradlew check` (see "Deliverables").

## What the spec requires (v1)

- **Opt-in.** The client advertises `clientCapabilities.subagents: {}`. Without it, the agent MUST NOT send
  `subagent_update`, `session_message` or `session_message_chunk`, including during replay (`sub:52-59`, `sub:525`).
  It sends `session` tool call content only to a client that advertised it, without the MUST NOT keyword
  (`sub:59`, `tc:357`).
- **Announcement.** The first `subagent_update` for an unknown `update.sessionId` announces a child. Later ones patch
  it. The outer `sessionId` is the immediate parent, which allows arbitrary nesting (`sub:86`, `sub:142`). The
  announcement MUST precede every request or notification bearing the child's id, including live `session_message`s
  naming it (`sub:88-95`). The agent MUST NOT guess parentage (`sub:150`). Only `update.sessionId` is required, and it
  is unique on the connection (`sub:97-101`).
- **Patches.** `title`, `description`, `capabilities`, `state`, `cwd`, `additionalDirectories` and `_meta`: omitted
  means unchanged, `null` clears, a value replaces. `capabilities` is replaced as a whole, and in v1 `_meta` is too.
  `{ "cancel": null }` and `capabilities: null` both revoke cancel (`sub:131-136`). The `_meta` rule is not on the page: it
  comes from the RFD (`rfd:139`, `rfd:141-145`) and the schema.
- **Streams.** A `subagent_update` goes on the parent's stream (outer `sessionId` = parent). Everything else about the
  child goes on the child's stream (outer = child). In v1, the child's work state exists only as
  `subagent_update.state` on the parent's stream, and nothing is duplicated (`sub:332`, `rfd:1246-1249`).
- **Work state** (`sub:330-420`). `running`, `requires_action`, `idle` (optional `stopReason`, including `error` with an
  optional JSON-RPC `error`, plus `usage` and `_meta`) and `unknown`. Unrecognized states and stop reasons are kept.
  - The agent MUST report `running`/`idle` when it observes child foreground work starting, resuming or stopping.
  - It MUST report `unknown` when observability is actually lost.
  - Work may start and stop many times (`sub:352`). The subagents page ties state reports to observed work, not to
    assignments (`sub:372-373`), so work may also start without the parent assigning anything.
  - A v1 child has no prompt and no prompt response (`sub:332`, `sub:380`).
- **Messages** (`pt:40-116`, `sub:274-285`, `sub:318-329`). `session_message` (an upsert with participant ids and links between the two views) and
  `session_message_chunk`. Each view goes on the stream of the session whose transcript it belongs to. Delivery is
  the agent's own business, and the agent reports only views it observed.
- **Restrictions** (`sub:422-436`). The client must not prompt, load, resume or close a child. `session/cancel` is
  allowed only while the child's current `capabilities.cancel` is granted. The agent ignores a `session/cancel` for a
  child without the grant, and SHOULD reject any other request that is not permitted for a child with `-32602`
  (`sub:433`).
- **Cancellation** (`sub:438-485`):
  - Cancelling work also cancels the descendant work it waits on, while background children may continue.
  - The agent reports cancellation from actual evidence: a cancellation that races with already-ended work does not
    change that work's outcome (`sub:455-456`).
  - Closing a parent cancels all descendant work and needs no terminal notification (`sub:480`, `sub:483`).
- **Replay** (`sub:493-533`):
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
  - open unions `StateUpdate` (Rust `v1::client::StateUpdate`, `client.rs:1244`) and `IdleStopReason` (Rust
    `client.rs:1420`), with the Rust names in the v1 package `com.agentclientprotocol.model`:
    - `StateUpdate`: `Running`, `Idle`, `RequiresAction`, `Unknown` (the real `unknown` state, `sub:367-370`, `sub:413-420`) and `Other`
      (an unrecognized state; keeps the discriminator and payload). Use `Other` for unrecognized values, as Rust
      does, because `Unknown` is a real state here. Note this differs from v2's Kotlin `StateUpdate`, where `Unknown`
      means unrecognized.
    - `IdleStopReason`: the v1 `StopReason` values, `Error` (with an optional JSON-RPC `error`) and `Other`.
  - **Why dedicated v1 types.** In Rust, v1 `StateUpdate` exists only for subagents: it's behind
    `unstable_subagents` and used only by `SubagentUpdate.state` (`client.rs:1013`), since v1 has no `state_update`.
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
  - `Idle` (Rust `IdleStateUpdate`, `client.rs:1308`) has the flattened stop reason, `usage: Usage?` (the existing v1
    `Usage` model; omitted or `null` means not reported) and `_meta`. In Rust, `usage` sits behind
    `unstable_end_turn_token_usage` and is deserialized with `DefaultOnError`, so a malformed `usage` becomes
    `null` without failing the snapshot. Match that, and test it.
- **New `ToolCallContent` variant:** `ToolCallContent.Session(sessionId: SessionId, _meta: JsonElement? = null)`,
  `@UnstableApi`, with the discriminator `session` (`tc:336-360`, `sub:314-316`; Rust `ToolCallContent::Session(SessionReference)`
  behind `unstable_subagents`, `tool_call.rs:564-598`). It's a display-only reference to a session that the tool
  call operates on, such as a child it spawned or waits on.
  - Upstream doesn't change stable v1: `SessionReference` is only in `schema/v1/schema.unstable.json`. Kotlin has no
    feature gates, so the variant lives in the stable sealed class, marked `@UnstableApi`, as `PlanUpdateV2`,
    `PlanRemoved`, `SessionInfoUpdate` and `UsageUpdate` do in the stable `SessionUpdate`.
  - It's a **source-breaking** addition to a stable sealed class: an exhaustive `when` over `ToolCallContent` needs
    a new branch (with an opt-in) or an `else`, and code compiled earlier throws `NoWhenBranchMatchedException` if
    it meets the variant. Only a client that advertises `subagents` can receive it. Rust's enum is
    `#[non_exhaustive]`, so Rust has no such break. It goes into the release note.
  - Leaving it out is worse: `ToolCallContent` has no fallback for unknown variants, so a client that advertises
    `subagents` would fail to decode every `tool_call` and `tool_call_update` carrying a reference, and lose the
    whole update (see "Side findings").
  - `_meta` decodes leniently (Rust `DefaultOnError`), with the same helper as the other subagent models.
  - Its KDoc says that in v1 it needs the `subagents` client capability, that the referenced session must already
    be known through session setup or an announcement, and that the reference is display-only: it grants no
    controls and says nothing about the referenced session's work.
  - `V1ToolCallContent.toV2OrNull` (`model/v2/conversion/ToolCallConversions.kt`) is exhaustive, so it gets a
    `Session -> null` branch until v2 has its own variant.
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
children use the v2 state model (`rfd:838`).

### Public API (`com.agentclientprotocol.agent`, all `@UnstableApi`)

The shape follows v2's agent API, because the RFD gives v1 and v2 subagents one ownership and directed-message model (`rfd:838`, `rfd:1236`), and the
user wants one shape for both versions. "One shape" means similar structure, not shared types. The v2 types will most likely be separate
declarations in `agent.v2`, because `SessionUpdate` and the client operations differ between versions. Keep the two
shapes as close as reasonably possible without contradicting the established patterns of each version. The SDK hands
the child's operations to an app factory, as v2's `AgentSupport.createSession(parameters, client)` does. During the
parent's replay, the factory replays the child's history through those operations before it returns, as v2's
`resumeSession` does.

```kotlin
/**
 * What the SDK gives the factory that builds a child: the child's own operations, bound to its id.
 *
 * @property client the child's operations. They apply the routing rule (see "Routing"), and fail until the child's
 * announcement has been sent, except for sending the announcement itself
 * @property subagents the child's own subagents, for announcing grandchildren
 */
@UnstableApi
public class AgentSubagentContext internal constructor(
    public val sessionId: SessionId,
    public val client: ClientSessionOperations,
    public val subagents: AgentSubagents,
)

/**
 * A subagent the app exposes to the client. Built by an [AgentSubagentFactory].
 */
@UnstableApi
public interface AgentSubagentSession {
    /**
     * The child's live work after its announcement, as the subagents spec defines it.
     *
     * A SubagentUpdate for this child goes on the parent's stream, where it patches the child and carries its work
     * state. Every other update goes on the child's own stream. The SDK collects this once, after the factory
     * returns, in the child's session context, so it should be cold (or hot with replay). For a child replayed
     * during the parent's `session/load`, collection starts after the parent's response. It may be empty for a
     * history-only child.
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
public class AgentSubagents internal constructor(/* the session it is bound to */) {
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
    ): S { /* ... */ }
}

/**
 * The subagents of the current session: a root session or a subagent. `null` when the client didn't advertise
 * `subagents`: run the child without exposing it.
 */
public val CoroutineContext.subagents: AgentSubagents?
```

- `AgentSubagentContext` and `AgentSubagents` are final classes with internal constructors, like the client's
  `ClientSubagentSession`: only the SDK creates them, the app only consumes them, and no SDK API takes either
  as input, so a final class can gain members later without breaking apps. The types the app implements,
  `AgentSubagentSession` and `AgentSubagentFactory`, are interfaces.
- The accessor is nullable instead of an `isSupported` flag, so the compiler forces the capability check. No other
  SDK API uses a support flag. It also matches the provisional v2 shape, a nullable `subagents` parameter (see
  "Looking ahead to v2"). `AgentSubagentContext.subagents` stays non-null, because a child exists only when the
  client supports subagents.

- `factory` comes before `parent`, so a call that names no parent reads `announce(id, factory)`. Because `parent` is
  the last parameter, the trailing-lambda syntax isn't available: a lambda goes inside the parentheses, wrapped in
  `AgentSubagentFactory { context -> ... }` (the SAM constructor of the `fun interface`). The app can also implement
  the factory as a named class (for example `RestoreReviewer(record)` and `StartReviewer(queue)`).
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
4. Register a `SubagentSessionWrapper` (below) under the parent, and call `factory.createSubagent(context)` in the child's
   context.
5. **Enforce the announcement first.** The first thing sent for the child must be a `SubagentUpdate` whose
   `sessionId` is the child's, and the factory must send it through `context.client.notify` before it returns, live
   and during a replay. `events` are collected only afterwards, so their first item isn't special.
   - Until the announcement has been sent, the child's client operations (permission, fs, terminal, elicitation,
     `notify` of anything else) fail with `IllegalStateException`, so nothing about the child can overtake it.
   - If **nothing has been sent yet** and the factory throws or returns: stop, unregister the child, send nothing
     more, and make `announce` throw `IllegalStateException`. This includes a `CancellationException` from the app's
     factory while the caller is active. `announce` must never pass it on as a `CancellationException`: the caller's
     coroutine is still active, and a foreign one would tear down its scope, usually the parent's prompt flow. That's
     the bug class #131 fixed for a peer's `-32800`.
   - If the factory throws **after** the announcement went out, the child exists on the wire. A
     `CancellationException` from a cancelled caller is handled the same way.
     - The child stays registered until subtree cleanup, like a child whose feed ended: its id stays unique and it
       can still be named as a `parent`.
     - `announce` rethrows the factory's exception unchanged. No collection starts, because there is no session.
       The one exception is a `CancellationException` that the app's factory throws while the caller is still
       active, the same case as for `events` above: `announce` wraps it in an `IllegalStateException` with the
       original as its cause, so it can't tear down the caller's scope. A `CancellationException` of a cancelled
       caller passes as is.
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
6. After the factory returns, start collecting `events` in the child's context, in a scope owned by the root session
   (see "Lifetimes"): right away for a live announcement, after the parent's load response for a replayed child.
7. Return the session once the factory has returned, after the announcement. This is the caller's ordering point.
   For example, the parent may then send its own `session_message` naming the child (`sub:88-93`). `announce` waits
   for nothing else: a cancelled caller reaches the factory, and is handled as a factory failure in step 5.

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
  ("Client does not support subagents capability") when the client didn't advertise `subagents`. The subagents page
  says an agent MUST NOT send those updates unless the client advertised `subagents` (`sub:59`, `init:241-242`).
  - The check sits in the root's `RemoteClientSessionOperations.notify`. `client.notify`, the prompt flow's
    `Event.SessionUpdateEvent` (`agent/internal/RootSessionWrapper.kt`, `RootSessionWrapper.prompt`) and replay all go through it, so one
    check covers every path. It follows the fs, terminal and elicitation checks in the same file, which use
    `error(...)` with the same message format.
  - It throws rather than dropping with a log: the app made an explicit call that breaks a MUST NOT, and a silent
    drop would hide the bug and lose content the app meant to show. Inside a prompt flow the throw fails the turn.
    The app checks first with `currentCoroutineContext().subagents != null`.
  - The same check covers a `tool_call` or `tool_call_update` whose `content` contains `ToolCallContent.Session`:
    the pages say an agent sends `session` content only to a client that advertised the capability, without the
    MUST NOT keyword of the three updates (`tc:357`, `sub:59`), so this check goes beyond the stated keyword. It's a
    scan of one list per tool-call update.
  - Children need no check, because a child exists only when the client advertised `subagents`. `subagent_update`
    needs none either: a root's `notify` always rejects it (above).
  - The KDoc of `SessionUpdate.SessionMessage` and `SessionUpdate.SessionMessageChunk` says they need the
    `subagents` client capability.
- The SDK doesn't validate or rewrite patch contents.
- Each send goes through `RemoteClientSessionOperations`/`Protocol` immediately, in emission order, so per-session
  ordering holds.

### What the SDK tracks per child (`SubagentSessionWrapper`)

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
  runtimes: v1's `RootSessionWrapper.prompt` and v2's `runTurn` (`terminalIdleSent`) inspect only the flow they collect,
  and `client.notify` goes straight to the wire.
- **Its parent and children,** for nesting checks and subtree cleanup (see "Lifetimes").
- **The child's context.** `SubagentSessionWrapper` extends `BaseSessionWrapper`, so `.agent` works in the child's
  context. `CoroutineContext.client` doesn't work yet: today it casts to `RootSessionWrapper` and reads its
  `clientOperations` (`CoroutineContext.client` in `agent/Agent.kt`), and `BaseSessionWrapper` has no
  `clientOperations`. Make the accessor resolve a `SubagentSessionWrapper` too, for example by moving the operations
  to a shared supertype that NES sessions don't implement (`SessionWrapper` in `agent/internal/SessionWrapper.kt` is that
  supertype now). `.subagents` needs the same. The child's `client` is a `RemoteClientSessionOperations` for the
  child's id, gated until the announcement is sent, and applies the routing rule.

`Agent` keeps one table per connection, from the child id to its `SubagentSessionWrapper`, next to `_sessions`.

### Incoming requests on a child id

- **`session/cancel`:**
  - If the id is a child whose `grant` is in effect, call `AgentSubagentSession.cancel()` in the child's context,
    inline in the notification handler as the root's `cancel()` is. If it throws, log the exception; a notification
    has no response to fail.
  - If it arrives while the child's factory still runs, there is no session to call yet. With a grant in effect,
    the SDK **latches** it and delivers it once, to `cancel()`, after the factory returns and outside the call to
    `announce`. The grant is checked again at delivery, and a cancel whose grant has been revoked meanwhile is
    dropped. A factory that throws drops the latched cancel too, because there is no session to call. The factory
    can send a grant, and the client can send `session/cancel` for it, before the factory returns, so a cancel that
    arrives then must not be lost.
  - Otherwise log and ignore, since a notification can't get an error. This includes a cancel that arrives while
    the factory runs with no grant in effect, and a child whose factory threw after announcing it: there is no
    session to call, and the SDK has already revoked the grant on the wire.
  - The SDK doesn't stop the collection, and doesn't report `cancelled` itself. The app reports the actual outcome
    (`sub:455-456`).
  - Cancel is still routed after the feed has ended, because `unknown` doesn't revoke a grant (`sub:419`).
- **Every other session method on a child id** fails with an explicit "session X is a subagent" error through
  `acpFail`. That covers `session/prompt`, `set_mode`, `set_model`, `set_config_option`, `close`, `delete`, `fork`,
  `load` and `resume`, which the page doesn't permit for a child (`sub:430`, `sub:434`). `acpFail` throws
  `AcpExpectedError`, which `Throwable.toJsonRpcError` (`protocol/Exceptions.kt`) maps to `-32602` Invalid params. So
  the agent answers the `-32602` that the page says it SHOULD send for a request that is not permitted for a child
  (`sub:433`). `Agent.checkNotSubagent` (`agent/Agent.kt`) raises it, and `getSessionOrThrow` calls it before it
  answers "not found".

### Feed end and failure

- If the collection **completes or throws** while the last `state` is `StateUpdate.Running`,
  `StateUpdate.RequiresAction` or `StateUpdate.Other` (an unrecognized state), send
  `SubagentUpdate(sessionId = child, state = StateUpdate.Unknown)`. Log the exception at `warn`: the SDK is
  recovering as the spec intends, and the app's work didn't necessarily fail.
  - Use `runCatching` around the send, as `reportIdle` does in `v2/Agent.kt`: a dead protocol must stay silent.
  - A thrown flow means the SDK lost its view of the child, not that the work failed. In a relay the native child
    may still be running. `idle(error)` would invent an outcome, which the subagents page forbids: `unknown` means the agent
    cannot currently determine the foreground activity (`sub:367-370`), `error` is only for "a failure that ends the
    child's foreground work" (`sub:380`), and a lost feed is reported as `unknown`, with no final status invented
    for the child's unfinished work (`sub:415`, `sub:420`).
  - This differs from v2's `runTurn`, which reports `idle` with no stop reason when the prompt flow throws. There the
    flow is the turn, so a throw proves the turn ended. Here `events` only observes work that runs elsewhere.
  - When the app knows its own work failed, it catches the failure and emits `idle(error)` through `events` before
    the flow ends. The last state is then `idle`, so the SDK sends nothing. Document this in the KDoc. The same
    holds for `idle(cancelled)` after the app's `cancel()` stopped the work.
- If the last `state` is `StateUpdate.Idle`, `StateUpdate.Unknown`, none or cleared, send nothing.
- If the collection is **cancelled** because the parent closed or the connection ended, send nothing (`sub:480`, `sub:483`).
- A `CancellationException` from `events` counts as cancelled only if the collector's own job is no longer active.
  If the job is still active, the exception came from the app's flow (for example an uncaught `withTimeout`, or a
  channel cancelled elsewhere), and it's a feed failure like any other: log it, send `unknown` under the rule above,
  and let the collector complete normally without rethrowing.
  - The question is not who threw it, but whether the client can still see the child. When the SDK cancels the
    collection, the child is being torn down with its parent or the connection, and the page needs no terminal
    notification for that (`sub:480`, `sub:483`). When the app's flow ends while the parent is alive, the client still
    shows the child, and the page says an agent that can no longer observe a child's work reports `unknown`
    (`sub:415`, `sub:420`).
  - The SDK doesn't send `idle(cancelled)` for it, even after a routed `session/cancel`. On the client, a child's
    `idle(cancelled)` marks its tool calls cancelled and answers its pending permission and elicitation requests
    (`sub:463`, `sub:472`). For a relay, a dead feed doesn't show that the native child stopped, and the page says
    that an agent which can no longer observe the child reports `unknown` (`sub:455`), so the SDK doesn't invent a
    cancelled outcome. An app that knows its work
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

- Each `RootSessionWrapper` owns a supervisor scope for its subtree's collections. It's cancelled when the root
  session is closed, after `AgentSession.close()` returns, when a re-opening of its id or a failed load drops it, and
  when the connection ends. One child's collection
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

  - It's parented on `protocolJob`, which `close()` cancels and which the read loop's end closes (`protocolJob` and `close()` in `protocol/Protocol.kt`).
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
  2. removes it from the session table, in the same atomic update that removes or replaces the root, so no child can
     be registered under a root that is gone: its id is free, `session/cancel` for it is ignored, and it can't be
     named as a `parent`. This update runs first, and steps 1 and 3 follow it;
  3. makes its `AgentSubagents` and client operations fail with `IllegalStateException`.

  The SDK calls no app code for the children: not `cancel()`, and there is no per-child close hook. Stopping the
  descendants' work, background children included (`sub:480`), and freeing their resources belongs to the app's
  root `AgentSession.close()`.
  - `cancel()` doesn't fit: it stops only foreground work, reports an outcome nobody needs after close, and is tied
    to the client's grant.
  - A per-child close hook was considered and deferred. v2 has no per-session close hook
    (`AgentSupport.closeSession`), and the app already owns the tree it announced. It can be added later without
    breaking anything.
- **When it runs.** In v1 only a root can be closed, since `session/close` on a child id is rejected. So subtree
  cleanup runs in these cases:
  - **`session/close` on the root** (only when the agent advertises `close`): first the app's `close()`, then subtree
    cleanup, then the response. No child *update* reaches the wire after the close is answered. Children may still
    send final updates while the app's `close()` runs, which is harmless. If `close()` throws, there is no
    cleanup: the parent stays registered (the `SessionClose` handler in `agent/Agent.kt`, which removes the root only after `close()` returned), so its children do too.
    Updates are the only guarantee. A request that a child already had in flight (permission, fs, terminal or
    elicitation), and the `$/cancel_request` the SDK sends for one whose call the cleanup cancelled, are accepted
    and may still reach the client after the response. This is documented in the KDoc of `AgentSubagents`.
    A close of an id that is neither a root nor a subagent, unknown or already closed, gets an empty result without
    any app code, since ACP makes close idempotent (`docs/protocol/v1/session-setup.mdx`, "Closing a session").
  - **`session/load` or `session/resume` of an id that is still open:** the SDK keeps accepting it, as the agent
    did before subagents, and the new root replaces the old one. Once the app's `loadSession` or `resumeSession`
    has returned, before the replay and the response, the same cleanup runs for the old root's subtree, so the
    replay can announce the same ids again and no update for an old child follows the response. The old root's turn
    and `AgentSession` are left alone: the SDK doesn't close them, so stopping the old children's work is up to the
    app. A factory that throws leaves the old root and its subtree as they were. If the new load fails, the id is
    absent afterwards, and the old root isn't restored.
  - **The connection ends:** subtree cleanup without calling the app's `close()`. Only step 1 needs work, and the
    connection scope's cancellation does it. Steps 2 and 3 follow on their own: no request can arrive any more, and
    sends fail on a closed protocol. Today v1 calls no app code on disconnect, for roots either. A "connection
    closed" hook for apps would be a separate change for roots and children together.
- A child whose feed ends doesn't cancel its descendants' collections. In a relay, they may still be running natively.
- Cancelling a child calls only that child's `cancel()`. Stopping the descendants its work waits on (`sub:459`) is
  the app's job.

### Replay (`session/load`)

The spec defines history by timing: during the parent's `session/load`, everything sent before the response is
history, and fresh snapshots come after it (`sub:508`). The app starts every child replay itself, because child
registrations don't survive a reconnect and only the app knows which children existed. So children are replayed from
the parent's `AgentSession.replayHistory()`, which marks the replay window for the whole subtree.

- The parent's `replayHistory()` calls `announce` for each recorded child, with a factory that replays:
  - the factory sends the child's announcement and recorded history through `context.client` before returning;
  - it announces recorded grandchildren through `context.subagents`, which repeats the same pattern;
  - it returns the live session, or one with empty `events` for a history-only child.
- The SDK treats grants and states sent before the parent's response as history: they don't set `grant`, and they
  don't feed the `unknown` rule.
- The replay window runs from the start of `session/load` until **the transport has accepted the load response**. It
  doesn't end in `afterResponse`: that runs a dispatch later, and the client already treats everything that follows
  the response on the wire as live. The window closes from the `onResponseQueued` hook of the load's
  `RequestOutcome`, which runs right after the transport accepted the response, before `afterResponse`.
- The SDK starts collecting a replayed child's `events` when that window closes, after the parent's load response.
  Its updates are fresh patches, not a new announcement (`sub:508`, `sub:512`).
  - Closing the window first launches the replayed children's collections into the root's connection scope, without
    waiting for them. `afterResponse` then calls the root's `postInitialize()`. In the other order, a slow
    `postInitialize()` would keep every child's state unconfirmed and its cancel disabled on the client
    (`sub:499-502`).
  - A child's fresh `SubagentUpdate`s and the parent updates from `postInitialize()` share the parent's stream and
    may interleave. The SDK orders only each sender's own updates, and both come after the response.
  - If `postInitialize()` throws, the started collections keep running. If the protocol is closing, the launch into
    the cancelled scope does nothing.
  - `session/new` and `session/resume` have no replayed children, so this order applies only to `session/load`.
- If the load fails, drop every registration made during the attempt, and the root with them. That includes a load
  whose response never reached the transport: the window then never closed, so the request's completion callback
  drops the root, and its subtree is cleaned up as on `session/close`.
- Children of a resumed parent (`session/resume`, no replay) are announced live, for example from `postInitialize`.
  Snapshots sent before a resume response are current observations, not history (`sub:507`). The client applies
  their grants only after the resume succeeds, which needs nothing on the agent side.
- Ancestor chains: before live traffic for a child, its ancestors must be announced in parent-before-child order
  during this load/resume (`sub:512`). This follows from the parent check in `announce`.
- **Forks need no SDK work.** A v1 `session/fork` replays nothing (`createSession` runs no `beforeResponse`), and
  copying history "does not itself create live child runtimes" (`rfd:1099-1106`; the stable page has no fork rule), so a fork starts with no children and
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
            // the task, as the incoming view in the child's transcript, before the child responds to it
            send(SessionUpdate.SessionMessage(task.messageId, senderSessionId = MaybeUndefined.Value(task.parentId),
                content = MaybeUndefined.Value(task.content)))
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
            subagents.announce(
                SessionId(info.id),
                AgentSubagentFactory { ctx -> NativeChild(ctx, native, info) },
                parent = info.parentId?.let(::SessionId),
            )
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

The task a parent gives a child, the first one included, SHOULD be reported as that incoming `session_message`,
before the child's response to it, and SHOULD NOT be a `user_message_chunk`, which would attribute it to the human
user (`sub:282-285`). When the agent doesn't know the sender, it omits `senderSessionId` and must not assume the
parent. A one-shot child, announced for one delegation and never given more work, conforms (`sub:146`): its
`events` ends after its `idle`, and the SDK sends nothing more (see "Feed end and failure").

## Client side

The user decided the client's type structure in the earlier discussion, and confirmed the delivery and routing
details below (decision log, question 7).

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
    (`Client.handleSessionRequest` in `client/Client.kt`). Nested children work through the same interface on the child's operations.
  - It stays a plain interface, not a `fun interface`: the client finds it by `as?` on the operations object, and a
    lambda couldn't also be the `ClientSessionOperations`.
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
  - **The context it runs in.** The call runs with the parent session in the coroutine context
    (`coroutineContext.baseClientSession`), with one exception. When `loadSession` or `resumeSession` gets ready
    `ClientSessionOperations`, the subagents announced in the root's stream before the answer are set up on the early
    path, before the root's `ClientSession` exists, so the context has no session then. That is the same as for the
    root's own `notify` calls at that point. The KDoc of `SubagentOperations` says so.
  - Unlike `ClientOperationsFactory`, which is to be deprecated (see "Side findings"), this factory is needed: the
    agent creates children, so the app learns about one only when it appears.

### Types

- `BaseClientSession` is a sealed v1 interface. The name follows the `Base` prefix of `BaseSessionWrapper`.
  Its direct subtypes are the `ClientSession` interface and the `ClientSubagentSession` class.
  - Members: `sessionId`, `operations`, `subagents` (the children, named the same on both), and the effective
    `cwd`/`additionalDirectories` (inherited from the parent unless set).
  - No `client`. `ClientSession.client` is deprecated (see "Close and connection end"), so the base type doesn't
    get it and `ClientSubagentSession` never has it.
  - `subagents: List<ClientSubagentSession>` lists the children in announcement order. It only grows: a child that
    failed setup is never in it (see "Failed setup"), and a child whose holder fails after its setup stays in it,
    marked closed (see "Reaping and failure"). The list is closed, too, when its session's holder fails or is
    closed: a setup that finishes later drops its child instead of adding it, and the list stays as it was (see
    "Close and connection end"). A load needs a
    closed session (question 19), so a reloaded parent is a new object with a new list; the old object's list stays
    as it was. The list lives in the parent's holder, because on the early path children are added before the
    root's `ClientSession` exists. Its KDoc says that a `subagent_update` whose child isn't in `subagents` is one
    the SDK didn't accept: the child's setup failed, or the update was rejected (see "Malformed announcements"),
    and that a listed child may be closed because its own stream failed or its root was closed.
  - `cwd: String` and `additionalDirectories: List<String>` are computed on read: the child's own value if it has
    one, otherwise its parent's effective value (`sub:232`). A root's come from its `parameters`, with
    `additionalDirectories ?: emptyList()`. The root's parameters are known when the opening call is made, so the
    holder keeps them and a child's values work on the early path too. Clearing either field with `null` makes the
    child inherit it again (`sub:136`), and the two are inherited independently.
  - `operations` is valid on both kinds for the object's whole life, because both are built after their operations
    exist: a root from the opening call, a child from `createSubagentOperations` (question 20). It's the app's link
    from a session to its own per-session object, so the app needs no map.
  - Shared KDoc lives on `BaseClientSession` and its members. Overrides add KDoc only where their behaviour differs
    in an important way.
  - A new context accessor returns `BaseClientSession`. `coroutineContext.clientSession`
    (`client/BaseClientSession.kt`) stays as it is. A third, `coroutineContext.clientSubagentSession`, returns
    the `ClientSubagentSession` and throws for a root session or none.
  - Adding `subagents` to `ClientSession` breaks external implementations of that interface. This is accepted
    because the member is `@UnstableApi`.
- `ClientSubagentSession` has no prompt, mode, model, config or close, and no config or mode state either (decision
  log, question 8). A child's `config_option_update` and `current_mode_update` (`sub:265-272`) reach its
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
    boundary (see "History and live updates"), and after a live `state: null` patch, which leaves the child's activity
    unconfirmed and does not report `idle` (`sub:135`). `StateUpdate.Unknown` is different: the agent confirmed that observability
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
  permissions, and the app would otherwise have to copy the page's grant rules (`sub:134`, `sub:424-433`). `cancel()` is not on the base type.
  Its internal members `applyPatch` and `onParentOpened` are how the parent's consumer updates it (see "History and
  live updates"), and `markClosed` ends it when its holder fails or its root is closed.

### History and live updates (the opening boundary)

**The problem.** During `session/load` the agent replays history as `session/update` before it answers, and the
answer carries the current state. The subagents page makes the successful response the freshness boundary for child work
state and grants when replay is requested (`sub:508`). Without replay (v1 `session/resume`), updates before the
response are current, but controls wait for success (`sub:502`, `sub:507`). A failed attempt invalidates whatever
arrived during it (`sub:503`). The client has to know where the response fell among the updates, and v1 can't tell the app today:
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
     * ### Delivery
     *
     * - A successful opening delivers exactly one [Opened] to its operations, before any live update.
     * - An opening call that fails before the agent's answer arrives delivers none.
     * - Once the answer has arrived, the call can still fail: if its caller is cancelled, or `notify` throws, while the
     *   call delivers [Opened] and the updates that arrived before the session took over. [Opened] may already have
     *   been delivered then, so treat a failed opening call as a failed opening, whatever events the operations got.
     *
     * Only the client runtime delivers it; agent code can't create one.
     *
     * @property response the answer of the call that opened this session, with its state at this point; `null` for a
     * subagent, which no call opens: its state starts unconfirmed and comes from later updates
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
- **One rule for every stream:** every operations object, root or child, gets at most one `Opened`, before any live
  update, and exactly one unless its stream fails. Everything after it is live. An opening that fails before its
  answer gets no `Opened`: the marker is delivered only after the request has returned, so a call that fails or is
  cancelled delivers none, and failing the holder drops a marker that is still queued. An opening can still fail
  *after* its answer, while the marker and the updates queued behind it are delivered (see "Delivering the
  marker"). The operations may then already have had their `Opened`, but the call still throws, and the id can be
  opened again.
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
- `OutgoingRequest` (`protocol/Protocol.kt`) gains `onResponse: ((JsonElement) -> Unit)? = null`. The entry goes
  into `pendingOutgoingRequests` before `sendFrame`, so the hook is registered before the request is sent. The batch
  path keeps `null`.
- `handleResponse` (`Protocol.kt`) calls it in the `JsonRpcSuccessResponse` branch only, just before
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
- A call that is cancelled drops its hook before the graceful wait for the peer's answer, so the hook never observes
  a response that the call no longer returns. The race stays: the response can arrive and run the hook just before
  the cancellation wins, so a call that ends with a `CancellationException` may have run its hook. The caller's
  failure path must undo what the hook did, which is why the opening calls fail their holder on every failure.
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

**Where the marker goes.** The opening calls (new, load, resume and fork in `client/Client.kt`) use the typed
overload:
- For load and resume, the hook calls `markOpened(response)` on the root's holder. For new and fork, the id first
  appears in the response, so the hook finds the holder by `response.sessionId`, claims it and marks it in one step.
- `markOpened` enqueues `Opened(response)` into the root's queue with `trySend`, moves the root holder's atomic
  `RootPhase` to the `ANSWERED` stage, and enqueues `Opened(null)` into every open descendant holder.
- The hook runs on the read loop's coroutine, not on `handlerDispatcher`, so it touches only the queues and the
  atomic registry.
- A child registered once the root is `ANSWERED` gets `Opened(null)` as its first queue item, enqueued by the
  `session/update` handler at registration. The handler and the hook both run in read-loop order, so each child gets
  its marker exactly once.
- **Invariant:** when the hook runs, every descendant holder is still queuing. A child's queue closes only after
  it has delivered its `Opened` and its parent's holder has completed (see "Holders"), and one opening per holder means the root's queue is open whenever its response
  can arrive (see "Holders"). So the marker never has to go through the synchronous path or `lateDeliveries`.
- With a `ClientOperationsFactory`, the order is still correct: the factory runs after the response, then the drain
  delivers history, `Opened` and live updates in queue order. Only the factory's `sessionResponse` argument stays
  early, which is why the factory is to be deprecated (see "Side findings").

**Delivering the marker.** The consumer of a root's queue (`deliverEarly` for ready operations, `drainHistory` for a
factory) stops at the marker and returns it without delivering it: only the opening call knows whether its request
succeeded.
- After the request has returned, the opening call delivers the marker as `SessionEvent.Opened` and then the rest of
  the queue in its own coroutine, cancellably (`completeOpening`). That is app code, so it doesn't run in
  `NonCancellable`. A cancellation of the caller, or a `notify` that throws, while it runs fails the opening like
  any failed opening: the holder fails through `failSessionTree`, the rest of the queue goes to
  `globalSessionUpdateHandler`, and the id can be opened again. This holds even though the operations may already
  have the marker. Delivering in `NonCancellable` instead would leave a cancelled caller without its session but with
  the id still taken, and would run app code uncancellably.
- A `CancellationException` that app code throws while the caller is still active becomes an
  `IllegalStateException`, so a caller never mistakes a failed opening for its own cancellation. That covers the
  operations' `notify` on every path, including the factory path's history drain, and the factory's
  `createClientOperations`. A cancellation of the caller itself passes as is.
- Before the switch to the session, every update and the marker pass the one queue with its one consumer at a time,
  so the arrival order holds by construction. After the switch, an update goes synchronously to the session, and an
  update that misses the closed queue waits in `lateDeliveries` for the drain and for the update that missed the
  queue before it.

**Replay requested.** The opening call claims the root's holder with `claimForOpening(replayRequested)`: `true` for
`session/load`, `false` for resume, new and fork. Only a root has it; child holders read it through the root. It's
read only at `Opened`. It describes the request, not the method: in v2 it would be `replayFrom != null`.

**Applying child state, in queue order.** A child's work state and grants come from `subagent_update` patches in its
*parent's* stream. The holder of that stream, not `ClientSessionImpl`, applies them, because on the early path its
consumer runs before any `ClientSessionImpl` exists (`ClientSessionHolder.deliverEarly`). For each queued item, the
consumer applies it before handing it to the prompt flow or `notify`, so the app never sees an update whose effect
isn't visible yet, and never sees state ahead of the stream. Each consumer phase passes an `opened`
value as a constant (`false` before the marker, `true` after it), so there's one writer by construction and no lock
or flag shared across threads is needed. Once the session is complete, delivery is synchronous in the handler, so
queue order and arrival order are the same.

| Item | Before `Opened` | After `Opened` |
|---|---|---|
| `title`, `description`, `cwd`, `additionalDirectories` | applied (history the client already shows can stay visible, `sub:501`) | applied |
| `capabilities`, `state` | held back on the child | applied: `canCancel` becomes `capabilities.cancel != null`, and `state` becomes a confirmed snapshot |
| `Opened` itself | n/a | `opened = true`, then `onParentOpened(promote = !replayRequested)` on each child: drop the held-back values after a load, promote them otherwise |

If the opening call fails before its answer, nothing gets `Opened`. However it fails, the held-back values go away
with the failed holders: the root's failure cascades to every descendant (see "Holders"), also when it comes after
the answer, and a child may then already have had its `Opened(null)`.

A child's pending permission requests are the exception to applying in queue order: they follow wire order, at
arrival (see "Pending permission requests").

### Routing and delivery

- **Registration.** The client's `session/update` handler (`client/Client.kt`) calls
  `ClientSessionRegistry.deliverSessionUpdate` (see "Session registry"). That function sorts each update when it
  arrives, synchronously, before anything that can suspend, and registers a `SubagentSessionHolder` for a newly
  announced child as part of it. The update is then enqueued into the parent's holder, tagged with the result (see
  "Sorting at arrival"). The parent's consumer creates the `ClientSubagentSession` when it reaches the announcement
  (see "Setup"), and applies later patches just before they're delivered (see "History and live updates").
  - **Why synchronously:** `Protocol` runs each notification handler up to its first suspension point before it
    handles the next message (`Protocol.handleIncomingFrame` in `protocol/Protocol.kt`, on the single-threaded `handlerDispatcher`), but it
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
      (implemented in `Protocol.setNotificationHandlerRaw`) and of the `setNotificationHandler` extension
      (`protocol/RpcMethodsOperations.extensions.kt`), so it's a stated contract
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
    `subagent_update` for a child `sessionId` announces that the parent created and owns the child" (`sub:86`) works
    because "first" is decided at arrival.
  - Once the holder is complete, the handler delivers the item directly, in the common case, or through the
    `lateDeliveries` chain when an earlier update still waits. The tag and the code that handles it are the same.
    **An announcement always takes the chain,** even when nothing waits: its setup may suspend in
    `createSubagentOperations`, and the updates after it must wait for the setup, as they do in the queue.
- **Malformed announcements.** A child id is "unique on the connection" and the agent "MUST NOT reuse it for an
  unrelated session" (`sub:98-100`), and a `subagent_update` "cannot move an existing session to another parent, name
  its own outer session as the child, or create a cycle" (`sub:142`). The page doesn't say what a client does with a
  violation.
  - A `subagent_update` whose `sessionId` has a blocking holder and isn't in `P`'s children is rejected. It's
    delivered to `P`'s `notify` as a plain update, it isn't applied to any child, it registers nothing, and the SDK
    logs a warning. The holder that owns the id keeps its own traffic.
  - This one rule covers an update that names its own parent, an ancestor (a cycle), another parent's child (a
    reparent), a root that is open or being opened, and an id with a speculative holder (only an update creates
    one, and announcements precede child traffic, so a speculative holder means a root is being opened under that
    id). The tree is never walked to look for a cycle.
  - An unknown parent and a failed parent are already covered: nothing is registered, and the update goes the way
    of any update for that parent (see "Holders", "Creation").
  - Failing the connection was rejected: `session/update` is a notification, so there's nothing to answer, and the
    parent's other traffic is fine. Dropping the update was rejected: the app would lose the parent's view of it.
- **Setup.** When the parent's consumer reaches a child's announcement, in queue order, it:
  1. calls `createSubagentOperations(childId)` on the parent's operations;
  2. builds the `ClientSubagentSession` from the result and the announcement's patch;
  3. adds it to the parent's `subagents`;
  4. starts the child's opening (see "Holders");
  5. delivers the announcement to the parent's prompt flow or `notify`.

  Steps 1 to 4 are `setUpSubagent`, a private method of the parent's `ClientSessionHolder`. The base holder takes the
  connection's `protocol`, the scope for the subagents' openings (`openingScope`) and the `failSessionTree` callback,
  and a `SubagentSessionHolder(sessionId, parent)` takes them from its parent. Step 4 is
  `SubagentSessionHolder.startOpening(session)`.

  So inside the parent's `notify` for the announcement, the child exists, with its operations and its state.
  - The parent's stream waits while the factory runs, as a root's stream waits for `ClientOperationsFactory`. A
    factory that waits for a later event of the parent's own stream never returns, and the KDoc says so.
  - Meanwhile the child's holder queues its updates and its requests wait for the holder. A permission request has
    joined the child's cancel race at arrival, though, so a cancel, an `idle(cancelled)` or a close still answers it
    while it waits (see "Pending permission requests").
  - If the parent failed or was closed while the factory ran, the parent's `subagents` is closed, so the child is
    neither added nor started: it is marked closed, and the failure or close that ended the parent also ends the
    child's holder. If only the child's own holder failed or was removed meanwhile, the child is added to the list,
    marked closed, and gets no opening.
  - The parent's consumer is the one that has the parent's operations: on the early path it's `deliverEarly`, so
    a replayed child gets its operations as soon as its announcement is delivered; on the factory path it's the
    drain after the factory, so the child waits for its parent's answer first. The main case is `session/load` with
    a factory: the agent replays children before the response, and the factory runs only after it.
  - This holds at every depth: a grandchild waits for its child, which waits for the root.
  - Running the factory in the child's own opening was rejected: the child would appear only after the parent's
    `notify` saw its announcement, so the app couldn't look it up there.
  - Each session's updates keep their order. Follow the single-queue rule for `ClientSessionHolder` in `CLAUDE.md`.
- **After setup.** All of the child's updates go to its `notify`. There's no prompt flow, because the client never
  prompts a child. Its permission, fs, terminal and elicitation requests go to its operations.
- **Failed setup.** If the parent's operations don't implement `SubagentOperations`, or `createSubagentOperations`
  throws, no `ClientSubagentSession` is created, and only that child's holder fails, through `failSessionTree` as
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

  The same holds for a child that was set up and whose holder fails later, for example because its `notify` throws
  before its `Opened`: its later updates go to `globalSessionUpdateHandler` and its requests get an error. Its object
  was already in the parent's `subagents`, so it stays there, **marked closed**: `canCancel` is `false`, `cancel()`
  throws, and later patches in the parent's stream are not applied to it. The list only grows, so removing it was
  rejected. The KDoc of `subagents` says that a listed child may be closed because its stream failed or its root
  closed.

  A grandchild of a failed child can't get operations, because they come from its failed parent, so its holder fails
  the same way, through the cascade in "Holders".

### Holders

Every session id, root or child, has one `ClientSessionHolder` (`client/internal/ClientSessionHolder.kt`): a
`RootSessionHolder` for a root, a `SubagentSessionHolder` for a child. Today's holder
can't host a child: `getOrCreateSessionHolderOrNull` creates one only while an opening is in progress
(`client/internal/ClientSessionRegistry.kt`), the reaping at the end of the last opening fails every holder that hasn't
completed (`withInitializingSession` there), and the holder is typed to `ClientSessionImpl`. The rules below fix that and keep the invariant of
"History and live updates": when the root's response hook runs, every descendant holder is still queuing.

- **Type.** The holder holds a `BaseClientSession`, and request handlers look sessions up as `BaseClientSession` and
  call its `operations`.
  - Kotlin doesn't allow `internal` members in an interface (checked by compiling: "Modifier 'internal' is not
    applicable inside 'interface'"), so the sealed type can't carry the runtime's hooks.
  - The few behaviours that differ by type become internal extension functions with an exhaustive `when` over the
    sealed type: delivering an event (a root's goes to its prompt flow or `notify`, a child's to `notify`) and
    answering a permission request through the session's `RootPendingPermissions` (a root's) or
    `SubagentPendingPermissions` (a child's). The request handler takes a child's `SubagentPendingPermissions` when
    the request arrives, before anything suspends, and passes the lookup of the child's session to it as the first
    step of its race (see "Pending permission requests").
  - `executeWithSession` and the context element move to `BaseClientSession`.
- **One opening per holder.** `claimForOpening` succeeds only for a holder that has no owner and hasn't completed,
  for every opening overload. Opening an id that is open or being opened fails before the request with "Session …
  is already open or being opened". To load it again, close it first. The `Opener` enum goes away.
  - Why: the response hook can put `Opened` only into an open queue, and it must not suspend, so a completed holder
    can't take a second marker. Two concurrent openings of one id would race for the same queue.
  - Today a factory call may claim a holder that a factory call already owns, even a completed one
    (`ClientSessionHolder.claim` before this change; `ClientSessionHolderTest` now pins the refusal, in `one opening call owns a holder`). Before #146, holders had no owner, and nothing
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
  it when it fails. Announcement order is kept separately, in the public `subagents`, which the parent's consumer
  builds. Both collections are in one atomic state, so one update seals both.
  `registerChild` first adds the child to its parent's children, and registers nothing if they're closed, so a
  parent that fails at the same moment can't miss a child. It then checks the id and puts the child's holder into
  the session map in one compare-and-set on the map, because openings, close and reaping change the map from other
  threads. If the id turns out to be blocked, it removes the child from the parent's children again and rejects the
  update. Only the handler adds children, so only a cascade can see that entry, and failing a holder that never
  reached the map does no harm. `markOpened`, the failure
  cascade and close walk this list.
  - The same update of the map checks that the root's holder is still the one its id maps to. The close, the
    reaping and the replacement of a failed root take the root's entry out of the map no later than its
    descendants (see "Close and connection end"), so a registration that comes after stays out of the map, and one
    that comes before is removed with them. Without it, a subagent registered between the removal and the end of
    the close would stay in the map with nothing left to remove it.
  - The close of the root is also visible through the root holder's `CLOSING` stage, which `registerChild` reads
    after the `addChild` (see "Close and connection end").
  - A failed root's descendants leave the map when the root is reaped or replaced by a new holder, so failed
    subagents of repeated failed loads don't pile up under fresh ids.
- **One opening per child, until `Opened` and its parent's completion.** Once the parent's consumer has built the
  child (see "Setup"), the child's own opening (`SubagentSessionHolder.completeOpening`, started by `startOpening`)
  delivers the child's queue to its operations, setting up grandchildren the way the parent's consumer sets up the
  child. After it has delivered `Opened`, it waits until the parent's holder has completed. Only then does it close
  the queue, deliver the rest and complete the holder. From then on, delivery is synchronous, and late updates wait in
  `lateDeliveries`, as for roots.
  - **Why it waits for the parent's holder.** The root's opening can still fail after its answer, while it delivers
    its marker and the updates queued behind it, and every holder below fails with it. A child that had already
    completed couldn't be failed that way, and would stay live under a failed root. So the later updates of the
    child stay in its queue, and its holder stays incomplete, until the parent's holder has completed. If the
    parent's holder has failed instead, the opening returns and the cascade drains the queue. This holds at every
    depth: a grandchild waits for its parent, which waits for the root.
  - The cost: a child's live updates, and the agent's requests for it, wait until the opening call of the root has
    returned and the root's queue is delivered. During that time the child is already visible in the parent's
    `subagents`, with its state live. An app whose parent's `notify` waits for a live event of a child's stream
    never returns, like any `notify` that waits for a later event of a stream. The KDoc of `ClientSubagentSession`
    says so.
  - An opening checks for cancellation (`ensureActive()`) before it takes each item, before and after `Opened`, and
    nowhere between taking an item and handing it to the operations. The failure cascade cancels openings without
    joining them (see "Reaping and failure"), so an opening that was cancelled in the middle of a `notify` leaves
    the rest of its queue for the drain. Only an item that it took just after passing the check can still reach its
    `notify`.
  - The parent's consumer starts it with `startOpening`, which launches it into `openingScope`, a scope from
    `Protocol.newConnectionScope` (see "Lifetimes" on the agent side), which `Client` creates once. So it ends with
    the connection, and it doesn't run on the handler thread. Its coroutine context carries a `SubagentOpening`
    element of the holder (see "Close and connection end").
  - Running it in the coroutine of the `session/update` handler that registered the child was rejected (it was the
    first version of question 19). For a parent that is already open, that coroutine is the one delivering the
    announcement to the parent's `notify`, so the child's history and requests would wait until that `notify`
    returned.
  - A child registered after the root's answer finds `Opened(null)` at the head of its queue, so its opening
    completes the holder right after delivering what's queued, once the parent's holder has completed. A child
    registered before the answer, on the early path, gets its history as it arrives, like the root's `deliverEarly`,
    until the root's response adds `Opened`.
  - The app sees a request on a child id only once its holder completes, so during a load that is after the root's
    opening call has returned, as for the root's own requests (`awaitSession`). A permission request is the
    exception in one respect: it joins the child's cancel race when it arrives, and the wait for the holder happens
    inside that race (see "Pending permission requests"). The other request kinds simply wait.
  - Switching a child to synchronous delivery as soon as it has operations was rejected: on the early path that
    happens before the root's response, and the hook would find a closed queue.
- **Reaping and failure.** Holders with a parent are exempt from the reaping in `withInitializingSession`, which
  would otherwise fail a child still waiting for its operations whenever an unrelated opening finished. A child
  holder ends only when:
  - its own setup fails: `createSubagentOperations` throws, or its `notify` throws before it has delivered
    `Opened`, or its opening's coroutine is cancelled. A `notify` failure after `Opened` is only logged, as for any
    live update;
  - its parent's holder fails: the failure cascades down the tree, parent first, and each queue goes to
    `globalSessionUpdateHandler` through `failSessionTree`. A speculative parent that gets reaped takes its
    children with it;
  - its root closes (see "Close and connection end").

  The cascade stops each holder's opening the way close does, through the opening state (`markOpeningStopped`), so
  an opening can't start after its holder has failed. It only cancels a running opening, and doesn't join it: it
  runs under `NonCancellable` and must not wait for the app's `notify`. A subagent's object, once built, is marked
  closed, and stays in its parent's `subagents`.

  **A failed child holder stays in the session map while its parent's holder is live,** so its id stays taken:
  its later patches are sorted as patches of a failed child, `getOrCreateSessionHolderOrNull` doesn't replace it
  with a speculative holder, and an opening of that id is refused under the one-opening rule. Once its parent's
  holder fails or is removed, it can be replaced like any failed holder, so a retried load can register the same
  child ids again. A failed root that is reaped or replaced takes its descendants out of the map with it. A holder
  *blocks* an id unless it has failed and isn't a child of a live holder.

### Session registry

`ClientSessionRegistry` is a new internal class in `client`. It owns the session map and every rule about which
holder owns an id, and it delivers `session/update`s from start to end.

- **Moved from `Client`:** `SessionsStorage` and `_sessions`, `getOrCreateSessionHolderOrNull`,
  `getOrCreateSessionHolder`, `removeSessionHolder`, the claiming of a holder for an opening call
  (`claimSessionHolder` and `claimAnsweredSessionHolder`), `withInitializingSession` with its reaping, the failing
  of a holder (`failSessionTree`) and `handleUnconnectedSessionUpdate`. The holder lookup in `Client`'s request
  handlers and in its `getSessionOrThrow` (now the registry's `awaitSession`) is a registry lookup, so nothing outside
  it touches the map.
- **Request lookup.** `awaitSession` reads the session map once and never changes it, so only updates create
  speculative holders. A request waits only for a root's holder that an opening call has claimed (`session/load`
  and `session/resume` claim before the request goes out, `session/new` and `session/fork` in the response hook)
  or for a child's holder. Anything else, no holder or a root's holder that no opening has claimed, fails at once
  with `-32002` (`JsonRpcException` with `RESOURCE_NOT_FOUND`), also when no opening is in progress.
  - The Rust SDK never waits for routing either: it answers a request for an unrouted session at once.
  - Creating a holder for a request was dropped: the wait would have no bound, because an unclaimed holder is
    reaped only when the last opening on the connection ends, and the holder would block a later announcement of a
    child with that id.
- **New:** `deliverSessionUpdate`, the sorting, `registerChild`, the blocking rule, the failure cascade over the
  tree, `subagentHolderOrNull` (the request handler's non-suspending lookup of a child's holder and its pending
  permissions),
  and `removeSessionTree` (see "Close and connection end"; the root holder's `startClosing` closes the descendants'
  permissions). `removeSessionTree`
  replaces `removeSessionHolder`.
- **Its constructor takes the `GlobalSessionUpdateHandler?`,** and also the `Protocol`, for a subagent's
  `session/cancel`, and a scope from `Protocol.newConnectionScope` for the subagents' openings. `Client` passes on
  its `globalSessionUpdateHandler`. That property stays, because it's public API, but nothing else in `Client` reads
  it.

  ```kotlin
  internal class ClientSessionRegistry(
      private val globalSessionUpdateHandler: GlobalSessionUpdateHandler?,
      protocol: Protocol,
      subagentScope: CoroutineScope,
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
- **`QueuedItem`.** The holder's queue (`queue` in `ClientSessionHolder.kt`, a `Channel<Pair<SessionUpdate, JsonElement?>>` before this change)
  gets an internal sealed item type in `ClientSessionHolder.kt`: `Update(update, meta)`, `Announcement(update,
  meta, child)`, `Patch(update, meta, child)` and `Opened(response)`, where `response` is `null` for a subagent's. Step 3 adds it with `Update` and `Opened`;
  the client side adds the other two. The consumers, `failAndDrainQueue` and the direct path through
  `lateDeliveries` switch over it.
- **A helper for session-scoped request handlers,** in `Client`. The fs and terminal handlers
  (in `client/Client.kt`, before `handleSessionRequest`) repeat the same steps: look up the session, cast its operations to the mixin or call
  `sessionMethodNotFound`, then `executeWithSession`. One helper does that, so the `BaseClientSession` lookup is
  written once:

  ```kotlin
  private inline fun <reified Ops : Any, P : AcpRequest, R : AcpResponse> handleSessionRequest(
      method: AcpMethod.AcpRequestResponseMethod<P, R>,
      crossinline sessionIdOf: (P) -> SessionId,
      crossinline call: suspend Ops.(P) -> R,
  ) {
      protocol.setRequestHandler(method) { params ->
          val session = sessions.awaitSession(sessionIdOf(params))
          val ops = session.operations as? Ops ?: sessionMethodNotFound<Ops>(method)
          session.executeWithSession { ops.call(params) }
      }
  }
  ```

  The session-scoped half of `elicitation/create` uses the same lookup and cast. Permission stays separate: it
  answers through the type-dispatched extension (see "Holders" and "Pending permission requests"). The same commit
  can tidy `elicitation/complete` (the `ElicitationComplete` handler in `client/Client.kt`), which checks
  `isSessionCompleted` and then looks the session up again with `awaitSession`.
  - An `ElicitationRegistry` was rejected: it would move about 50 lines and need three dependencies (the session
    lookup, `protocol.getOutgoingRequestSessionId`, `globalElicitationHandler`), and its only state is already in
    `ElicitationSessionStore`. Answering elicitations on cancel doesn't need it either (see "Side findings").

### Close and connection end

The subagents page: closing a parent ends the work of all its descendants and needs no terminal notification
(`sub:480`, `sub:483`), and the client handles "the pending requests of the closed session's descendants together
with the parent's own" (`sub:482`). In v1 only a
root can be closed.

- **Before the request:** every descendant's `SubagentPendingPermissions` is closed, at the same point as the
  root's `RootPendingPermissions`, and for the same reason: the agent's close may wait for child work
  that waits for a permission answer. Each close only completes a signal, so the order doesn't matter.
  - `RootSessionHolder.startClosing` first moves the root's holder to the **`CLOSING` stage** of its `RootPhase` (after
    `ANSWERED`, and read through `root`), and then walks the descendants. `registerChild` reads the stage after it
    has added the new child to its parent, and closes the new child's permissions if the root is closing. So a child that the walk misses,
    because it was registered while the walk ran, has its permissions closed by its own registration: one of the
    two sees the other's write. It needs no lock.
  - The flag stays set if the close fails, as the closed permissions do, so a child announced after a failed close
    gets closed permissions too.
- **After a successful response, before `close()` returns,** the registry removes the subtree (`removeSessionTree`):
  1. it closes each holder's children map, top-down, so a late announcement can't register a grandchild under a
     holder that is going away (such a grandchild would block its id forever, and no close would reach it). This
     also closes the holder's `subagents` list, so a setup that finishes later drops its child;
  2. it stops the opening of every subagent in the subtree (`markOpeningStopped`), which keeps one that hasn't
     started from starting, then cancels the running ones and joins them, so none starts or delivers afterwards. The
     cost: `close()` waits for a child's `notify` to react to cancellation. The opening that the close itself runs in
     is the exception: a subagent's own operations may call `close()` from their `notify`, and joining that opening
     would wait for itself. The close doesn't join it, and cancels it at the end;
  3. the rest runs under `NonCancellable`, so a cancelled caller can't leave a half-removed tree, because the agent
     has already dropped the sessions. It removes the root and every descendant from the session map in one
     compare-and-set, each only if its id still maps to that holder, so no half-removed tree is ever visible. A
     registration checks the root's entry inside its own update of the map (see "Holders"), so it either enters
     the map before this update, and is removed, or stays out of it;
  4. outside that update, bottom-up, because it calls app code or suspends: it closes each child's pending
     permissions (which changes nothing if the first step ran, and answers the requests of a child that was
     announced after it), marks each child object closed, and fails each child holder that hasn't completed
     (waiting in `createSubagentOperations`, not yet set up, or still in its `notify` before `Opened`) with "session
     closed" through `failSessionTree`, so its waiting requests get the error and its queue goes to
     `globalSessionUpdateHandler`;
  5. it returns the removed ids, and `Client` removes their elicitation entries, as `removeSessionHolder` does for a
     root today.

  After that, updates for those ids are unconnected, as a closed root's are today.
- **Detecting the opening that the close runs in.** Either of two signs counts, and the close skips the join of
  every opening that matches one:
  - the close's job is the opening's job or below it, through the job hierarchy (`Job.children`): a close that a
    subagent's `notify` starts runs in its opening's job tree, directly or in a coroutine that `notify` launched,
    even one whose context lost the element below;
  - the close's context carries the opening's `SubagentOpening` element, which `startOpening` puts into the
    opening's context. It survives `withContext(NonCancellable)`, `withContext(Job())` and
    `withContext(otherScope.coroutineContext)`, which replace the job: the opening then waits for the close without
    reacting to cancellation, and isn't above it any more, so ancestry alone would join it and deadlock.

  A false match only skips a join: a close from an app coroutine that copied the opening's context into another
  scope returns before that opening reacts, and cancels it at the end. A missed one deadlocks, so the check errs
  that way. Ancestry alone, an earlier version, was rejected for that deadlock. A close in a coroutine of another
  scope with a context of its own, such as `otherScope.async { close() }`, matches neither sign and joins the
  opening: if `notify` awaits it cancellably, the cancellation ends that wait and the close completes; if `notify`
  waits for it without reacting to cancellation, neither finishes. The `ClientSession.close` KDoc states that
  limitation.
- **If the request fails,** nothing is removed, as for a root today and as on the agent side. An answer with
  `-32002` (resource not found) is not a failure: the agent no longer knows the session, and ACP asks the client to
  treat it as a successful close (`docs/protocol/v1/session-setup.mdx`, "Closing a session"), so the subtree is
  removed and `close()` returns an empty response. The permissions stay
  closed, as the root's do, and the root stays `CLOSING`.
- **Closed child objects keep their last values.** `subagents` on the closed parent stays as it was. `canCancel`
  becomes `false`, and `cancel()` throws `IllegalStateException`, so a closed child never sends `session/cancel` for
  an id the agent has dropped. Later `subagent_update`s are not applied to it. The removal marks every child of the
  tree closed, also one whose holder had completed. A child whose holder failed, because its own `notify` threw
  before its `Opened` or an ancestor's holder failed, is closed the same way, and stays in its parent's `subagents`:
  a listed child may be closed because its stream or an ancestor's failed, or because its root closed. `operations` stays valid as a link. An update that was already being delivered when `close()`
  ran can still reach it once, because the removal doesn't wait for deliveries in the handler's coroutine.
- **On connection end, nothing more happens.** The subagents' openings live in the connection scope, so they're
  cancelled, and question 20 rethrows that cancellation rather than failing the holder. `Protocol` cancels the
  incoming permission handlers, so no answer is sent. The holders stay, as roots' do. Cleaning up on disconnect would
  be one change for roots and children together, as on the agent side.
- **The race the opening state closes.** Once a parent's holder is complete, its announcements are delivered
  directly in the handler's coroutine, so a grandchild's setup can be inside `createSubagentOperations` while
  `close()` runs. Its holder is in the subtree but has no opening yet. Without a guard, the setup would return and
  launch an opening for a closed session. `startOpening` starts none once `markOpeningStopped` has run, and marks
  the session closed instead.

**`ClientSessionImpl` no longer calls its `Client`.** It used `client` only for `client.removeSessionHolder` after
a close (`ClientSessionImpl.close`, `client/internal/ClientSessionImpl.kt`). It gets two callbacks instead, as v2's `ClientSession` gets
`onClosed` (`client/v2/ClientSession.kt`, passed by `client/v2/Client.kt`):

```kotlin
internal class ClientSessionImpl(
    @Deprecated(...) override val client: Client,   // only for the deprecated getter
    ...,
    private val protocol: Protocol,
    private val isOpen: () -> Boolean,              // whether its holder is still the registered, live one
    private val onClosed: suspend () -> Unit,       // removes the subtree and its elicitation entries
    private val holder: RootSessionHolder,
) : ClientSession {

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        // A session that is gone sends nothing: the request carries only the id.
        if (!isOpen()) {
            check(!holder.isFailed) { "Session $sessionId failed to open and can't be closed" }
            return CloseSessionResponse()
        }

        /*
         * Answer before waiting for the response: an agent whose close waits for its work to end (a child's
         * included) would otherwise wait for a permission answer while the client waits for the close response.
         */
        pendingPermissions.close()
        holder.startClosing()  // answers the descendants' permission requests

        val response = AcpMethod.AgentMethods.V1.SessionClose(protocol, CloseSessionRequest(sessionId, _meta))
        onClosed()
        return response
    }
}

// Client, where it creates the session
ClientSessionImpl(
    this, sessionId, sessionParameters, operations, sessionResponse, protocol,
    isOpen = { sessions.isOpen(sessionHolder) },
    onClosed = { removeClosedSession(sessionHolder) },
    holder = sessionHolder,
)
```

- **A close acts on its own holder, not on the id.** v1 `session/close` carries only the id, and the id can be
  opened again after a close, so a close found by id from a session object that is gone would close the later
  session of that id. The session sends the request only while its holder is the registered holder of the id and
  blocks it (`ClientSessionRegistry.isOpen`), read once at the start; the callbacks then take the holder itself.
  While that holds, `claimForOpening` refuses every other opening of the id, so no later holder can appear while
  the close is in flight. Otherwise nothing is sent and nothing is touched:
  - the holder has failed: the opening failed, and only `notify` could have read the session from its context
    during the opening. `close()` throws `IllegalStateException`; the agent's session stays open until the
    connection ends, as for any opening that fails on the client after the answer;
  - otherwise the session was closed: a root's holder that blocks its id leaves the map only through
    `removeSessionTree`, because the reaping and a replacement take only holders that haven't completed or have
    failed. `close()` returns an empty `CloseSessionResponse`, the answer an agent gives for a closed id.

  A retry after a failed close finds its holder still registered and `CLOSING`, and sends again. Two closes in
  flight on the open session both send, and the second removal removes nothing.
- The session keeps the order: answer permissions, send the request, clean up only on success. `Client` decides
  what cleanup means, because it has the registry and the elicitation store. `removeClosedSession` is a private
  `Client` function that calls `sessions.removeSessionTree` and the store.
- Passing the registry to the session was rejected: it would give the session claiming, sorting, reaping and every
  other session's holders, and keep a cycle (registry → holder → session → registry). A single `onClose(_meta)`
  doing all of close was rejected: the request would move into a map component. The session gets its holder too, for
  `subagents` and for the guard above, and the callbacks still do the registry's part.
- **`ClientSession.client` is deprecated** at WARNING level, on the interface member. Nothing in the repo reads it,
  the app already has the `Client` it opened the session with, v2 has no such member, and it makes a cycle
  (`Client` → map → holder → session → `Client`). The message says to keep that `Client`. `ClientSessionImpl`
  keeps the reference only to implement the getter. `ClientNesSessionImpl` could take the same callback shape, as a
  separate cleanup.

**The registry and holder parts, simplified from the code:**

```kotlin
// ClientSessionRegistry
fun isOpen(root: RootSessionHolder): Boolean = _sessions.value.sessions[root.sessionId] === root && root.blocksId

suspend fun removeSessionTree(root: RootSessionHolder): Set<SessionId> {
    val subtree = buildList {
        fun visit(holder: ClientSessionHolder) {
            add(holder)
            holder.sealChildren().forEach(::visit)  // also seals holder.subagents
        }
        visit(root)
    }

    val context = currentCoroutineContext()
    val openings = subtree.mapNotNull { holder -> (holder as? SubagentSessionHolder)?.markOpeningStopped()?.let { holder to it } }
    // the opening's SubagentOpening element, or job ancestry
    val ownOpenings = openings.filter { (holder, job) -> context.runsIn(holder, job) }.map { it.second }.toSet()
    try {
        for ((_, job) in openings) if (job !in ownOpenings) job.cancel()
        for ((_, job) in openings) if (job !in ownOpenings) job.join()
    } finally {
        withContext(NonCancellable) {
            _sessions.update { storage ->  // registerChild checks the root's entry inside its own update
                storage.copy(sessions = storage.sessions.mutate { map ->
                    for (holder in subtree) if (map[holder.sessionId] === holder) map.remove(holder.sessionId)
                })
            }

            for (holder in subtree.asReversed()) {  // bottom-up
                if (holder !is SubagentSessionHolder) continue
                holder.permissions.close()
                holder.markClosed()
                if (!holder.session.isCompleted) failSessionTree(holder, AcpExpectedError("Session ${holder.sessionId} closed"))
            }
        }
        for (job in ownOpenings) job.cancel()
    }
    return removedIds
}

// ClientSessionHolder, which now knows its own id, with a subclass per kind of session
internal sealed class ClientSessionHolder(
    val sessionId: SessionId,
    val protocol: Protocol,                   // for the subagents' session/cancel
    val openingScope: CoroutineScope,         // from Protocol.newConnectionScope, for the subagents' openings
    val failSessionTree: suspend (ClientSessionHolder, Throwable) -> Unit,
) {
    private data class Children(
        val byId: PersistentMap<SessionId, SubagentSessionHolder> = persistentMapOf(),
        val listed: PersistentList<ClientSubagentSession> = persistentListOf(),  // the public subagents
        val sealed: Boolean = false,
    )
    private val children = atomic(Children())
    val subagents: List<ClientSubagentSession> get() = children.value.listed  // the public BaseClientSession.subagents

    fun addChild(child: SubagentSessionHolder): Boolean   // false once sealed
    fun removeChild(id: SessionId)
    fun childOrNull(id: SessionId): SubagentSessionHolder?
    fun listSubagent(subagent: ClientSubagentSession): Boolean  // false once sealed
    fun sealChildren(): Collection<SubagentSessionHolder>  // seals the map and `subagents` in one update
    fun forEachDescendant(action: (SubagentSessionHolder) -> Unit)

    // "Setup" steps 1 to 4: createSubagentOperations, the ClientSubagentSession, listSubagent, startOpening
    private suspend fun setUpSubagent(announcement: QueuedItem.Announcement, operations: ClientSessionOperations, parentOpened: Boolean)
}

internal class RootSessionHolder(
    sessionId: SessionId,
    protocol: Protocol,
    openingScope: CoroutineScope,
    failSessionTree: suspend (ClientSessionHolder, Throwable) -> Unit,
) : ClientSessionHolder(sessionId, protocol, openingScope, failSessionTree) {
    // UNCLAIMED, CLAIMED, ANSWERED (the root's opened marker was queued), CLOSING (the close started; stays if it fails),
    // with the claim's opening, in one atomic snapshot
    private val _phase = atomic(RootPhase.UNCLAIMED)
    val phase: RootPhase get() = _phase.value

    // ANSWERED or CLOSING to CLOSING (first: registerChild reads it after addChild), then closes the descendants'
    // permissions; throws for an unanswered root
    fun startClosing()
}

internal class SubagentSessionHolder(sessionId: SessionId, val parent: ClientSessionHolder) :
    ClientSessionHolder(sessionId, parent.protocol, parent.openingScope, parent.failSessionTree) {
    val permissions = SubagentPendingPermissions()

    private sealed class OpeningState {
        object NotStarted : OpeningState()
        class Running(val job: Job) : OpeningState()
        object Stopped : OpeningState()
    }
    private val opening = atomic<OpeningState>(OpeningState.NotStarted)
    private val _subagent = atomic<ClientSubagentSession?>(null)  // stored by startOpening, read by the holder's methods

    // Stores the session, then launches completeOpening() into openingScope with a SubagentOpening element in its
    // context. If markOpeningStopped has run, it starts none and marks the session closed instead.
    fun startOpening(session: ClientSubagentSession)
    fun markOpeningStopped(): Job?

    fun markOpened(promote: Boolean)  // from the root's response hook, or at the registration after the answer
    fun applyPatch(update: SessionUpdate.SubagentUpdate, parentOpened: Boolean)  // the parent's Patch branch
    fun markClosed()                  // when this holder fails or its root's tree is removed

    // The body of the opening: delivers the queue up to the opened marker, waits for the parent's holder, then switches.
    private suspend fun completeOpening()
}

// ClientSubagentSession, which gets its holder as a private constructor argument
private val closed = atomic(false)
public val canCancel: Boolean get() = !closed.value && capabilities.value?.cancel != null
public suspend fun cancel() {
    check(!closed.value) { "Subagent session $sessionId is closed" }
    check(capabilities.value?.cancel != null) { "Subagent session $sessionId doesn't allow cancel" }
    try {
        AcpMethod.AgentMethods.V1.SessionCancel(protocol, CancelNotification(sessionId))
    } finally {
        holder.permissions.onClientCancel()
    }
}
internal fun markClosed() { closed.value = true }  // applyPatch and onParentOpened then do nothing
```

### Effective cancel grant

- The latest stored `capabilities.cancel` is non-null. `null`, `{}` and `{cancel: null}` all mean no grant, and there
  is no grant until `capabilities` has been sent. `unknown` doesn't revoke it.
- **Load or resume of a parent:** grants reset to none. Grants before `Opened` are held back (see "History and live
  updates").
  - `session/load` (replay requested): they are history and never take effect.
  - `session/resume` (no replay): they take effect at `Opened`, when the call has succeeded.
  - A failed attempt discards everything received during it.

### Pending permission requests (builds on #156)

The subagents page: when the client cancels a session's work, it MUST answer that session's pending permission
requests with `cancelled` (`sub:471`). A descendant's requests are cleaned up once it reports `idle` with
`cancelled`, "as if it had cancelled the descendant directly"; until then the user can still answer them, and a
descendant that keeps running keeps its requests (`sub:472`). `idle` alone "does not cancel the requests of unrelated
work that continues" (`sub:473`). A v1 agent MUST report `running` or `idle` when it sees foreground
work start or stop (`sub:372`), and a cancel that races with ended work "does not change that work's outcome"
(`sub:456`), so no new `idle` follows it. This covers `session/request_permission` only; fs, terminal and
elicitation requests are unaffected (see "Side findings" for elicitation).

| Event | Root (#156) | Child |
|---|---|---|
| Work starts | `prompt()` → `clearCancellation()` | any live `state` patch → `clearCancellation()` |
| Client cancels it directly | `cancel()` → `cancelPending()` | `cancel()` → `cancelPending()`, then `clearCancellation()` unless the child has confirmed foreground work |
| Its work is cancelled, by anyone | n/a | a live `idle` + `cancelled` → `cancelPending()`, then `clearCancellation()`; before the root's answer it is held back, see `onOpened` below |
| Root closed | `close()` | `close()` on every descendant before the request; holders removed after it (see "Close and connection end") |

- **Every live `idle(cancelled)` answers the pending requests,** not only one that follows an ancestor's cancel. The
  client can't tell who cancelled the child, and doesn't need to: the snapshot confirms that the work was cancelled,
  and a late answer is not meant to restart cancelled work (the agent SHOULD NOT use it for that, and MUST NOT use it to
  permit a new task, `sub:475`), so the requests no longer apply (`sub:473`). An
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
- **`clearCancellation()` replaces `startTurn()`** in the base class `PendingPermissions`, for roots and children.
  It replaces only a signal that a cancel has completed:

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
  because the parent's consumer builds it later. `state` and `canCancel` keep their one writer, that consumer; the
  permission state isn't visible to the app, so updating it at arrival shows nothing ahead of the stream.
- **A child's permission request joins the cancel race when it arrives.** The wire order matters for the requests
  too: a request that waits for its child's holder, which can take as long as the root's opening call and the
  child's factory, must still be answered `cancelled` by a later live `idle(cancelled)`, by `cancel()` and by the
  root's close, and not only by what happens after the holder completed. So the `session/request_permission`
  handler takes the child's `SubagentPendingPermissions` from the registry at arrival, in its non-suspending prefix,
  before anything suspends. `Protocol` starts request handlers in arrival order, so that prefix runs before any
  later notification is sorted (the `setRequestOutcomeHandlerRaw` KDoc states the guarantee; a response that follows
  the request isn't ordered against it). The handler takes the child's holder (`subagentHolderOrNull`) and its
  permissions there, and the wait for **that** holder, `holder.session.await()`, moves **inside** the race, as the
  first step of the `answer` block, and the app's `requestPermissions` runs after it. It never looks the id up
  again, so it can't reach a holder registered later under the same id. The app still sees the request only after the holder has completed. A request that is cancelled
  meanwhile is answered `cancelled` without waiting for the holder. Roots are unchanged: their signal is read at the
  same point, and their holder is already complete when a prompt can run.
- **`PendingPermissions.kt`** holds three internal classes. The sealed base `PendingPermissions` owns the signal, the
  public `answerUnlessCancelled` and `close`, and the protected open `cancelPending` and `clearCancellation`, so the
  `CLOSED` rule lives in one place. `RootPendingPermissions` makes the two protected ones public, as the root's
  commands. `SubagentPendingPermissions` holds the rules above, and its `on…` names are events: the class decides
  the effect.

  ```kotlin
  internal class SubagentPendingPermissions : PendingPermissions() {
      private sealed class WorkState {
          // before the root's answer: the latest held state, and the requests set aside by a held idle(cancelled)
          class Held(val activeWork: Boolean, val setAside: CompletableDeferred<Unit>?) : WorkState()
          // after it: whether the last live state confirmed foreground work
          class Live(val activeWork: Boolean) : WorkState()
      }
      private val workState = atomic<WorkState>(WorkState.Held(activeWork = false, setAside = null))

      fun onState(state: StateUpdate?, live: Boolean)  // from the sorting, at arrival
      fun onOpened(promote: Boolean)                    // from markOpened, at the root's response
      fun onClientCancel()                              // from ClientSubagentSession.cancel()
      // inherited: suspend fun <T> answerUnlessCancelled(cancelledAnswer: () -> T, answer: suspend () -> T): T, close()
  }
  ```

  - `onState` with `live = true`: `idle(cancelled)` calls `cancelPending()`; every patch, including `state: null`,
    then records `Live(activeWork)`, with `activeWork` true when the state is `running` or `requires_action`, **and
    only then** calls `clearCancellation()`. With `live = false` it holds the state's effect back, as in "History
    and live updates": it replaces the `Held` state with the latest value, and an `idle(cancelled)` sets aside the
    requests pending at its arrival (the private `setPendingAside()`: the signal they wait on is replaced by a fresh
    one, which completes the old one when it completes, so the next cancel or close still answers them). "Live"
    comes from question 18's `ANSWERED` stage of the root, which the response hook reaches in wire order.
  - `onOpened(promote)`: after a resume, the `Held` state becomes `Live` with the held-back `activeWork`, and the
    requests set aside by a held `idle(cancelled)` are answered `cancelled`, as that `idle` would have answered them
    live; a later held state doesn't undo that, and a request that arrived after the `idle` isn't answered by it.
    After a load, the state becomes `Live(activeWork = false)` and nothing is answered: the requests set aside stay
    pending like the others. `markOpened` calls it on every open descendant as it walks the tree. Requests can be
    pending then, because a request joins the race when it arrives, also while it waits for its holder, which
    completes only after the answer, so the app never sees a request that a held `idle(cancelled)` answers.
  - `onClientCancel()`: `cancelPending()`, then `clearCancellation()` unless the state is `Live` with `activeWork`.
  - **A cancel racing a state.** The write order in `onState` fixes what each interleaving gives. If the state comes
    first and the cancel after it, the cancel sees the new `activeWork`: with confirmed foreground work, later
    requests stay blocked until a further state. If the cancel comes first and the state after it, requests are
    blocked only between the two, because the state clears the block. A cancel that overlaps the two writes of
    `onState` gets the outcome of one of these two orders.
  - `answerPermissionRequest` (an internal extension in `BaseClientSessionRuntime.kt`) runs the race:
    `answerUnlessCancelled(cancelledAnswer)` around "get the child's session, then run its operations'
    `requestPermissions`". The registry's `subagentHolderOrNull(sessionId)` gives the handler the child's holder,
    and with it the `SubagentPendingPermissions`, without suspending.
  - **Ownership:** the child's holder creates it at registration, so a load or resume, which needs new holders
    (question 19), starts with a fresh one. The holder keeps it for the sorting and `markOpened`, and the
    `ClientSubagentSession`, which gets its holder as a private constructor argument, reaches it through the holder
    for `cancel()`. The permission handler takes it from the holder too. The holder is never public, so the child
    object doesn't expose the queue, the tree or the opening.
  - The root uses `RootPendingPermissions`, which adds no rules: its only inputs are `prompt()`, `cancel()` and
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
- `ToolCallContent.Session`: round trip with absent, `null` and malformed `_meta`; a `tool_call_update` mixing it
  with other content decodes; the v1 → v2 conversion drops it.

**Agent**
- Capability gate: `CoroutineContext.subagents` is `null` without `subagents`, and non-null with it.
- Without `subagents`, a root's `client.notify` and its prompt flow both fail on `session_message` and
  `session_message_chunk` with `IllegalStateException`, and nothing reaches the wire. With `subagents`, both are
  sent. The same for a `tool_call` and a `tool_call_update` with `ToolCallContent.Session` in `content`.
- First-send rule: a factory that throws or returns before sending the announcement, live or during a replay,
  makes `announce` throw, nothing more reaches the wire, and the id is free again.
- Factory failure after the announcement:
  - announced with `running` and a cancel grant, then throws: `announce` rethrows the same exception, the child
    stays registered (announcing the id again fails), one `SubagentUpdate` with `capabilities = null` and
    `state = unknown` goes out, and a later `session/cancel` is ignored;
  - announced with `idle` and no grant: nothing more is sent;
  - the caller is cancelled while the factory runs after announcing: the same revoking update still goes out;
  - during the parent's replay: nothing more is sent;
  - the factory throws a `CancellationException` of its own after the announcement while the caller is active:
    `announce` throws `IllegalStateException` with the original as its cause, and the revoking update still goes
    out. With a cancelled caller, the `CancellationException` passes as is.
- A `session/cancel` while the factory runs:
  - with a grant in effect, it is delivered to `cancel()` once, after the factory returns and outside `announce`;
  - with the grant revoked before the factory returns, it is dropped;
  - with no grant, or when the factory throws, it is ignored.
- Client operations fail before the announcement.
- `announce` returns only after the announcement has been sent. A parent `session_message` sent right after it
  arrives after the announcement.
- Cancelling the caller while the factory runs: with nothing sent, the child is unregistered and its id is free
  again; with the announcement already sent, the child stays registered.
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
- Subtree cleanup:
  - collections are cancelled, ids are freed, and `AgentSubagents`/client operations fail afterwards;
  - no child update reaches the wire after the `session/close` response. A request that a child had in flight, and
    its `$/cancel_request`, may reach the client after it;
  - a `session/load` or `session/resume` of an open root cleans up the old root's subtree after the app's factory
    returns, before the replay and the response, so the replay can announce the same ids again, and the old root's
    turn keeps running. A factory that throws leaves the old tree as it was. A reload that then fails leaves the id
    absent, with no orphans;
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
- Replay window: it closes when the transport accepts the load response, not when `afterResponse` starts. A grant
  sent in between is live. A load whose response never reaches the transport drops the root with every child
  registered during it.

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
- An opening that fails before its answer delivers no `Opened`, and its updates go to `globalSessionUpdateHandler`.
  This includes a call that is cancelled while its request is in flight, even when the answer arrived at the same
  moment.
- An opening that fails after its answer, while the marker and the queued updates are delivered, fails the call and
  the holder, and the id can be opened again. Cover each way, on every opening overload: the caller is cancelled
  inside `notify`, `notify` throws, and `notify` throws a `CancellationException` of its own while the caller is
  active (an `IllegalStateException`). The rest of the queue goes to `globalSessionUpdateHandler`. The same for a
  `createClientOperations` that throws a `CancellationException` of its own.
- A call that is cancelled drops its response hook (an internal `Protocol` test): a response that arrives during the
  graceful wait doesn't reach the hook.
- A child announced during history gets `Opened(null)` at the position of the root's answer; a child announced after
  it gets `Opened(null)` as its first event; a grandchild too. Each stream gets exactly one.
- Agent side: `client.notify(update)` still sends the update; `RemoteClientSessionOperations.notify` throws
  `IllegalStateException` for `Opened` (an in-module test).

**Client: types and members**
- Member types: `subagents` is in announcement order on a root and on a child. Clearing `title` or `description`
  gives `null`. A live `unknown` gives `StateUpdate.Unknown`.
- Inside the parent's `notify` for a live `subagent_update`, the child's `canCancel` and `state` already reflect it.
- Effective directories: a child without its own values gets the root's `parameters` (`additionalDirectories`
  `null` gives an empty list); a child's `cwd` patch doesn't change its `additionalDirectories`; clearing `cwd`
  inherits again; a grandchild follows a later `cwd` patch of its parent; on the early path, a child read during
  the root's history already gets the root's values.
- A child's `current_mode_update` and `config_option_update` reach the child's `notify`, and the root's
  `currentMode` and `configOptions` don't change.
- `cancel()` without a grant throws `IllegalStateException` and sends nothing. With a grant, it sends
  `session/cancel` for the child's id.
- Reload and discovery: a root has a child with a live grant; the root is closed, then loaded or resumed again.
  The new root object's `subagents` holds only the children the agent re-announced, and the old object's list
  doesn't change. A re-announced child has `canCancel = false` until a live grant arrives after a load, and until
  `Opened` after a resume.

**Client: setup and routing**
- `createSubagentOperations` gets the child's id. Inside the parent's `notify` for the announcement, the child is in
  `subagents`, and its `operations` is the factory's result. Inside a call to the child's operations, the context
  names the child.
- A throwing `createSubagentOperations` fails only that child and its descendants. Parent operations that don't
  implement `SubagentOperations` do the same, with an error naming the interface. Either way, the child never
  appears in `subagents`, its requests get an error, and the parent's `notify` still gets the announcement.
- A `CancellationException` from `createSubagentOperations` while the consumer is active fails only that child. The
  connection ending while the factory runs is rethrown and fails no holder.
- A child's `notify` that throws while its history is delivered, before `Opened`, fails that child and its
  descendants, and the parent keeps working. The child's object stays in the parent's `subagents`, marked closed:
  `canCancel` is `false`, `cancel()` throws, and later patches don't change it. The control case: a throw after
  `Opened` fails nothing, as for a root.
- A child's live updates and requests wait until the parent's holder has completed. If the root's opening fails
  after its answer, the child's holder fails with it, also when the child had already delivered its `Opened(null)`,
  and nothing stays live under the failed root.
- A `CancellationException` from `createSubagentOperations` that is thrown while the parent's consumer is active
  fails only that child's holder. One from the factory path's `createClientOperations` fails the opening with an
  `IllegalStateException`.
- `createSubagentOperations` sees the parent session in `coroutineContext.baseClientSession`, except on the early
  path of `loadSession` and `resumeSession` with ready operations, where it sees none.
- A factory that suspends delays the parent's later updates, not other sessions', and the child's updates and
  requests wait for it.
- Registration race: the agent announces a child and immediately sends `session/request_permission` and a
  `session/update` for it while the parent's `notify` is blocked on the announcement. Both reach the child's
  operations instead of failing with "session not found" or going to `globalSessionUpdateHandler`. Two cases: the
  parent is still delivering its history, and the parent is already open.
- A second `subagent_update` for a child, sent before the parent's consumer reaches the announcement, is applied
  as a patch after setup, and the factory runs once.
- A nested child queued behind a pending parent: on `session/load` with a factory, with a replayed child and
  grandchild, the grandchild's `createSubagentOperations` is called on the child's operations, after the child's
  factory, which runs after the root's factory. Each stream gets its history, then exactly one `Opened(null)`, then
  live updates, and the grandchild's requests wait until then.
- fs, terminal and elicitation requests on a child id reach the child's operations. If those operations don't
  implement the mixin, the request fails with method-not-found.
- A `subagent_update` on a session id with no holder goes to `globalSessionUpdateHandler` and registers nothing. A
  later update on that child id is unconnected too.
- A request for an id before the answer of the `session/new` or `session/fork` that returns it, or with no opening
  in progress, fails at once with `-32002`, and the session still opens. During a load or resume of the id, a
  request waits and reaches the app after the opening. A request for an unknown id during an opening leaves the id
  free for a later announcement.
- Registry unit tests, without a `Protocol`:
  - an announcement rejected because its id has a speculative holder stays rejected when that holder is reaped
    before the parent's consumer reaches it: nothing is registered;
  - a child whose parent fails while the child's setup is inside `createSubagentOperations` never gets an opening.

**Client: malformed announcements**
- A `subagent_update` naming its own parent, an ancestor, another parent's child, an open root and an id with a
  speculative holder each reach the parent's `notify`, register nothing, change no child, and leave the owning
  holder's traffic where it was.
- A failed child's id stays taken: a later patch for it reaches the parent's `notify` and doesn't call the factory
  again; an update on the child's id during an unrelated opening goes to `globalSessionUpdateHandler`; a
  `loadSession` of that id is refused. After a failed load is retried, the same child ids register again.

**Client: history and grants**
- Grant values: no grant before `capabilities` arrives; `null`, `{}` and `{cancel: null}` all mean no grant; a live
  patch without `cancel` revokes the grant; `unknown` keeps it.
- Grants: after a load, a history grant never enables cancel, and a grant after `Opened` does. After a resume, a
  grant before the answer makes `canCancel` true only once `Opened` is delivered. A failed resume discards it.
- State: after a load, `state` is `null` until a live snapshot arrives, and a history snapshot doesn't touch pending
  permissions (a replayed `idle(cancelled)` doesn't answer a pending request). A live snapshot is confirmed, and a
  live `state: null` makes `state` `null` again.
- After a resume, a `state` that arrived before the answer becomes the confirmed `state` at `Opened`.
- During a load, history `title`, `description` and `cwd` patches are visible on the child before `Opened`, and
  history `capabilities` and `state` are not.
- On `session/new` and `session/fork`, a child announced before the response with a grant and `running` gets both
  at `Opened`, because no replay was requested.

**Client: pending permissions for children**
- A live `idle(cancelled)` answers the child's pending requests, whether or not the client cancelled anything; a
  live `idle` with another reason and an `unknown` don't.
- `cancel()` while `requires_action` answers the pending request and blocks later ones until the next live state;
  `cancel()` while `idle`, unconfirmed or `unknown` answers the pending ones and blocks nothing.
- A request that arrives after a live `running` reaches the app while the parent's `notify` for an earlier update
  is still blocked.
- A request that waits for its child's holder (the factory is slow, or the root's opening call is still running) is
  answered `cancelled` by a later live `idle(cancelled)`, by `cancel()` and by the root's close, without waiting for
  the holder.
- `SubagentPendingPermissions.onState` sets `activeWork` before it calls `clearCancellation()`: a state followed by a
  `cancel()` blocks later requests until a further state, and a `cancel()` followed by a state blocks them only
  between the two.
- A history `idle(cancelled)` touches nothing; after a resume, a held-back `running` counts as active work for
  `cancel()`; after a load it doesn't.
- After a resume, a held-back `idle(cancelled)` answers `cancelled` the requests that arrived before it, at the
  answer, and not the ones after it; after a load it answers none of them, and they still reach the app.
- `SubagentPendingPermissions` unit tests for the same rules, without a `Client`.
- `clearCancellation()` regression tests: a root request from turn 1 still waiting when `prompt()` starts turn 2 is
  answered by `cancel()`; a child request still waiting when `requires_action` arrives is answered by `cancel()`.

**Client: close**
- Every descendant's pending permission request is answered `cancelled` before `session/close` is sent, and an
  agent whose close waits for a child's permission answer doesn't deadlock.
- After the response, every descendant id is unconnected: its updates go to `globalSessionUpdateHandler`, its
  requests fail at once with `-32002` "session not found", and its elicitation entries are gone.
- A child waiting in `createSubagentOperations` when the close is answered is failed: its opening never starts,
  and its waiting requests get the error.
- A child inside a suspended `notify` when the response arrives: `close()` returns only after that `notify` has
  reacted to the cancellation.
- An announcement that arrives during the removal registers nothing.
- A failed close removes nothing, and the permissions stay closed: later requests on the root and its children are
  still answered `cancelled`. The root stays `CLOSING` too: a child announced after a failed close has its
  permissions closed.
- A child announced after `startClosing` and before the close response has its permission requests
  answered `cancelled` (the `CLOSING` stage), and after the removal too (the removal closes every subagent's
  permissions).
- A child registered while the removal runs stays out of the session map (the root's entry is gone), and its
  subagent object is closed.
- A close that a child's own `notify` starts returns without joining that opening, and cancels the opening at the
  end, also when `notify` waits for it under `withContext(NonCancellable)`, `withContext(Job())` or another scope's
  context. A close that an app coroutine starts after copying the opening's context into another scope doesn't
  join the opening either. A close in another scope with a fresh context that `notify` awaits joins the opening,
  whose cancellation ends that wait.
- The closed `subagents` list: a child whose setup finishes after the close is dropped, not added.
- A closed child keeps `subagents`, `state` and `title`; `canCancel` is `false`, and `cancel()` throws without
  sending anything.
- On connection end, the subagents' openings stop and no holder is failed.
- A registry unit test: a grandchild whose setup is inside `createSubagentOperations` when `removeSessionTree` runs
  never gets an opening.

**Client: holders**
- One opening per holder: a second opening of an open session fails before its request, for factory after factory,
  factory after operations and operations after factory; so does a second opening while the first is in flight.
  After `close()`, a load of the same id works. The `ClientSessionHolderTest` case that reopened a completed holder expects the refusal (`one opening call owns a holder`).
- A child announced on an open parent, with no opening in progress, gets a holder: its updates and requests reach
  its operations.
- A child waiting in `createSubagentOperations` survives an unrelated `newSession` that finishes meanwhile.
- A child under a speculative parent that no opening claims fails with it: the parent's queued updates, then the
  child's, go to `globalSessionUpdateHandler`.
- A failed load fails every descendant holder, and their queued updates go to `globalSessionUpdateHandler`.
- Early path: a child's history reaches its operations before the root's answer, then `Opened(null)`, then live
  updates. A child's `session/request_permission` during the load reaches the app only after the root's opening
  call has returned.
- Failed holders of a failed root leave the session map when the root is reaped or replaced, so repeated failed loads
  with fresh child ids leave nothing behind.
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
  - one opening per holder, the holder tree, the exemption from reaping, and the child's opening that stops at
    `Opened` (see "Holders");
  - the child's operations created by the parent's consumer before the announcement is delivered, and the child's
    opening in the connection scope;
  - child members are plain getters, updated by the parent's consumer before the event that changes them is
    delivered: don't add a `StateFlow` beside the stream;
  - `ClientSessionRegistry` owns the session map and `session/update` delivery; each update is sorted at arrival,
    and the consumer acts on its tag;
  - a child's pending permissions follow wire order through `SubagentPendingPermissions`, and the existing
    pending-permission rule is updated for `clearCancellation()`;
  - root close: descendants' permissions answered before the request, the subtree removed in one update after a
    successful response, and sessions reach the client only through callbacks, never through `Client`;
  - the root holder's `CLOSING` stage (reached first by `startClosing`, read by `registerChild` after
    `addChild`, kept after a failed close) and the check of the root's entry inside `registerChild`'s update of the
    map, the close order, own-opening detection by job ancestry or the opening's `SubagentOpening` context element,
    and that a failed child is marked closed and stays in `subagents`;
  - a child's opening waits for its parent's holder before it switches to synchronous delivery.
- A release note for the breaking `ClientSessionOperations.notify(SessionEvent)` change, and the samples moved to
  it. It also says that opening a session that is already open, or being opened, now fails for the factory
  overloads too, so a client must close a session before loading it again, that `ClientSession.client` is
  deprecated, and that `ToolCallContent` gets the `@UnstableApi` `Session` variant, so an exhaustive `when` over it
  needs a new branch or an `else`.
- KDoc for `setNotificationHandlerRaw` and the `setNotificationHandler` extension stating that ordering guarantee
  (see "Routing and delivery").
- Run the `check-specification` skill before writing the models (see "Sources").
- A sample in `:samples:kotlin-acp-client-sample` is optional. If you add one, use only public APIs.

## Looking ahead to v2 (do not implement now)

- v2 has no capability, because subagents are baseline. The child reports state with an ordinary `state_update` on its
  own stream, and the SDK mirrors the same snapshot onto the parent's `subagent_update.state` before collecting the
  next event (`rfd:788-797`, `v2sub:331`, `v2sub:352`). A child `SubagentUpdate` carrying `state` would be rejected.
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
- **Per-item cancels on a child** (RFD `7ec5185c`, `rfd:1078-1081`, `v2sub:375`): a child's tool call or terminal that advertises
  its own `capabilities.cancel` enables `tool_call/cancel` or `terminal/cancel` with the child's `sessionId`, even
  without the child's `cancel` grant. Both methods exist only in v2 (`schema/v2/schema.unstable.json`), so v1's
  "every other session method on a child id fails" still holds. The v2 agent must route them to the child, and
  the v2 client may need a way to send them for a child.
- **`session` tool call content in v2:** v2's `ToolCallContent` gets the same variant. v2 receivers that don't know
  it keep it as unknown content, which Kotlin's v2 `ToolCallContent.Unknown` already does. The conversion then maps
  it between versions instead of dropping it.
- **A per-child `prompt` capability** is the RFD's expected follow-up (FAQ "Why not allow prompting or steering a
  subagent?", `rfd:1641-1645`). `ClientSubagentSession` is a final class, so adding `prompt()` later breaks nothing.
- **Operations on v2 sessions:** v2's `ClientSession` keeps its operations in a private constructor field
  (the private `operations` constructor property in `client/v2/ClientSession.kt`), while v1 exposes `operations` on both roots and children (question 20). Decide
  in the v2 work whether v2 children follow v1 or v2 roots.

## Side findings

- **v1 `ToolCallContent` has no fallback for unknown variants** (`ToolCallContent` in `acp-model/.../model/ToolCall.kt`), unlike `SessionUpdate`
  with `UnknownSessionUpdate` and v2's `ToolCallContent.Unknown`. Any future content type makes a v1 client fail to
  decode the whole `tool_call` or `tool_call_update`, as `session` would without the new variant. Adding
  `ToolCallContent.Unknown` would change how stable v1 decodes, from failing to keeping the raw value, so it's a
  stable model decision of its own, not part of subagents.
- **Neither v1 client cancels a cancelled session's elicitations.** The subagents page says the client SHOULD dismiss
  a cancelled session's elicitation controls and answer them with the `cancel` action (`sub:471`), and
  that a request whose work has ended and that no longer applies SHOULD be dismissed (`sub:473`). The
  `elicitation/create` handler calls `ops.createElicitation` directly (`Client.createSessionElicitation` in
  `client/Client.kt`), so nothing
  races it against a cancel. Children follow roots here; fix both together, separately from subagents.
  - The fix needs no new state: run the session-scoped call through the same session's signal, whose
    `answerUnlessCancelled` is already generic, with
    `cancelledAnswer = { CreateElicitationResponse(ElicitationAction.Cancel) }`.
    A root then follows the root rules and a child the #24 rules. Elicitations outside any session, handled by
    `globalElicitationHandler`, stay out of it.
  - In that fix, rename `PendingPermissions` to `PendingInteractions`, and `RootPendingPermissions` and
    `SubagentPendingPermissions` to match (the RFD's term is "interactive request", `rfd:1193`, where the page says "request"; `PendingRequests` would be too
    broad, since fs and terminal requests aren't answered on cancel), and
    update `CLAUDE.md` and this spec. Until then, "permission" is the accurate name.
- The v2 client's `cancel()` answers every pending permission request, but v2 exempts requests for work in the
  `background` list (`docs/protocol/v2/prompt-lifecycle.mdx`). The v2 runtime doesn't model that list yet.
- **v1 `Agent.kt` registers the `SessionResume` handler twice** (confirmed after the rebase; in `agent/Agent.kt`, once after the
  `SessionLoad` handler and once after the `SessionFork` handler). The second registration replaces the first, so only
  the one after `SessionFork`, which includes `configOptions` in the response, is used. Remove the first one.
- **The Kotlin v2 state model lags behind the subagent schema** (for the v2 work, not now). Compared with Rust at
  `67cde2d5`:
  - `model.v2.StopReason` has no `error` variant. Rust v2 `StopReason` has `Error(ErrorStopReason)`, added on
    2026-10-01 according to the RFD changelog.
  - `model.v2.StateUpdate` has no real `unknown` state. Its `Unknown` variant already means "unrecognized state,
    raw JSON kept", so adding the real `unknown` state (`sub:413-420`) means renaming it (Rust uses `Other`), which is a breaking v2
    change.
- **v1 root session state after `session/load` is applied in the wrong order.** The agent replays the history, then
  answers. So the response's `configOptions`/`modes` are newer than every replayed `config_option_update` or
  `current_mode_update` (v2 says this explicitly: `docs/protocol/v2/session-setup.mdx:138`). `ClientSessionImpl`
  starts from the response and then applies the replayed updates on top: the factory path through
  `drainHistory` → `deliverUpdate` → `trackState`, and the early path (#146) through
  `earlyMode`/`earlyConfigOptions` in the holder, which `applyEarlyState` applies. So older history overwrites the
  response.
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
    (`_currentMode` in `ClientSessionImpl.kt`), and `trackState` writes to it on every `current_mode_update` without a guard. Config
    options are guarded by `configOptionsSupported`.

- **`ClientOperationsFactory` should be deprecated** (a follow-up PR, not this change; the plan is in a `TODO`
  comment in `client/ClientOperationsFactory.kt`). Roots don't need a factory. `session/new` and `session/fork` only
  need the client to buffer updates until the answer names the session id, and the holder does that either way. The
  factory's arguments aren't needed either:
  - `sessionId`: every operations call runs with the session in its context (the handlers in `client/Client.kt`,
    through `handleSessionRequest`, wrap it in `executeWithSession`), and the early load/resume overloads rely on the caller knowing the id.
  - `sessionResponse`: it's harmful. The factory gets it before the buffered history, which is older, so state built
    from the response and then from `notify` ends up stale, as in the finding above.

  The only v1 sample ignores both (the `newSession` call in `samples/.../TerminalClientSupport.kt`), and v2 takes `operations` directly in
  every opening call. The fix: add `newSession` and `forkSession` overloads that take `ClientSessionOperations`, like
  the load/resume ones from #146, then deprecate the interface and the four factory overloads. This doesn't solve
  history versus live updates on its own: that's `SessionEvent.Opened` (question 18), which also keeps the factory
  path in the right order. Children keep a genuine factory, `createSubagentOperations`, because the agent creates
  them.

- **Both root runtimes treat a `CancellationException` thrown by the app's own flow as a cancellation** (a
  follow-up PR, not this change; the fix ideas are in `FIXME` comments in `RootSessionWrapper.prompt` in
  `agent/internal/RootSessionWrapper.kt` and in `runTurn` in `agent/v2/Agent.kt`). The v1 `RootSessionWrapper.prompt` rethrows it, so the turn's job counts as cancelled and
  `session/prompt` answers `stopReason: cancelled` (`RootSessionWrapper.prompt` in `agent/internal/RootSessionWrapper.kt`, where `isCancelled` picks `StopReason.CANCELLED`). The v2 `runTurn` reports
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

A review of this spec as a fresh implementer found gaps in it, numbered 12 to 26. All of them are decided: the
sections above reflect the answers, the decision log records them, and "Tests" covers them. Nothing is open.

## Decision log

Every question below is decided. The sections above already reflect the decisions; this log records what was chosen
and why, so that a new session doesn't reopen them without a new reason.

### Rejected shapes

The design moved through several shapes before landing on the factory-based `announce` above:

- **No SDK-run turns.** The SDK doesn't start or run a child's work (no `send`, `runTurn` or `PromptResponse` for
  children). A child's work may run inside a native agent process the SDK only wraps. In v1, a child has no prompt and
  no prompt response (`sub:332`, `sub:380`). The SDK's guarantees come from routing, from owning the collection of
  `events`, and from reporting `unknown` when that feed ends while the child is active.
- **A child may start work on its own.** Work may start and stop many times (`sub:352`), and the subagents page ties
  state reports to observed work, not to assignments from the parent (`sub:372-373`). v1 children use the v2 state
  model (`rfd:838`).
- **One app type, no SDK handle.** The app implements `AgentSubagentSession`. Patches, state and messages are ordinary
  events, so there's no `update(...)` method and no `AgentSubagent` handle. Metadata properties such as `title` on the
  session were dropped, so the announcement is the only source of that data.
- **The announcement is the first send for the child,** enforced by the SDK. `announce` returns only after it's been
  sent, so the parent's `session_message` naming the child can't overtake it.
- **The SDK doesn't rewrite stop reasons.** The subagents page says a cancellation racing with already-ended work
  doesn't change that work's outcome, and the agent reports `cancelled` when it observes that the work stopped
  (`sub:455-456`). So unlike the v1 root, the SDK never
  forces `cancelled`.
- **No duplication in v1.** A child's state exists only as `subagent_update.state` on the parent's stream. In v2 the
  child reports `state_update` on its own stream, and the SDK would mirror it onto the parent (`rfd:788-797`).
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
3. **A live announcement that never comes. Decided (revised 2026-11-24):** the factory always sends the announcement
   before it returns, live and during a replay, and `announce` drops the child and throws otherwise. The earlier
   design, where `announce` waited for the first item of `events`, was removed: `events` are live work only (see
   "`announce` semantics", steps 5 and 7).
4. **Names. Decided:** keep `AgentSubagentSession`, `AgentSubagentFactory.createSubagent`, `AgentSubagentContext`, `AgentSubagents.announce` and `CoroutineContext.subagents`. Replace `isSupported` with a nullable `CoroutineContext.subagents` (see "Public API").

#### Formerly marked [confirm]

5. **A failed flow. Decided:** if `events` throws while the child is `running` or `requires_action`, the SDK reports `unknown`, not `idle(error)`, and logs the exception at `warn` (see "Feed end and failure").
6. **Closing a parent. Decided:** subtree cleanup cancels collections, frees ids and disables operations. It calls no app code for the children. Order: the app's `close()`, then cleanup, then the response. No cleanup if `close()` throws. On connection end, cleanup runs without `close()` (see "Lifetimes").
   - A `session/load` or `session/resume` that re-opens a root that is still open is still accepted. Once the app's factory returns, the old root's subtree is cleaned up the same way, and the old root's turn is left alone. The alternatives were refusing the re-open (the protocol defines no error that fits, and v2, the Rust SDK and the agent before subagents accept it) and leaving the old children registered (their ids would block the replay).
   - A child's late requests and its `$/cancel_request` after the close response are accepted and documented. Only updates are guaranteed not to follow it.
7. **The client side. Decided:** synchronous registration of a new child in the client's `session/update` handler, with the three guards; a child's opening waiting for its parent's holder before it completes its own (see "Holders"); a throwing `createSubagentOperations` failing only that child (and its descendants); the name `BaseClientSession` (see "Client side").

#### Client state and usage

8. **Read-only config for children on the client. Decided:** no config state at all, only notifications, as in v2. A child's `config_option_update` and `current_mode_update` reach its `notify` like any other update (see "Client side", "Types").
9. **Usage and cost. Decided:** no tracking. A child reports `usage` in its `idle` snapshot. A session's cost includes the
   costs of its descendants, so the client must not add a child's cost to an ancestor's, and context window usage is
   not summed (`sub:539-543`). The only work is the model field `StateUpdate.Idle.usage` (see "Model
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
      `schema/v1/schema.json`), so it has no response to carry an error. The subagents page says the agent ignores a
      `session/cancel` for a child that doesn't advertise `cancel` (`sub:433`), which is what the SDK does. The client learns whether it can cancel only
      from the grant, so the SDK withdraws the grant.
    - Leaving recovery to the app was rejected: after the throw, the caller has no handle that can still send a
      `SubagentUpdate` for the child.
    - A `CancellationException` that the app's factory throws after the announcement, while the caller is active,
      becomes an `IllegalStateException` with the original as its cause, the same rule as for a factory that throws
      before the announcement and for `events` (question 14). It would otherwise tear down the caller's scope.
    - A `session/cancel` that arrives while the factory still runs with a grant in effect is latched, and delivered
      to `cancel()` once, after the factory returns, if the grant still holds then. Dropping it was rejected: the
      factory may have sent the grant itself, so the client could legitimately cancel before the session exists.
      Without a grant in effect, and for a factory that throws, it is ignored.
13. **How the agent learns that the connection ended. Decided:** each root's subtree supervisor comes from an
    internal `Protocol.newConnectionScope(name)`, parented on `protocolJob` and running on the dispatcher of the
    scope the app passed to `Protocol` (see "Lifetimes").
    - Calling the app's `close()` on disconnect was rejected. It doesn't stop the SDK's own collections, and a hot
      `events` never completes. `AgentSupport` has no `close`, and `AgentSession.close` is the
      `session/close` handler, which throws by default. v1 calls no app code on disconnect today, so a hook would
      be a separate change for roots too.
    - A callback on `protocolJob` was rejected: the job's cancellation already stops the scope.
    - The handler dispatcher was rejected: a busy in-process child would delay every handler on the connection, and
      no guarantee depends on that thread.
14. **A `CancellationException` from the app's `events`. Decided:** it counts as cancelled only when the
    collector's own job is no longer active. Otherwise it's a feed failure: `unknown` if the child is active, then
    the collector completes normally (see "Feed end and failure").
    - Treating every CE as an SDK cancel was rejected. An SDK cancel of the collection is silent because the child
      is being torn down, so the same silence on a live parent would leave a `running` child on the client for
      good. Passing the CE through `announce` would also cancel the caller's active scope.
    - Sending `idle(cancelled)`, including after a routed `session/cancel`, was rejected: it has client-side
      effects (tool calls and pending requests), and for a relay it invents an outcome (`sub:455`). The app
      emits `idle(cancelled)` itself when it knows.
    - The root runtimes have the same problem. It's recorded in "Side findings" and as `FIXME` comments, for a
      follow-up PR. #131 fixed only the peer's `-32800`, through `AcpRequestCancelledException`.
15. **`session_message`/`session_message_chunk` without the capability. Decided:** a root's
    `RemoteClientSessionOperations.notify` throws `IllegalStateException` for them when the client didn't
    advertise `subagents`, as the fs, terminal and elicitation operations do for their capabilities (see
    "Routing"). Dropping them with a log was rejected: it hides an app bug that breaks a MUST NOT (`sub:59`) and
    loses content silently.
16. **The order of `afterResponse` work after `session/load`. Decided:** launch the replayed children's
    collections first, then call `postInitialize()` (see "Replay"). The reverse order was rejected: the launch
    doesn't block, but a slow `postInitialize()` would delay every child's first fresh snapshot.
    - The launch and the end of the replay window happen when the transport accepts the response
      (`RequestOutcome.onResponseQueued`), not when `afterResponse` starts, so the agent's idea of history matches the
      client's, which treats everything after the response on the wire as live.
    - A failed load, including a response that never reached the transport, drops the root with every registration
      made during it.
17. **Forking a parent that has children. Decided:** no SDK work, a KDoc note on `AgentSupport.forkSession`, and
    a test (see "Replay"). The RFD puts the remap of copied ids on the agent (`rfd:1099-1106`), and those ids live in
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
    - A child's `Opened` has a `null` response, and every stream gets exactly one, unless its stream fails first. The
      root's response for children and a separate child variant were rejected.
    - The marker is delivered after the request has returned, and the rest of the queue with it, in the opening
      call's own coroutine, cancellably. A cancellation or a `notify` failure there fails the opening, so the rule
      is "an opening that fails before its answer gets no `Opened`", and not "a failed opening gets none". Making
      the delivery uncancellable, so that an opening could not fail once the marker was out, was rejected: it runs
      app code in `NonCancellable`, and a cancelled caller would lose its session while the id stayed taken.
    - A `CancellationException` that app code throws during the opening, while the caller is active, becomes an
      `IllegalStateException`, on every path.
    - Whether history was replayed stays internal, on the root's holder, set by the opening call. A public
      `Opened.historyReplayed` was rejected for now: it arrives after the events it describes, the app knows it
      already, and the protocol may refine replay (`replay_complete`, agent-client-protocol#2407; v2 replay modes).
      Recording the opening method was rejected too: the page distinguishes only replay versus no replay (`sub:505-508`).
    - The response is observed through an internal, synchronous, success-only hook in `Protocol`, not `await()`,
      `invokeOnCompletion` or `RequestOutcome`, and not a public or suspending hook.
    - Child state is applied by the parent's consumer in queue order, before delivery, not in the handler at
      arrival: it matches the stream the app sees, and has one writer.
    - `ClientSubagentSession` is a final class with an `internal` constructor, and its `state` and `canCancel` stay
      public.
    - v2 gets the same shape as a separate type, in the v2 work.
19. **Holder lifecycle for children. Decided:** the holder holds a `BaseClientSession`; one opening per holder for
    every overload; child holders get their own creation path, live in a tree of holders, are exempt from reaping
    and end through a cascade; and each child has one opening until it has delivered `Opened` (see "Holders"). The
    opening first ran in the child's registration handler; question 20 moved it to a connection scope.
    - Keeping the factory re-claim from #146 was rejected: the response hook can't mark a completed holder, and the
      reopened session is never registered.
    - Letting a reopen join the open session, as the unmerged #148 proposed for JetBrains Air (which reopens a
      session when a tab is closed and opened again), was rejected: the replay would reach the open stream after its
      `Opened` and look live, and the page requires invalidating child state and grants on every load or resume
      (`sub:499-503`).
    - Letting a reload replace the open session (detach the old one without `session/close`, then open a fresh
      holder) was considered and not chosen: the strict rule is simpler, and a reload can be added later as an
      explicit API.
    - Internal members on `BaseClientSession` were rejected because Kotlin forbids them in interfaces, and
      `BaseClientSession` must be an interface: the existing public interface `ClientSession` extends it, and turning
      `ClientSession` into a class would break compiled callers and implementers.
    - A child's opening waits for its parent's holder to complete before it closes its queue and completes its own
      holder. Completing at once after `Opened` was rejected: the root's opening can still fail after its answer,
      and a child that had completed could not be failed with it. The cost is that a child's live updates and
      requests wait for the root's opening call (see "Holders").
    - A failed child's object is marked closed and stays in `subagents`, so the list only grows. Removing it from the
      list was rejected: it breaks that rule. Leaving it live was rejected too: its patches would still apply, and
      `cancel()` would send `session/cancel` for a session whose updates now go to the global handler.
20. **How the client gets a child's operations. Decided:** a client-only `SubagentOperations` with
    `suspend fun createSubagentOperations(sessionId: SessionId): ClientSessionOperations`, which the parent's
    consumer calls when it reaches the announcement; the SDK builds the `ClientSubagentSession` from the result, so
    `operations` stays on `BaseClientSession` and is always valid (see "Operations", "Types" and "Routing and
    delivery"). This also answers the former question 23: the operations are created in the parent's consumer,
    before the parent's `notify` sees the announcement. Objects are never reused across loads, because a load needs
    a closed session (question 19).
    - Inheriting it in `common.ClientSessionOperations`, as the earlier notes decided (commit `9d1dafb`), was
      rejected: `common` would import from `client`, and agent code could call it on
      `RemoteClientSessionOperations` and get `NotImplementedError`.
    - The child as the factory's argument was rejected: the child would exist before its operations, so either its
      `operations` getter would be filled in late and throw inside the factory, or it would have no getter and the
      app would keep a map. With the id, the operations get their session from the context, as roots do.
    - Running the factory in the child's own opening was rejected: the child would appear after the parent's
      `notify` saw its announcement. The cost of the chosen order is that the parent's stream waits for the
      factory, as a root's waits for `ClientOperationsFactory`.
    - An attach model (`child.attach(operations)`) was rejected: a child the app never attaches queues without
      bound and stalls the agent. Delivering the whole tree to the root's operations was rejected: every call would
      dispatch on an implicit context value.
    - The child's opening moved from its registration handler's coroutine to a scope from
      `Protocol.newConnectionScope`: for an open parent, that handler is busy in the parent's `notify`.
    - The call runs with the parent session in the context, except on the early path of `loadSession` and
      `resumeSession` with ready operations, where no session exists yet. The KDoc says so.
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
    - A child's permission request joins the cancel race when it arrives, and the wait for the child's holder moves
      inside the race. Taking the signal only after the holder completed was rejected: a request that waited
      for the holder would miss a later `idle(cancelled)`, a `cancel()` and the root's close, and get an error instead of
      `cancelled` on close. The app still sees the request only after the holder completes.
    - `onState` sets `activeWork` before it calls `clearCancellation()`, so a cancel that overlaps a state gets the
      outcome of one of the two orders: a state followed by a cancel blocks until a further state, and a cancel
      followed by a state blocks only between the two.
    - An `idle(cancelled)` that arrives before the root's answer is held back like the other states, and its
      effect with it: it sets aside the requests pending at its arrival, and `onOpened(promote = true)` answers them
      `cancelled`, so the outcome is that of the live `idle`. After a load it is dropped like the rest of history,
      and those requests stay pending like the others. Dropping it after a resume too, the earlier behaviour, let a
      request of cancelled work reach the app.
    - Keeping the child's signal only in the child object was rejected: patches can arrive before it exists. The
      child object gets its holder only as a private constructor argument, to reach the signal for `cancel()`, and
      the holder stays internal.
25. **Close order on the client. Decided:** before the request, every descendant's permissions are closed; after a
    successful response, the registry closes the children maps, stops the openings, removes the subtree in one
    compare-and-set, marks the child objects closed and fails the incomplete holders, and `Client` removes their
    elicitation entries; a failed close removes nothing; on connection end nothing more happens (see "Close and
    connection end"). `ClientSessionImpl` gets two callbacks instead of calling `Client`, and
    `ClientSession.client` is deprecated.
    - The root's holder has a `CLOSING` stage. `startClosing` reaches it first, and `registerChild` reads
      it after `addChild` and closes the new child's permissions, so a child announced during the walk, or after it and
      before the response, gets closed permissions. It stays after a failed close, as the closed permissions do,
      and it needs no lock. The registration checks inside its own update of the map that the root's entry is still
      there, which the removal takes out.
      The removal also closes every subagent's permissions. Narrowing the KDoc to the subagents that existed when the
      close started was rejected: the page says the client handles the pending requests of the closed session's descendants together with the parent's own (`sub:482`).
    - A close that a child's own `notify` starts doesn't join that child's opening. It detects the opening by job
      ancestry or by the opening's `SubagentOpening` context element, either one: the element survives
      `withContext(NonCancellable)` and other replaced jobs, and ancestry covers a coroutine below the opening whose
      context dropped the element. A false match only skips a join, while a missed one deadlocks (see "Close and
      connection end").
    - Detecting it by job ancestry alone was rejected: a close that `notify` waits for under
      `withContext(NonCancellable)` leaves the opening's job tree and would join its own opening forever. Detecting
      it by the element alone was rejected too: it misses a coroutine below the opening started with a context of
      its own.
    - Closing descendants bottom-up, the earlier recommendation, was rejected: the order mattered only for removing
      holders one at a time, and one update removes them all.
    - Closing every session on connection end, also in the earlier recommendation, was rejected: v1 does nothing
      for roots on disconnect, and the agent side doesn't either. It would be one change for both.
    - Passing the registry or the holder to the session, and a single `onClose(_meta)`, were rejected (see "Close
      and connection end").
    - An `ElicitationRegistry` was rejected in favour of a helper for session-scoped request handlers (see "Session
      registry").
26. **RFD changes after `67cde2d5`. Decided:** the design follows the RFD through four commits (`7ec5185c`,
    `14560463`, `b3f6123f`, `c392d7e9`), and the spec was pinned to `13b76582`, the latest revision then. The RFD became Completed on 2026-11-20 (`9d44c77a`),
    with its rules on the stable page, and the file now cites that page at `01a54c84` (see "Sources"). The last step,
    from `c392d7e9` to `13b76582`, changes no subagent model, test or schema entry. It moves the RFD to Preview and
    shifts line numbers. The only model change is the `@UnstableApi` `ToolCallContent.Session` variant in the stable
    v1 sealed class, a source-breaking addition noted in the release note; the agent's capability check covers it as
    it covers `session_message`. The delegated-task rule changes the examples. Per-item cancels and v2's `session`
    content go into "Looking ahead": those methods exist only in v2. One-shot children, view linking and lost
    tracking need no change.
    - Leaving the variant out was rejected: a client that advertises `subagents` would lose every tool-call update
      carrying a reference.
    - Adding `ToolCallContent.Unknown` in the same change was rejected: it changes stable v1 decoding (see "Side
      findings").
    - Not checking the capability for `session` content, the first version of this answer, was rejected: the pages
      say an agent sends `session` content only to a client that advertised the capability (`tc:357`, `sub:59`), with no
      MUST keyword, and the root's `notify` already checks `session_message` the same way.
