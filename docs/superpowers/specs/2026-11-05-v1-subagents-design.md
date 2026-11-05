# v1 subagents: design notes (in discussion)

Status: work in progress, not approved. These notes capture an ongoing design discussion so it can be resumed in a
new session. Each item is marked:

- **[decided]**: the user agreed;
- **[proposed]**: the assistant's call, not yet confirmed;
- **[open]**: still under discussion.

Spec: `agent-client-protocol` at `dc011f0`, primarily `docs/rfds/subagents.mdx` (an unstable draft). Line numbers
below refer to that RFD. Rust v1 models: `agent-client-protocol-schema/src/v1/client.rs` (`SubagentUpdate`,
`SessionMessage`, `SessionMessageChunk`, `StateUpdate`, `IdleStopReason`, `SubagentSessionCapabilities`).

## Branch and prerequisites

- `eugenethedev/subagents` is stacked on PR #156 (`eugenethedev/permission-cancellation-fix`, commit `8bbf921`).
  PR #156 makes the client answer pending permission requests with `cancelled` on `cancel()`/`close()`, using
  `client/PendingPermissionCancellation.kt`. The subagent design reuses that mechanism. **[decided]**
- PR #155 (agent-side v1 turn handling: one turn at a time, cancel targets its own turn) is merged to master, but
  #156 is based on the older master. The agent-side design reuses #155's turn handling, so #156, and this branch
  with it, should be rebased onto master before implementation. **[proposed]**
- Scope is v1 first. v2 follows later; in v2, subagents are baseline with no capability, and the child's state is
  mirrored on the child's own stream. **[decided]**

## What the v1 spec requires (summary)

- The client opts in with `clientCapabilities.subagents: {}`. Without it, the agent must not send any subagent
  update (lines 59-75).
- `subagent_update` is sent on the parent's stream. The first one announces a child and later ones patch it. It
  must precede all traffic bearing the child's id (lines 86-115).
  - Fields: `title`, `description`, `capabilities.cancel`, `state`, `cwd`, `additionalDirectories`, `_meta`.
  - Patch rules: omitted means unchanged, `null` clears, a value replaces. `capabilities` is replaced as a whole
    (lines 117-160).
- `session_message` (an upsert carrying participant ids and links between the two views) and
  `session_message_chunk` (content only) report messages between sessions. Delivery is the agent's own business
  (lines 417-635, 560-563).
- In v1, work state exists only as `subagent_update.state`: `running`, `idle` (with an optional stop reason,
  including `error` with a JSON-RPC error), `requires_action` or `unknown`, plus unknown values that are kept.
  The agent **MUST** report `running` and `idle` on every start and stop (lines 731-770).
- Child traffic goes over the same connection with no subscription. The client must not load, resume, prompt or
  close a child (lines 211-222, 946-969). `session/cancel` is allowed only while `capabilities.cancel` is granted
  (lines 982-1034).
- Replay rules (lines 886-929):
  - `session/load` replays child history best-effort before its response.
  - State and capabilities sent before the response are history. Only snapshots after the response count.
  - `session/resume` doesn't replay, but controls still wait for a successful answer.

## Client side

### Routing and operations

- **Registration.** The `session/update` handler registers a `ClientSessionHolder` for a newly announced child
  synchronously, before it can suspend. `Protocol` only guarantees that a notification's handler starts before
  later messages (`Protocol.kt:432-436`). The `subagent_update` itself is still delivered to the parent, through
  its prompt flow or `notify`. **[proposed]**
- **Child operations.** `SubagentOperations` is a mixin interface in the style of `FileSystemOperations` and
  `ElicitationOperations`. **[decided]**
  - It is inherited by `ClientSessionOperations`.
  - It has one method, `createSubagentOperations(subagent: ClientSubagentSession): ClientSessionOperations`.
  - Its default body throws `NotImplementedError`.
  - Nested children work through the same method on the child's operations.
- **Delivery.** While `createSubagentOperations` runs, the child holder queues its updates and its requests wait,
  as in the existing factory path. With the `ClientOperationsFactory` overloads, the parent's operations don't
  exist until the parent's answer, so the child holder waits for its parent first. Each session's updates keep
  their order. **[proposed]**
- **After setup.** All of the child's updates go to its `notify`; there is no prompt flow, because the client
  never prompts a child. Its permission, fs, terminal and elicitation requests go to its operations. **[proposed]**
- **Missing override.** Only that child's holder fails: its updates go to `globalSessionUpdateHandler`, its
  requests get an error, and the parent keeps working. **[proposed]**

### Types

- **`BaseClientSession`.** A sealed v1 interface whose direct subtypes are `ClientSession` and
  `ClientSubagentSession`. **[decided direction; name proposed]**
  - Members: `sessionId`, `client`, `operations`, `subagents`, and the effective `cwd`/`additionalDirectories`.
  - Its main purpose is a type for `ClientSubagentSession.parent`. It also allows one recursive tree walk and
    directory inheritance.
  - A new context accessor returns `BaseClientSession`. `coroutineContext.clientSession` stays as it is, for
    compatibility.
  - Adding `subagents` to `ClientSession` breaks external implementations of that interface. This is accepted,
    because the member is `@UnstableApi`.
