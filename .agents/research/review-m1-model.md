# Review: M1, v1 subagents model layer (`a0dcdc5..7acd10d`)

Reviewed: product commits `fc3ea32`, `51b582d`, `7acd10d`, read through git at `7acd10d`. I did not run Gradle, because of the brief's constraint. The slices reported `./gradlew check` green at `7acd10d`. Wire shapes were checked field by field against `.agents/research/subagents-wire-shapes.md` (upstream `13b76582`), and the `session_message` patch claims against the RFD at `13b76582` (`docs/rfds/subagents.mdx:445-500`).

**Result: 0 blockers, 0 should-fix, 6 nits.** The models, discriminators, tri-state handling, lenient and strict decoding, and the API dump all match the design and the upstream research. The v2 side changed only in imports plus the three `toV2()` branches and the `toV2OrNull` branch.

## Findings (most severe first)

### 1. nit: `V1ClientCapabilities.toV2()` silently drops `subagents`
- `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/v2/conversion/CapabilitiesConversions.kt:340-358`
- Defect: the conversion throws for every other v1 capability that has nowhere to go in v2 (`fs`, `terminal`, `plan`). It drops `subagents = SubagentCapabilities()` without an error, and its KDoc doesn't mention the drop.
- Scenario: a bridge converts a v1 client's capabilities `{ "subagents": {} }` to v2. The v2 capabilities come out with no subagent opt-in and no exception, so the caller can't tell the opt-in was lost. The failure is safe: a v2 agent then sends no subagent traffic.
- Scope: changing the conversion is outside M1 (v2 stays unchanged). Record it for the v2 follow-up, or at least document the drop in the KDoc.
- Confidence: confirmed by reading.

### 2. nit: the new conversion branches have no tests
- `.../v2/conversion/SessionUpdateConversions.kt:349-356` and `.../v2/conversion/ToolCallConversions.kt:120`
- Defect: nothing tests that `V1SessionUpdate.toV2()` throws `ProtocolConversionException` for `subagent_update`, `session_message` and `session_message_chunk`. Nothing tests that `ToolCallContent.Session` is dropped either. The range touches `SessionUpdateConversionTest` only for an import.
- Scenario: someone changes a branch to map these updates to `SessionUpdate.Unknown`, or makes `Session` throw. The tool call conversion would then fail every v1 `tool_call` that carries a reference, and no test would catch it.
- Related, pre-existing pattern: `toV2ValueIfNotEmpty` (`ToolCallConversions.kt:249`) turns a v1 content list holding only `[Session]` into v2 `content: []`. On a v2 upsert, `[]` clears the stored content. Diff and terminal items already behave this way.
- Confidence: confirmed by reading (`git grep` finds no test of these branches).

### 3. nit: a multiline comment uses consecutive `//` lines
- `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/SubagentSessionUpdates.kt:18-20`
- Defect: three consecutive `//` lines, which the `AGENTS.md` "Comments and KDoc" rule forbids. It requires a `/* ... */` block for a multiline non-KDoc comment.
- Scenario: none at runtime. It is a direct rule violation in new code.
- Confidence: confirmed by reading.

### 4. nit: the moved `MaybeUndefined` KDoc still calls it a v2 type with v2-only semantics
- `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/MaybeUndefined.kt:14-20`, `:95-96`
- Defect: the public KDoc says the type is for "v2 upsert payloads". The `decodeMaybeUndefined` KDoc says it mirrors "`DefaultOnError` on every `MaybeUndefined` field of the v2 schema".
- Why that is now wrong: the type lives in the v1 package and backs v1 `SubagentUpdate` and `SessionMessage`. Some v1 `MaybeUndefined` fields are strict (`cwd`, `additionalDirectories`) and deliberately bypass the helper.
- Scenario: a v1 user reading the docs for `SubagentUpdate.title` is told the field type is a v2 concept. A later programmer may assume every `MaybeUndefined` field is lenient and route `cwd` through `decodeMaybeUndefined`, which would swallow the strict failure.
- Note: the move commit was a pure package move, so this is stale documentation, not a behavior change.
- Confidence: confirmed by reading.

