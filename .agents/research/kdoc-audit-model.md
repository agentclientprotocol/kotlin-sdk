# KDoc audit: `acp-model`, `build-logic`, Gradle comments, subagents sample, e2e (PR `master...42afb77`)

**Sources checked:** this repo @ `42afb77` (`git diff master...HEAD`); `agent-client-protocol` @ `134241fd` (2026-11-25, `pull --ff-only` OK; `docs/protocol/v1/subagents.mdx` last changed by `bde1e35f`, same as the pinned research), and `.agents/research/subagents-wire-shapes.md`, `subagents-stable-schema-diff.md`, `subagents-rfd-cite-mapping.md`.
**Confidence:** high for the stale/inaccurate findings (each one is checked against the code, and against the spec page where the KDoc makes a protocol claim). Medium for the rule-letter findings on private class-body properties (see R5).

Paths: `M/` = `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/`, `S/` = `samples/kotlin-acp-client-sample/src/main/kotlin/com/agentclientprotocol/samples/subagents/`.

## Summary

| File | Long-form | Stale/inaccurate | Rule | Missing |
|---|---|---|---|---|
| `M/SessionUpdate.kt` | 2 (restructure 1, keep 1) | 4 | – | – |
| `M/SubagentState.kt` | – | 3 | 1 | 1 (optional) |
| `M/Subagents.kt` | – | 1 | – | – |
| `M/ToolCall.kt` | – | – | – | 1 |
| `M/MaybeUndefined.kt` | 1 (keep) | 1 | – | – |
| `M/Capabilities.kt` | – | – | – | 1 (optional) |
| `M/v2/conversion/SessionUpdateConversions.kt` | – | 1 | – | – |
| `M/v2/conversion/ToolCallConversions.kt` | – | 1 (minor) | – | – |
| `M/JsonObjectSerializer.kt`, `M/SubagentSessionUpdates.kt` | – | – | – | – |
| `build-logic/.../CheckInternalPackagesNotPublic.kt` + `acp.multiplatform.gradle.kts` | – | – | 1 | – |
| `samples/kotlin-acp-client-sample/build.gradle.kts` | – | – | 1 | – |
| `S/SubagentsApp.kt` | 1 (keep) | – | – | – |
| `S/Transcript.kt` | – | – | 1 | – |
| `S/client/SubagentsClient.kt` | 1 (keep) | 1 | 1 | – |
| `S/client/ScriptedUser.kt` | – | 1 | – | 1 (minor) |
| `S/client/TreeClientSessionOperations.kt` | – | 1 | 2 | – |
| `S/client/SubagentClientSessionOperations.kt` | – | – | 1 | – |
| `S/client/ClientTranscript.kt` | – | – | 1 | – |
| `S/agent/DelegatingAgentSession.kt` | 3 (keep 3) | 1 (minor) | 2 | – |
| `S/agent/DelegatedSubagentSession.kt` | 1 (keep + fix) | 1 | – | – |
| `S/agent/DelegatedTask.kt` | – | – | 1 | – |
| `S/agent/NativeRelayedSubagentSession.kt` | 1 (restructure) | – | 2 | – |
| `S/agent/RemoteProbeSubagentSession.kt` | – | 1 (minor) | – | – |
| `S/agent/DelegatingAgentSupport.kt`, `S/agent/SessionRecord.kt` | – | – | 1 (R5) | – |
| `samples/.../support/TerminalClientSupport.kt` (PR line) | – | – | 1 | – |
| `e2e/**` | – | – | – | – (no comments in the PR hunks) |

No single-line KDoc, no consecutive `//` runs, and no references to `.agents/` or `docs/superpowers` in any file in scope.

## Long-form assessments

### L1. `M/SessionUpdate.kt:273-297`, `SessionUpdate.SubagentUpdate`: **restructure** (25 → 33 lines)

It has three accuracy problems (S1-S3 below), it leaves out two parts of the contract, and it has four topical paragraphs with no headings. Proposed text:

```kotlin
    /**
     * A patch of a subagent session, sent by the agent as `subagent_update` on the parent's stream. Needs the
     * `subagents` client capability.
     *
     * The first update for a [sessionId] announces the subagent under the session whose stream carries it; later
     * updates patch it.
     *
     * ### Patch semantics
     *
     * Every field except [sessionId] is a [MaybeUndefined] patch: omitted leaves the stored value unchanged, `null`
     * clears it, and a value replaces it. [capabilities] is replaced as a whole, not merged, so `{}` permits nothing,
     * like `null`. The SDK does not validate or rewrite patch contents; for example, it does not check that [cwd] is
     * absolute.
     *
     * ### Decoding
     *
     * Decoding is lenient for [title], [description], [capabilities], [state] and [_meta]: a malformed value decodes
     * as [MaybeUndefined.Undefined]. [cwd] and [additionalDirectories] are strict, because the client acts on them: a
     * malformed value fails the whole update.
     *
     * This type does not implement [AcpWithMeta], because [_meta] is a patch field.
     *
     * @property sessionId the subagent session this update announces or patches
     * @property title the parent's display label for the subagent session
     * @property description the parent's description of the subagent session's role
     * @property capabilities what the client may send for the subagent session
     * @property state the work state of the session; a value that is not a JSON object or has no string `state`
     * decodes as [MaybeUndefined.Undefined]
     * @property cwd absolute working directory of the session
     * @property additionalDirectories absolute additional roots of the session
     * @property _meta extension metadata; a value that is not a JSON object decodes as
     * [MaybeUndefined.Undefined]
     */
```