- **Naming.** The list of children is called `subagents` on both `ClientSession` and `ClientSubagentSession`.
  **[decided]**
- **`ClientSubagentSession`.** It has no prompt, mode, model, config or close. Members: `parent`, `title`,
  `description`, `state` (unconfirmed until a fresh snapshot arrives), `canCancel: StateFlow<Boolean>` and
  `cancel()`. **[proposed]**
- **`cancel()` stays off the base type.** The root can always cancel, while a child depends on a grant that
  changes. `ClientSubagentSession.cancel()` throws `IllegalStateException` locally when no grant is in effect,
  like `Client.authStatus`. **[decided]**
- **KDoc rule.** Shared KDoc lives on `BaseClientSession` and its members, and overrides don't repeat it. A child
  type may add its own KDoc only where its behaviour differs and the difference is important to highlight.
  **[decided]**

### The effective cancel grant

- **The grant itself:** the latest stored `capabilities.cancel` is non-null. `null`, `{}` and `{cancel: null}` all
  mean no grant, and no grant exists until `capabilities` has been sent.
- **On a parent load or resume:** grants reset to none.
  - `session/load`: grants that arrive before the response are history and never take effect.
  - `session/resume`: grants that arrive before the response take effect only once the resume succeeds.
  - If the load or resume fails, everything received during the attempt is discarded.
- **Unaffected by `unknown`:** a grant is not revoked when the child moves to `unknown`.

### Pending permission requests (builds on #156)

Each child has its own `PendingPermissionCancellation`. **[proposed]**