### 5. nit: nested generated serializers under `isLenient` accept values Rust rejects
- `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/ToolCall.kt:121-126`; `SubagentState.kt:183`, `:230`
- Defect: `ToolCallContent.Session.sessionId`, the `JsonRpcError` in `IdleStopReason.Error`, and `Usage` in `Idle` go through generated serializers on `ACPJson` (`isLenient = true`). These accept wrong value types:
  - `"sessionId": 42` or `true` decodes as `SessionId("42")`. Rust rejects the item and skips it through `VecSkipError` on `ToolCall.content`.
  - `"error": {"code": "5", "message": "m"}` decodes as code `5`. Rust gives `error = None`.
  - A `Usage` with `"totalTokens": "3"` is accepted.
- Scenario: a malformed agent sends `{"type":"session","sessionId":42}`. Kotlin shows a reference to session `"42"` and re-encodes it as a string. A Rust client drops the item.
- Impact: receive-side only and display-only. The hand-written string checks in `SubagentSessionUpdates.kt` cover the patch fields correctly. This is programmer choice 5, and acceptable.
- Confidence: confirmed by reading (behavior of `isLenient` for primitives).

### 6. nit, pre-existing: strict v1 `Usage` drops the whole `Idle.usage` for one bad optional field
- `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/Requests.kt:698-706`, used at `SubagentState.kt:183`
- Defect: v1 `Usage` uses a generated, strict serializer. Rust makes `thoughtTokens`, `cachedReadTokens`, `cachedWriteTokens` and `_meta` lenient.
- Scenario: `usage: {"inputTokens":1,"outputTokens":2,"totalTokens":3,"thoughtTokens":"n/a"}`. Kotlin gives `Idle.usage = null`. Rust keeps the three totals and nulls only `thoughtTokens`.
- Impact: only malformed input is affected, and the snapshot itself still decodes, so it matters little for subagent conformance. The bigger impact is outside M1: the same field on `PromptResponse.usage` has no lenient wrapper, so there it fails the whole prompt response. That is a follow-up.
- Confidence: confirmed by reading; the Rust behavior is from the research report.

## Programmer choices (assessment)

1. **`subagents` after `_meta`.** This matches the design ("appended as the last constructor parameter", spec `:105-107`), and the API dump shows exactly that change. Positional calls that pass `_meta` keep compiling. Callers already compiled against the old constructor or `copy` break at the binary level, and the design accepted that. OK.
2. **`IdleStopReason.Other.fields` also drops `state`; `Other` is not validated.** Dropping `state` matches what Rust actually decodes: the internally tagged `StateUpdate` removes `state` before the flattened stop reason sees the remaining fields. Filtering it on encode is also safer than Rust, which would write a duplicate key.
   - Without validation, `StateUpdate.Other("running")` encodes and re-decodes as `Running`. The KDoc says "should not be one of" the known values, and the SDK only produces `Other` for unrecognized values. Acceptable.
3. **A non-object `_meta` decodes as `null` on the new types.** This matches Rust, where `Meta` is a JSON object under `DefaultOnError`. It differs from the older v1 `_meta: JsonElement?` fields, which accept any value, but conformance is what counts here. `StateUpdate.Other` keeps a malformed `_meta` raw, which also matches Rust. OK.
4. **`SubagentUpdate` and `SessionMessage` don't implement `AcpWithMeta`.** Right: their `_meta` is tri-state, and `AcpWithMeta._meta: JsonElement?` can't express that. Nothing in `:acp` or `acp-ktor*` casts to `AcpWithMeta`; only tests do. The KDoc states it. OK.
5. **Explicit `stringOrNull()` checks.** These are necessary and correct: under `isLenient`, `decodeMaybeUndefined(String)` would read `false` or `42` as text. The leftover leniency in `ToolCallContent.Session.sessionId` is finding 5, and it is harmless.
6. **`toV2()` throws `removedV1EnumVariant`.** No runtime calls `toV2()`: `git grep toV2` over `acp/`, `acp-ktor*`, samples and e2e at `7acd10d` finds nothing outside `acp-model`. A v1 connection never converts, and a v2 connection decodes v2 models, so the throw can't break a connection.
   - The message reads "v1 SessionUpdate variant `subagent_update` cannot be represented in v2", which is accurate. The KDoc `@throws` was updated. The only gap is the missing test (finding 2).