- **Added:** the capability requirement (stable `docs/protocol/v1/subagents.mdx:59`; `SessionMessage` and `SessionMessageChunk` already state it, this one didn't), and the announce-then-patch rule (`subagents.mdx:86`).
- **Fixed:** the `{}`/`null` sentence (S1), the `title`/`description` tags (S2), and the `state` tag (S3).
- **Moved:** "replaced as a whole" now appears only in the prose; it is no longer repeated in the `capabilities` tag.
- **Dropped:** nothing else. The `cwd` example is optional; leave it out if it seems too long.

### L2. `M/SessionUpdate.kt:309-333`, `SessionUpdate.SessionMessage`: **keep** (25 lines)

The text is accurate (checked against `docs/protocol/v1/prompt-turn.mdx` "Session-directed messages" and `SessionMessageSerializer`) and nothing in it is duplicated elsewhere. It needs one accuracy fix, S4. If you want it consistent with L1, give it the same `### Patch semantics` (paragraph 2) and `### Decoding` (paragraphs 3-4) headings. That is optional: each paragraph is short and covers one topic.

### L3. `M/MaybeUndefined.kt:13-27`, `MaybeUndefined`: **keep** (15 lines)

Most of it existed before the PR. The PR changed one paragraph, and that paragraph is accurate.

### L4. `S/SubagentsApp.kt:11-29`, `main`: **keep** (19 lines)

This is the entry point. The numbered script matches `runSubagentsClient` step for step: the reviewer, cleaner and tester outcomes, the relay, close, load, re-grant and second close. The interleaving note and the "start reading" pointer repeat the README, but a reader who opens the file first needs them.

### L5. `S/client/SubagentsClient.kt:30-48`, `runSubagentsClient`: **keep** (19 lines)

The first paragraph overlaps L4 by about three lines. Both are entry points (the README sends client readers here), so the overlap is justified. The scenario list matches the section comments in the file (1, 3, 7, 9, 10).

### L6. `S/agent/DelegatingAgentSession.kt:42-59`, class KDoc: **keep** (18 lines)

The scenario list matches the file's sections (1, 4, 9, 11, 12, 13, 14). One optional addition: line 55-56 lists the kinds of child but not the private `FinishedSubagentSession` returned after a load. Append ", and after a load, `FinishedSubagentSession` for a replayed child that does no more work".

### L7. `S/agent/DelegatingAgentSession.kt:146-162`, section 11 (replay): **keep** (17 lines)

The claims are accurate:
- the history window (per `AGENTS.md`);
- the order of the replay loop (`:168-179`);
- "which the spec allows": order holds within each session (`subagents.mdx:210`, `:526`).

Each bullet maps to one loop of the code. Nothing to cut.

### L8. `S/agent/DelegatingAgentSession.kt:214-228`, section 13 (fresh grant): **keep**, one word (15 lines)

All three paragraphs carry information found nowhere else:
- why the grant is live;
- why there is no `senderMessageId` (`:230` passes `null`);
- why the code checks `outcome != null` (`:207`).

Change at `:225`: "so the reviewer continues only once its previous task has ended" → "so the reviewer continues only if its previous task has ended". The code doesn't wait; it skips the continuation when the task hasn't ended.

### L9. `S/agent/DelegatedSubagentSession.kt:32-46`, class KDoc: **keep**, apply S10 (15 lines)

### L10. `S/agent/NativeRelayedSubagentSession.kt:24-38`, class KDoc: **restructure** (15 → 13 lines, plus 8 lines of property KDoc in the body)

`@property announcement` and `@property record` document class-body properties (`:40`, `:46`), which the guide says must have their own KDoc. The scenario sentence also reads as a run-on. Proposed text:

```kotlin
/**
 * A relayed worker of the native agent. It translates that one worker: the relay tells it what the worker did, and it
 * turns that into the updates of its [events] and into its [record].
 *
 * Scenario in this file: 10. relay: an agent that wraps a native agent with one flat event stream announces nested
 * children with `parent`.
 *
 * Only the relay loop calls [relayOutput] and [relayFinished], one at a time, which keeps the single-writer rule of
 * [record]. The relay loop also builds the tree: it announces the session, and decides under which parent.
 *
 * @param id the worker's session id
 * @param title the title the worker was announced with
 */
internal class NativeRelayedSubagentSession(id: SessionId, title: String) : AgentSubagentSession {
    /**
     * What the worker is announced with: its title, running.
     */
    val announcement = ...

    /**
     * What the worker did, written as the relay reports its output and end.
     */
    val record = ...
```

**Moved:** the two `@property` tags become body KDocs, with the same text. **Reworded:** the scenario line. **Dropped:** nothing.

## Stale or inaccurate

| # | Location | Declaration | Problem and evidence | Fix |
|---|---|---|---|---|
| S1 | `M/SessionUpdate.kt:278-279` | `SubagentUpdate` | "so `{}` and `null` are different patches". The spec says the opposite about their effect: "`capabilities: null` and `capabilities: {}` both permit nothing. Inside the object, an omitted or `null` `cancel` means unsupported, not unchanged" (`subagents.mdx:134`). | See L1: "replaced as a whole, not merged, so `{}` permits nothing, like `null`". |
| S2 | `M/SessionUpdate.kt:289-290` | `@property title`, `description` | "human-readable name of the subagent session" and "what the subagent session is doing" don't match the spec. `title` is "the parent's display label for the child", not the child's `session_info_update` title. `description` is "the parent's description of the child's role or purpose" (`subagents.mdx:104-112`, `:138`), not its activity. | See L1. |
| S3 | `M/SessionUpdate.kt:292-293` | `@property state` | "a malformed known state decodes as Undefined". In Kotlin a known state never fails: every payload field is lenient (`M/SubagentState.kt:172-180`, `lenientMeta`/`decodeOrNull`/`stringOrNull`). `{"state":"running","_meta":false}` decodes as `Running(null)`. Only a non-object, or a missing or non-string `state`, becomes `Undefined` (`:168-169`). | "a value that is not a JSON object or has no string `state` decodes as [MaybeUndefined.Undefined]". |
| S4 | `M/SessionUpdate.kt:313` | `SessionMessage` | "Messages are keyed by [messageId]" leaves out the scope: "a `messageId` scoped to that transcript", the outer `params.sessionId` (`prompt-turn.mdx` "Session-directed messages"). | "Messages are keyed by [messageId] within the session whose stream carries the update: repeated updates with the same ID patch the stored message." |
| S5 | `M/SubagentState.kt:26-27` | `StateUpdate` | "A known state whose payload is malformed never decodes as [Other]" implies that such a state fails. In Kotlin it decodes as the known state with the malformed fields `null` (same evidence as S3). | "Decoding fails for a value that is not a JSON object and for a missing or non-string `state`, and `subagent_update` treats such a state as absent. A known state always decodes as that state: a malformed field of it decodes as `null`." |
| S6 | `M/SubagentState.kt:43-44` | `Idle.stopReason` | "known-but-invalid values all decode as `null`" is wrong for Kotlin. `"error"` with a bad `error` gives `Error(null)`, and a known reason with extra fields gives that reason (`:216-223`). Only a missing, `null` or non-string `stopReason` gives `null` (`:214`). | "why the work stopped; omitted, `null` and non-string values decode as `null`, meaning no stop reason is reported". |
| S7 | `M/Subagents.kt:22-23` | `SubagentCapabilities` | It lists the three updates but not `session` tool call content, which the agent sends "only to a Client that advertised it" (`subagents.mdx:59`; `tool-calls.mdx:357`). | "...opts in to `subagent_update`, `session_message` and `session_message_chunk` session updates and to `session` tool call content. Without it the agent must not send those updates, and sends no `session` content." |
| S8 | `M/MaybeUndefined.kt:94-98` | `decodeMaybeUndefined` | (a) "decoded by their own serializer": `cwd` and `additionalDirectories` are actually decoded by private helpers (`strictMaybeString`/`strictMaybeStringList`, `M/SubagentSessionUpdates.kt:116-135`). (b) "A present-but-malformed value degrades to Undefined" doesn't hold for `String` fields: `ACPJson` has `isLenient = true` (`rpc/Serialization.kt:25`), so `false`/`42` decode as text. That is why `lenientMaybeString` exists (`M/SubagentSessionUpdates.kt:101-104`). | "A present-but-malformed value degrades to [MaybeUndefined.Undefined] instead of failing, mirroring Rust's `DefaultOnError`. `ACPJson` is lenient, so a JSON number or boolean still decodes as a string: a string field that must reject them checks the element itself, as the v1 `SubagentUpdate.title` does. Fields that must fail on a malformed value, such as `SubagentUpdate.cwd` and `additionalDirectories`, don't use this helper." |
| S9 | `M/v2/conversion/SessionUpdateConversions.kt:318-320` | `V1SessionUpdate.toV2` `@throws` | "which v2 does not model yet" reads as the protocol, but upstream v2 defines `subagent_update`/`session_message` (`schema/v2/schema.json`, `docs/protocol/v2/subagents.mdx:12-15`). It is this SDK's v2 model that lacks them. The run-on is also hard to scan. | "@throws ProtocolConversionException if this is a `current_mode_update`, which v2 removed; a `subagent_update`, `session_message` or `session_message_chunk`, which this SDK's v2 model does not have yet; or a message chunk with no `messageId`, which v2 requires" |
| S9b | `M/v2/conversion/ToolCallConversions.kt:88-101` (minor) | content block comment | The PR drops v1 `ToolCallContent.Session` (`:120`), but the comment explains only diffs and terminals. Upstream v2 has `SessionReference` (`schema/v2/schema.json`). | Add a paragraph: "v1's `session` reference doesn't cross either: this SDK's v2 model has no `session` content yet." |
| S10 | `S/agent/DelegatedSubagentSession.kt:35-36` | class KDoc | "That flow is the one path for every update of the child" isn't true. The announcement goes through `context.client.notify` in the factory (`DelegatedSubagentFactory.kt:50`), and the grandchild's announcement goes on this child's stream through the probe's context (`RemoteProbeSubagentSession.kt:39`). | "After the announcement, which the factory sends, that flow carries every update of the child except the announcements of its own subagents." |
| S11 | `S/client/ScriptedUser.kt:66` | `askPermission` | "Other cancellations, like the connection's end, answer nothing" contradicts section 5 just above (`:60-61`) and the SDK: closing the root also answers the request `cancelled`, yet the code prints "dismissed" then because `subagentCancelled` is false. | `// Only the user's cancel of the subagent is named; any other cancellation, such as the root's close or the connection's end, shows as dismissed.` |
| S12 | `S/client/TreeClientSessionOperations.kt:17-18` | class KDoc | "The subclasses differ only in how they find their session" overlooks the second abstract member, `showOpened` (`:47`). | "The subclasses differ in how they find their session in the call's context and how they show its `Opened`: ..." |
| S13 | `S/client/SubagentsClient.kt:168-169` | `isActive` | "as the SDK and the spec treat it" overstates the spec. It says only that an unrecognized value "does not mean that the work ended" (`subagents.mdx:378`). The SDK part is right (`acp/.../SubagentSessionWrapper.kt:427-430`). | "An unrecognized state counts as active, as the SDK's agent side treats it: the spec says it doesn't mean the work ended." |
| S14 | `S/agent/RemoteProbeSubagentSession.kt:48` (minor) | class KDoc | "its connection drops as soon as the probe has started": it drops after the probe got its task and reported the rerun tool call (`:70-92`). | "its connection drops as soon as the probe has started the rerun." |
| S15 | `S/agent/DelegatingAgentSession.kt:55-56` (minor) | class KDoc | See L6: the list of child kinds is incomplete. | See L6. |

## Rule violations

| # | Location | Problem | Fix |
|---|---|---|---|
| R1 | `M/SubagentState.kt:128-129`, `IdleStopReason.Error` `@property error` | The tag text has two sentences, and the second starts with a capital. | "the JSON-RPC error describing the failure, which agents should include; omitted, `null` and malformed values decode as `null`" |
| R2 | `S/agent/DelegatedTask.kt:24-25` | `@property sentMessageId`/`receivedMessageId` document class-body properties (`:36-37`), and "that message" depends on the `instruction` tag. | Remove the two tags. Above `:36` put `/**\n * The id of the instruction's message in the parent's stream.\n */`, and above `:37` put "...in the child's stream." |
| R3 | `S/agent/NativeRelayedSubagentSession.kt:36-37` | Same issue: body properties documented with `@property`. | See L10. |
| R4 | `S/agent/NativeRelayedSubagentSession.kt:28-29` | Run-on scenario line. | See L10. |
| R5 | Class-body properties documented with `/* */` or `//` instead of KDoc: `S/client/TreeClientSessionOperations.kt:33-36` (`opened`), `S/client/ClientTranscript.kt:25-28` (`shown`) and `:31` (`openedSubagents`), `S/agent/DelegatingAgentSession.kt:65-69` (`children`), `S/agent/DelegatingAgentSupport.kt:32-34` (`records`), `S/agent/SessionRecord.kt:27-30` (`_turns`) | The guide's rule is "A property declared in the class body is documented with a KDoc comment directly above it". All of these are private, so this is the rule's letter with low impact. | Change `/*` to `/**`, and turn the `//` into a KDoc. Text unchanged, except R6. |
| R6 | `S/agent/DelegatingAgentSession.kt:67-68` | "This object's replay adds to it, then its turns, one at a time" is hard to parse. It is meant to justify the unsynchronized `+=`. | "Its writers run one at a time: first this object's replay, then its turns. `close` reads it from another coroutine." |
| R7 | `S/agent/DelegatingAgentSession.kt:38`, `NATIVE_AGENT_REQUEST` | "A prompt that mentions it is relayed" is ambiguous. | "A prompt that contains this text is relayed to the native agent (scenario 10); any other prompt is delegated." |
| R8 | `S/client/TreeClientSessionOperations.kt:99-102` | "the client's early `loadSession` overload" uses a term defined nowhere. `SubagentsClient.kt:96-97` says "takes the operations object up front". | "While the `loadSession` overload that takes the operations up front delivers it, ..." |
| R9 | `S/client/SubagentClientSessionOperations.kt:25` | "exactly one `Opened` without a response" is obscure. | "then exactly one `Opened`, which carries no response, then live updates." |
| R10 | `S/client/SubagentsClient.kt:102-103` | "a replayed subagent ... what the history said about them": the number doesn't agree, and the antecedent is unclear. | "...what the history said about its state and grant is dropped at its parent's `Opened`, ..." |
| R11 | `S/Transcript.kt:9`, `Transcript` | "Called from several coroutines at once" doesn't say what that means for an implementer. | "[add] is called from several coroutines at once, so an implementation must be thread-safe." |
| R12 | `samples/.../support/TerminalClientSupport.kt:53` (a line the PR added) | "Updates before the marker came before the answer" uses SDK-internal jargon ("marker"). | `// Updates before Opened arrived before the opening call's answer, like the history that session/load replays.` |
| R13 | `samples/kotlin-acp-client-sample/build.gradle.kts:42-45` | The second sentence, about the plain `run` task and `-PmainClass`, describes the `application {}` block at `:31-36`, not `registerSampleTask`. | Keep "Each sample has one run task." above `registerSampleTask`. Move "The plain `run` task starts the simple agent by default; override it with `-PmainClass=<fully qualified main class>`." into a block comment above `application {`. |
| R14 | `build-logic/src/main/kotlin/CheckInternalPackagesNotPublic.kt:15-16` and `acp.multiplatform.gradle.kts:55-56` | (a) "`apiCheck` fails if the dump was not regenerated, and this task fails if it was" is vague: "if it was" means if it was regenerated *and lists such a class*. (b) The sentence "the dump is the compiler's own view of the public API" appears in both places. | KDoc: "The dump is the compiler's own view of the public API. Run the task next to `apiCheck`: `apiCheck` fails if the dump is out of date, and this task fails if an up-to-date dump lists such a class." Gradle comment: "Keep `internal` packages out of the public API, checked against the API dump (see `CheckInternalPackagesNotPublic`)." |

## Missing KDoc

| # | Location | Declaration | Proposal |
|---|---|---|---|
| X1 | `M/ToolCall.kt:112-116` | `ToolCallContent.Session` properties | Its sibling `_meta` docs in `Subagents.kt` state the leniency; this one uses `LenientMetaSerializer` silently. Add `@property sessionId the referenced session, which the client must already know` and `@property _meta item metadata; omitted, `null` and values that are not a JSON object all decode as `null`` (`tool-calls.mdx` "Sessions"). |
| X2 | `M/SubagentState.kt:45`, `Idle.usage` (optional) | unstable member of a stable type | The type is stable but `usage` is `@property:UnstableApi`, because the stable `IdleStateUpdate` has no `usage` (`subagents-stable-schema-diff.md` row 9). Append to the tag: "; unstable: not part of the stable v1 schema". `PromptResponse.usage` has no such note, so this is optional. |
| X3 | `M/Capabilities.kt:97-99`, `ClientCapabilities.subagents` (optional) | new property | The class documents none of its properties, and `SubagentCapabilities` covers the meaning, including "malformed means unsupported". No change needed unless the class gains `@property` tags. |
| X4 | `S/client/ScriptedUser.kt:18-19` (minor) | `PERMISSION_ANSWERED_CANCELLED`, `USER_CANCELS` | Their sibling `LOADING` (`SubagentsClient.kt:152-155`) has a KDoc. Add one line each, e.g. "The client's line when the SDK answered a subagent's permission request `cancelled` after the user cancelled it." |

## Out-of-scope observations (not KDoc; for routing)

- `M/ToolCall.kt:5`: the PR added `import com.agentclientprotocol.annotations.UnstableApi`, and the file doesn't use it. `@file:Suppress("unused")` hides the warning.
- v2 string tri-state fields decode through `decodeMaybeUndefined(..., String.serializer())`, at `M/v2/SessionUpdate.kt:492-493`, `:601`, `M/v2/Terminal.kt:123-124` and `M/v2/ToolCall.kt:753-754`. Because `ACPJson` is lenient, `false` or `42` decode as text rather than as `Undefined`. This is v2 behavior from before the PR; S8 only documents it.
- `M/v2/conversion/ProtocolConversionException.kt:27`: the helper `removedV1EnumVariant` is used for variants that v2 has upstream but this SDK doesn't model. Its message ("cannot be represented in v2") is fine; the helper's name is misleading.
- `M/Subagents.kt:110`: `LenientSubagentCapabilitiesSerializer` uses an unchecked `decoder as JsonDecoder`, while the other serializers use `as?` with a `SerializationException`.