| Event | Root (#156) | Child |
|---|---|---|
| Work starts | `prompt()` → `startTurn()` | child reports `running` → `startTurn()` |
| Client cancels it directly | `cancel()` → `cancelPending()` | `ClientSubagentSession.cancel()` → `cancelPending()` |
| Ancestor cancelled | n/a | child reports `idle` + `cancelled` → `cancelPending()` (lines 1045-1051) |
| Ancestor closed | `close()` | `close()` on every descendant, and their holders are removed |

### Discovery and history

- Subagents are discovered only from announcements. There is no list or query, and none is needed, because the
  agent re-announces ancestor chains before live traffic.
- After `session/resume`, the client sees only the children the agent re-announces.
- Child history is available only through the parent's `session/load`.

**[decided: acceptable]**

## Model layer (`:acp-model`, all `@UnstableApi`)

- **[proposed]** New types:
  - `ClientCapabilities.subagents: SubagentCapabilities?`;
  - `SessionUpdate.SubagentUpdate`, `SessionUpdate.SessionMessage` and `SessionUpdate.SessionMessageChunk`;
  - `SubagentSessionCapabilities` and `SessionCancelCapabilities`;
  - open unions for the v1 `SubagentState` (`StateUpdate` in Rust) and `IdleStopReason`, which keep unknown
    values.
- **[proposed]** The patch fields need `MaybeUndefined`, which today lives in `model.v2`. Move it to a shared
  package rather than have v1 models import a v2 type.
- **[proposed]** Hand-written serializers, as for other `MaybeUndefined` structs. Add JSON tests for absent versus
  `null`.

## Agent side

### Decided so far

- **Children outlive the parent's turn.** A child can keep working, and get new assignments, after the parent's
  `session/prompt` has answered. **[decided]**
- **Replay is decided by timing.** An announcement inside `replayHistory` creates the same registration as a live
  one; there is no history-only kind of child. Grants sent before the load response don't enable cancel routing.
  Fresh snapshots go out after the response, naturally from `postInitialize`, which runs as
  `RequestOutcome.afterResponse`. The agent never marks a snapshot as historical itself. **[decided]**
- **The SDK manages the child's lifecycle,** consistent with #155 and #156. **[decided direction]**
  - The SDK sends `running` before an assignment and `idle` after it.
  - The stop reason comes from the flow's `PromptResponseEvent`, from cancellation (`cancelled`), or from an
    exception (`error`, carrying the JSON-RPC error).
  - An assignment the parent waits for runs in the caller's job, so cancelling the parent's turn cascades to it.
  - A background assignment runs in a scope owned by the parent session, which the SDK cancels when the parent
    closes.
  - The SDK runs the child's code in the child's session context, so `currentCoroutineContext().client` is the
    child's.
  - The child's assignments reuse #155's turn handling.

### Registration on the agent: what "routing" means

`Agent` keeps a per-connection table of announced children (`_subagents`, childId → `SubagentWrapper extends
BaseSessionWrapper`). Outgoing child traffic needs no table, because `RemoteClientSessionOperations` takes only a
session id. The table is there for four things:

1. id uniqueness on the connection;
2. routing an incoming `session/cancel` for a child, only while the grant the agent last sent live is in effect;
   otherwise it's logged and ignored, since a notification can't get an error;
3. rejecting other methods on a child id with an explicit "is a subagent" error;
4. the close cascade: after `AgentSession.close()`, the subtree is removed and its handles invalidated. The same
   happens when the connection ends.

### Single source for `state` **[proposed]**

- The SDK is the only sender of a child's `state`.
- The app influences it only through:
  - the response event, an exception or cancellation;
  - automatic `requires_action` while the child has a pending permission request;
  - a narrow explicit call for `unknown`.
- Raw `SubagentUpdate`s sent through `client.notify` or emitted in a child's flow are rejected with
  `IllegalArgumentException` that points to the subagent API.

This matches the v1 runtime's handling of `PromptResponseEvent` (`Agent.kt:77`). The v2 runtime instead lets the
app report state and only fills in a missing idle (`v2/Agent.kt:163-193`).

### No factory on `AgentSupport` **[proposed; user asked, not yet answered]**

`AgentSupport.createSession` exists because the client starts a session. The agent starts a subagent itself, so a
factory would be a round trip with no client trigger. It would also lose the parent session's local context
(model client, tools, task). So: no `AgentSubagentSupport`, and no `AgentSupport.createSubagentSession`. The
parent builds the child object and hands it to the SDK with `currentCoroutineContext().subagents.start(child)`.

### Current shape of the API **[open]**

Behaviour (from the app) and control (from the SDK) are two roles that can't merge, but they don't need two
types. Two options are on the table.

**A. One abstract class the app extends** (the assistant's preference):

```kotlin
@UnstableApi
public abstract class AgentSubagentSession(public val sessionId: SessionId) {
    // The app implements:
    protected abstract suspend fun run(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event>
    protected open suspend fun onCancel() {}
    public open val title: String? get() = null
    // description, cwd, additionalDirectories, cancellable (initial cancel grant) likewise

    // The SDK implements; usable after `subagents.start(this)`, otherwise they throw:
    public suspend fun send(content: List<ContentBlock>, _meta: JsonElement? = null): PromptResponse
    public fun sendInBackground(content: List<ContentBlock>, _meta: JsonElement? = null): Deferred<PromptResponse>
    public suspend fun update(/* MaybeUndefined title, description, cwd, additionalDirectories, capabilities, _meta */)
    public val isActive: Boolean
}
```

- **Pros:** one object per child, and a child can update itself.
- **Cons:** an abstract class with SDK state breaks the SDK's interface-plus-context style. The control methods
  also exist before `start` and throw there.

**B. One interface, with control through the context.**

- The app implements only `AgentSubagentSession`: behaviour, metadata and `cancel()`.
- The parent addresses a child by id: `currentCoroutineContext().subagents.send(childId, content)`.
- A child updates itself through `currentCoroutineContext().subagent.update(...)`.

This keeps the SDK's style, but there's no object to hold.

The earlier draft, superseded: an `announce(...)` with many parameters plus a cancel-handler lambda, a separate
`AgentSubagent` handle, and `runAsSubagent { }`. It was dropped because `runAsSubagent` has no precedent in the SDK
and it duplicated `createSession`-style parameters.

### Messages between sessions **[proposed]**

- Messages can go both ways: parent to child, child to parent, and child to sibling (lines 168-171, 1242-1244).
  The protocol only reports them.
- **Parent to child:** `send(content)` is the message. The SDK reports the parent's outgoing `session_message`
  and the child's incoming one, with `senderMessageId`/`recipientMessageId` linking them, then runs the child.
- **Child to parent, or to a sibling:** delivery stays with the app. The SDK offers a reporting helper, roughly
  `reportMessage(from, to, content)`, for the views the app actually observed. A child's final answer usually
  still reaches the parent as a tool result.
- Each message has one reporting path: the SDK reports assignments, and the app reports only other messages.

### Still to discuss **[open]**

- Option A or B above, and the final names (`send`, `sendInBackground`, `run`, `onCancel`).
- How the parent gets a child's result. A v1 `PromptResponse` has only a stop reason, no content.
- The explicit `unknown` API, and whether automatic `requires_action` also covers elicitations.
- Whether SDK-reported parent-to-child messages can be turned off, for agents that don't want to expose the
  assignment text.
- Cost reporting (usage) for children: no aggregation (lines 1071-1101). Nothing has been designed yet.
- Child config options: the RFD says a child SHOULD report read-only config through `config_option_update`.
  Neither side has been designed yet.

## Side findings

- `Agent.kt` registers the v1 `SessionResume` handler twice, and the second registration wins. This is unrelated
  to subagents.
- The v2 client's `cancel()` answers every pending permission request, but v2 exempts requests for work in the
  `background` list (`docs/protocol/v2/prompt-lifecycle.mdx:1039`). The v2 runtime has no model of that list yet.