7. **Strict v1 `Usage`.** This is pre-existing and doesn't matter for subagent conformance beyond malformed input (finding 6).

## Conformance checks that passed

- **Discriminators:** `subagent_update`, `session_message`, `session_message_chunk`, `state`, `stopReason`, and `"type":"session"`.
- **`SubagentUpdate`:**
  - `sessionId` is strict: missing, `null` and `42` all fail.
  - `title` and `description` are lenient strings.
  - `capabilities` and `state` go through `decodeMaybeUndefined`, so a malformed known state becomes `Undefined` and never `Other`.
  - `cwd` and `additionalDirectories` are strict: a non-array, a non-string item or a `null` item fails the update, and the failure propagates through `SessionUpdateSerializer` instead of becoming `UnknownSessionUpdate`.
  - `_meta` is lenient and accepts only an object.
- **`SessionMessage`:**
  - `messageId` is strict.
  - The four id fields are lenient.
  - `content` uses `VecSkipError`. A non-array becomes `Undefined`, `null` and `[]` stay distinct, and an all-invalid list decodes as `[]`.
- **`SessionMessageChunk`:** `content` must be an object and decode, `_meta` is a plain optional (`null` and malformed both become `null`), and identity fields are dropped.
- **Capabilities:** `cancel` is lenient (`true`, `false` and `null` all become `null`). `ClientCapabilities.subagents` is lenient, and `null` is omitted on encode (tested).
- **State:**
  - Known states drop extra fields.
  - `Other` keeps everything except `state`, including `_meta`.
  - `Idle.stopReason` falls back to no stop reason for any non-string value.
  - `Error.error` is lenient.
  - `IdleStopReason.Other` strips `stopReason`, `usage` and `_meta`.
  - `usage` is lenient at field level.
- **`MaybeUndefined` move:** a byte-identical file apart from the `package` line. Every v2 usage was updated, and the v2 sources, tests, samples and e2e show only import changes.
- **API dump:**
  - The only removed lines are the old `ClientCapabilities` constructor and `copy` signatures.
  - All added types are the ones the milestone calls for, and all carry `@UnstableApi` in source.
  - `IdleStopReason` correctly has no serializer of its own.
- **KDoc:**
  - No single-line KDoc.
  - Primary-constructor properties are documented with `@property` in the class KDoc.
  - `ToolCallContent.Session` states the capability requirement, that the referenced session must already be known, that the reference is display-only, and the source break.
  - No reference to `.agents/` in product code.
- **Tests:**
  - Explicit JSON assertions throughout.
  - Absent versus `null` versus value on every patch field, singly and together.
  - `capabilities` replaced as a whole, including `{"cancel":null}` re-encoding as `{}`.
  - Strict `cwd` and `additionalDirectories` failures.
  - `content` `null` versus `[]`, and the all-invalid list.
  - Round trips of an unrecognized state and stop reason.
  - `idle` with an `error`.
  - A malformed known state becoming absent.
  - Full `SessionNotification` round trips.

## Follow-ups (outside M1)

- **Release note:** binary break of the stable `ClientCapabilities` constructor and `copy`; source break of the stable `ToolCallContent` and `SessionUpdate` sealed hierarchies.
- **v2 follow-up:** `V1ClientCapabilities.toV2()` and `subagents` (finding 1).
- **Pre-existing:** `PromptResponse.usage` fails the whole response when an optional `Usage` field is malformed (finding 6).
- **Pre-existing:** v2 string fields decoded through `decodeMaybeUndefined` accept `false` and `42` as text under `isLenient`, while Rust treats them as malformed. This was not checked in depth because v2 is out of scope.
