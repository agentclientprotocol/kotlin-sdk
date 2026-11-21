# v1 subagents: exact wire shapes and decoding rules at current upstream, and drift from the spec's `c392d7e9` record

**Sources checked:** `agent-client-protocol` @ `13b765828f961812f0c78ae577220e994209fa2f` (2026-11-14, fast-forwarded from `c392d7e9` today; Rust schema crate 1.18.0). Rust reference SDK not consulted: the question is about schema shape, which that repo doesn't own. This repo (`acp-kotlin-sdk`) read only to check the spec file and confirm that the existing types it names exist.
**Confidence:** high. The Rust models, their unit tests and `schema.unstable.json` agree. The subagent models, their tests and their schema entries are byte-identical between `c392d7e9` and `13b76582` (`git diff c392d7e9 13b76582` shows only added, unrelated prompt-suggestion code in `v1/client.rs`, no change to `v1/tool_call.rs`, and no subagent change in `schema/v1/schema.unstable.json`).

All paths below are in the `agent-client-protocol` repo at `13b76582` unless marked "Kotlin SDK".

## Answer

The v1 subagent wire shapes and decoding rules have not changed since `c392d7e9`. Every field, wire name, optionality rule, leniency rule and discriminator that the spec's "Model layer" section records matches current upstream. The differences are only:

1. **Rust line numbers in `client.rs` moved.** They are +10 lines up to line ~2088 and +137 after it, because `unstable_prompt_suggestions` code was inserted. The `tool_call.rs` line numbers are unchanged.
2. **The RFD moved from Draft to Preview** (2026-11-13). The spec still calls it "an unstable draft". v1 stays behind `unstable_subagents` and in `schema/v1/schema.unstable.json` only.
3. Some RFD line numbers after `:918` shifted (-1, then +2 after `:1246`). The RFD line numbers cited in "Model layer" (`:609-665`) are unchanged.

The report also adds a few precision points the spec doesn't spell out. They are not contradictions. See "Precision notes": `[]` and `null` stay distinct on the wire, an all-invalid `content` array decodes as `[]`, extra fields beside a known state or stop reason are dropped, and the stop-reason fallback comes from a flattened `Option`, not from `DefaultOnError`.

## Requirements

Tiers here are about wire and decoding. The behavioral MUSTs are already in the spec's "What the spec requires".

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|-------------|------|-------|-----------------|----------|
| 1 | Agent sends `subagent_update` / `session_message` / `session_message_chunk` (and `session` tool content) only if the client advertised `clientCapabilities.subagents` | MUST NOT (otherwise) | agent | unstable (v1 `unstable_subagents`) | `docs/rfds/subagents.mdx:76-78`, `:663-664` |
| 2 | `clientCapabilities.subagents`: optional, nullable object. Omitted or `null` means unsupported, `{}` means supported | wire shape | client | unstable | RFD `:62-66`; `client.rs:3513-3526`; schema `schema.unstable.json:6690` |
| 3 | `SubagentUpdate.sessionId` required, non-null | MUST (decode fails otherwise) | agent | unstable | RFD `:120-124`; `client.rs:976`; `client.rs:5297-5305` (test); schema `:6130`, `required: ["sessionId"]` |
| 4 | `SubagentUpdate` patch fields are tri-state: omitted = unchanged, `null` = clear, value = replace | wire semantics | both | unstable | RFD `:141-150`; `client.rs:977-1044` |
| 5 | `cwd` MUST be absolute. Each `additionalDirectories` entry MUST be absolute | MUST (prose only, not enforced by Rust or the schema) | agent | unstable | RFD `:302-308`; `client.rs:1014`, `:1022`; schema `:6176-6186` (plain `string`) |
| 6 | `SessionMessage.messageId` and `SessionMessageChunk.messageId` required, non-null. Chunk `content` required, a single non-null `ContentBlock` | MUST (decode fails) | agent | unstable | RFD `:439-443`; `client.rs:212-214`, `:258`; tests `client.rs:413-436`, `:525-546` |
| 7 | `SessionMessage` participant ids, references, `content` and `_meta` are tri-state patches. `content: []` also clears | wire semantics | both | unstable | RFD `:451-464`, `:493-500`; `client.rs:259-307` |
| 8 | Chunk `_meta`: omitted or `null` means no metadata. Not a message patch | wire semantics | both | unstable | RFD `:445-446`, `:499-500`; `client.rs:215-221` |
| 9 | Unrecognized states and stop reasons keep their discriminator and payload | MUST-preserve (SDK guidance) | client (receiver) | unstable | RFD `:916-920`, `:1296-1300` (SDKs "preserve them when deserializing and re-serializing"); `client.rs:1255-1260`, `:1434-1439` |
| 10 | `ToolCallContent` `session` variant: `sessionId` required, `_meta` optional and nullable | wire shape | agent | unstable | RFD `:611-636`; `tool_call.rs:559-611`; schema `:647`, `:1131` |
| 11 | Leniency (`DefaultOnError` / `VecSkipError`) as listed in "Details" | Rust model / schema-extension representation (the RFD is silent) | receiver | unstable | `x-deserialize-default-on-error` / `x-deserialize-skip-invalid-items` in schema; spec repo `AGENTS.md` "Schema rules" |

## Details

### `session/update` discriminators (tag field `sessionUpdate`)

`SessionUpdate` is `#[serde(tag = "sessionUpdate", rename_all = "snake_case")]` (`client.rs:100-105`).

| Wire value | Rust variant | Line | Schema |
|---|---|---|---|
| `subagent_update` | `SubagentUpdate(SubagentUpdate)` | `client.rs:176-177` | `schema.unstable.json:4912` |
| `session_message` | `SessionMessage(SessionMessage)` | `client.rs:184-185` | `:4928` |
| `session_message_chunk` | `SessionMessageChunk(SessionMessageChunk)` | `client.rs:191-192` | `:4944` |

All three are `#[cfg(feature = "unstable_subagents")]`. Without the feature, Rust rejects them as unknown variants (`client.rs:683-700`, test).

The fields of the update object sit beside `sessionUpdate`, inside `params.update`. The outer `params.sessionId` is a separate field (test `client.rs:5288-5296`).

### `SubagentUpdate` (`client.rs:967-1045`; schema `:6130-6195`)

Struct attributes: `#[serde_as] #[skip_serializing_none] #[serde(rename_all = "camelCase")] #[non_exhaustive]` (`client.rs:968-973`).

| Field (wire) | Rust type | Required | Tri-state | serde attributes | Leniency | Line |
|---|---|---|---|---|---|---|
| `sessionId` | `SessionId` (string) | yes | no | none | **strict** | `:976` |
| `title` | `MaybeUndefined<String>` | no | yes | `serde_as(deserialize_as = "DefaultOnError<MaybeUndefined<_>>")`, `serde(default, skip_serializing_if = "MaybeUndefined::is_undefined")` | lenient: malformed means absent | `:981-984` |
| `description` | `MaybeUndefined<String>` | no | yes | same as `title` | lenient | `:990-993` |
| `capabilities` | `MaybeUndefined<SubagentSessionCapabilities>` | no | yes | same as `title` | lenient | `:1000-1003` |
| `state` | `MaybeUndefined<StateUpdate>` | no | yes | same as `title` | lenient | `:1010-1013` |
| `cwd` | `MaybeUndefined<PathBuf>` (string) | no | yes | `serde(default, skip_serializing_if = "MaybeUndefined::is_undefined")` only, no `DefaultOnError` | **strict**: malformed fails the whole update | `:1020-1021` |
| `additionalDirectories` | `MaybeUndefined<Vec<PathBuf>>` | no | yes | same as `cwd`, no `VecSkipError` | **strict**: a non-array or **any** bad entry fails the whole update | `:1029-1030` |
| `_meta` | `MaybeUndefined<Meta>` (`Meta` = JSON object, `v1/ext.rs:14`) | no | yes | `DefaultOnError<MaybeUndefined<_>>`, `serde(default, rename = "_meta", skip_serializing_if = ...)` | lenient. A non-object value is malformed | `:1037-1044` |

- `MaybeUndefined::default()` is `Undefined` (`serde_util.rs:610-621`), so "lenient" here means a malformed value decodes as **omitted**, not as `null`.
- Tests:
  - `client.rs:5416-5438`: `null` round-trips for title, description, capabilities, state and `_meta`; `false` decodes as omitted.
  - `client.rs:5263-5274`: `cwd: false`, `additionalDirectories: "/tmp/wt"` and `["/tmp/wt", false]` are all errors.
  - `client.rs:5195-5200`: invalid title or description decodes as omitted.
- The schema agrees: `x-deserialize-default-on-error: true` appears on title, description, capabilities, state and `_meta`, and is absent on `cwd` and `additionalDirectories` (`schema.unstable.json:6130-6195`). `additionalDirectories.items` is `{type: string}`, with no absolute-path constraint.
- Why upstream keeps `cwd` and `additionalDirectories` strict: the spec repo's `AGENTS.md` "Schema rules" say to keep a field strict when the receiver acts on its value. The client uses these for the child's root set and the terminal default cwd (`client.rs:1016-1019`, `:1028`).

### `SessionMessage` (`client.rs:249-308`; schema `:6196-6273`)

Struct attributes: same as `SubagentUpdate` (`client.rs:250-255`).

| Field | Rust type | Required | Tri-state | serde attributes | Leniency | Line |
|---|---|---|---|---|---|---|
| `messageId` | `MessageId` (string, `client.rs:2075`) | yes | no | none | **strict**: missing, `null` or non-string fails (test `:413-436`) | `:258` |
| `senderSessionId` | `MaybeUndefined<SessionId>` | no | yes | `DefaultOnError<MaybeUndefined<_>>`, `default`, `skip_serializing_if = is_undefined` | lenient: malformed means absent (test `:614-625`) | `:262-265` |
| `recipientSessionId` | `MaybeUndefined<SessionId>` | no | yes | same | lenient | `:269-272` |
| `senderMessageId` | `MaybeUndefined<MessageId>` | no | yes | same | lenient (test `:668-677`) | `:278-281` |
| `recipientMessageId` | `MaybeUndefined<MessageId>` | no | yes | same | lenient | `:287-290` |
| `content` | `MaybeUndefined<Vec<ContentBlock>>` | no | yes | `DefaultOnError<MaybeUndefined<VecSkipError<_>>>`, `default`, `skip_serializing_if = is_undefined` | lenient: a non-array (`false`, `{}`) means absent (tests `:437-447`, `:488-495`). Invalid items are skipped | `:293-296` |
| `_meta` | `MaybeUndefined<Meta>` | no | yes | `DefaultOnError<MaybeUndefined<_>>`, `default`, `rename = "_meta"`, `skip_serializing_if` | lenient (test `:488-495`) | `:300-307` |

### `SessionMessageChunk` (`client.rs:203-222`; schema `:6274-...`)

| Field | Rust type | Required | Tri-state | serde attributes | Leniency | Line |
|---|---|---|---|---|---|---|
| `messageId` | `MessageId` | yes | no | none | **strict** (test `:527-546`) | `:212` |
| `content` | `ContentBlock` (a single object) | yes | no | none | **strict**: missing, `null` or `42` fails (test `:527-546`) | `:214` |
| `_meta` | `Option<Meta>` | no | **no** (omitted ≡ `null`) | `serde_as(deserialize_as = "DefaultOnError")`, `serde(default, rename = "_meta")`. `skip_serializing_none` drops `None` on encode | lenient: malformed means `None` | `:218-221` |

- Test `client.rs:547-557`: input `_meta: null` re-encodes as omitted.
- Test `client.rs:559-568`: identity fields (`senderSessionId` and the others) on a chunk are ignored and dropped on re-encode, because unknown fields are ignored.

### Capabilities

`SubagentSessionCapabilities` (`client.rs:1134-1157`; schema `:5725`) has no required fields.

| Field | Rust type | serde | Leniency | Line |
|---|---|---|---|---|
| `cancel` | `Option<SessionCancelCapabilities>` | `DefaultOnError`, `default` | lenient: `true` or `false` means `None` (test `client.rs:5655-5664`) | `:1145-1148` |
| `_meta` | `Option<Meta>` | `DefaultOnError`, `default`, `rename = "_meta"` | lenient | `:1152-1156` |

- `SessionCancelCapabilities` (`client.rs:1191-1208`; schema `:5749`) has only `_meta: Option<Meta>`, which is lenient (`:1203-1207`). The struct has no `rename_all`, which is irrelevant since its only field is renamed explicitly.
- `SubagentCapabilities` (`client.rs:3663-3678`; schema `:6958`) has only `_meta: Option<Meta>`, which is lenient (`:3673-3677`).
- `ClientCapabilities.subagents: Option<SubagentCapabilities>` (`client.rs:3522-3526`) uses `DefaultOnError` and `default`, so it is lenient. Omitted and `null` are equal, and `None` is omitted on encode (test `client.rs:5646-5653`). Schema: `anyOf [SubagentCapabilities, null]` with `x-deserialize-default-on-error` (`schema.unstable.json:6690`).

### `StateUpdate` (`client.rs:1239-1261`; schema `:5761-5886`)

`#[serde(tag = "state", rename_all = "snake_case")] #[non_exhaustive]`. The discriminator field is **`state`**.

| `state` value | Variant / payload struct | Payload fields | Line |
|---|---|---|---|
| `running` | `Running(RunningStateUpdate)` | `_meta: Option<Meta>` (lenient) | `:1246`, `:1271-1279` |
| `idle` | `Idle(IdleStateUpdate)` | flattened `stopReason` group, `usage`, `_meta`. See below | `:1248`, `:1308-1327` |
| `requires_action` | `RequiresAction(RequiresActionStateUpdate)` | `_meta: Option<Meta>` (lenient) | `:1250`, `:1591-1599` |
| `unknown` | `Unknown(UnknownStateUpdate)` | `_meta: Option<Meta>` (lenient) | `:1254`, `:1640-1648` |
| any other string | `#[serde(untagged)] Other(OtherStateUpdate)` | `state: String` plus `#[serde(flatten)] fields: BTreeMap<String, Value>` | `:1259-1260`, `:1674-1680` |

- **How `Other` keeps payloads.** The hand-written `Deserialize` (`client.rs:1695-1715`) reads the whole object as a map and removes `state`. It fails if `state` is missing or not a string. It **fails if `state` is a known value** (`running`, `idle`, `requires_action` or `unknown`, `:1717-1723`). Otherwise it keeps **every remaining field, including `_meta`**, in `fields`. On serialize, `state` and the flattened `fields` are written back (round-trip tests `client.rs:5332-5345`).
- **Malformed known state.** `Other` refuses known discriminators, so a known state that fails its own payload never becomes `Other`. The `StateUpdate` decode fails. On `SubagentUpdate.state`, `DefaultOnError` then makes the field **omitted** (`Undefined`).
  - Every payload field of the known states is itself lenient, so in practice the failure cases are a non-object, a missing `state` or a non-string `state`. Test `client.rs:5606-5617`: `"running"` (a bare string), `{}` and `{"state": 4}` each fail as a standalone `StateUpdate`, and decode as an omitted `state` inside `SubagentUpdate`.
  - A known state with a malformed `_meta` decodes with `_meta = None` (test `client.rs:5588-5599`).
- Extra unknown fields beside a known state, such as `{"state":"running","foo":1}`, are ignored and dropped on re-encode. Serde ignores unknown fields; no `deny_unknown_fields` is set.

### `IdleStateUpdate` (`client.rs:1297-1327`)

| Field | Rust type | serde | Leniency | Line |
|---|---|---|---|---|
| `stopReason` group | `Option<IdleStopReason>` | `#[serde(flatten)]` | omitted, `null` or malformed means `None`. See below | `:1310-1311` |
| `usage` | `Option<Box<Usage>>` | `#[cfg(feature = "unstable_end_turn_token_usage")]`, `DefaultOnError`, `default` | lenient: malformed means `None` | `:1315-1319` |
| `_meta` | `Option<Meta>` | `DefaultOnError`, `default`, `rename = "_meta"` | lenient | `:1323-1326` |

- **Stop-reason fallback.** The field carries **no `DefaultOnError` attribute**. The fallback is documented on the type: "An omitted, `null`, or malformed `stopReason` means not reported" (`client.rs:1299`). Tests confirm it:
  - `client.rs:5473-5485`: omitted, `null` and `true` each give `Idle` with no stop reason, and `_meta` is kept.
  - `client.rs:5618-5623`: `false` and `null` give no stop reason.
- *Hypothesis on the mechanism (not cited from this repo):* serde deserializes a flattened `Option<T>` with `T::deserialize(..).ok()`, so any failure becomes `None`.
- Schema: `IdleStateUpdate` (`schema.unstable.json:5917-6105`) gets a synthesized `stopReason` property `{type: ["string","null"], x-deserialize-default-on-error: true}` and a `"none"` branch for an omitted or `null` `stopReason` (`:6095`). The transform that adds them is `client.rs:1360-1401`.
- `Usage`:
  - Rust v1 `Usage` is at `agent.rs:4835-4842`, itself behind `unstable_end_turn_token_usage`.
  - Wire (`schema.unstable.json:4394`): `totalTokens`, `inputTokens` and `outputTokens` are required u64. `thoughtTokens`, `cachedReadTokens`, `cachedWriteTokens` and `_meta` are optional and lenient. A `usage` that lacks a required field is malformed and decodes as `None`.
  - Without the feature, Rust doesn't model `usage`, and drops it rather than keeping it in `Other` (test `client.rs:5555-5572`).
  - `schema.unstable.json` includes `usage` unconditionally.

### `IdleStopReason` (`client.rs:1415-1440`). Inlined into `IdleStateUpdate` in the schema, with no `$defs` entry of its own

`#[serde(tag = "stopReason", rename_all = "snake_case")] #[non_exhaustive]`. The discriminator field is **`stopReason`**, flattened beside `state`, `usage` and `_meta` in the same object.

| `stopReason` value | Variant | Payload |
|---|---|---|
| `end_turn` | `EndTurn` | none |
| `max_tokens` | `MaxTokens` | none |
| `max_turn_requests` | `MaxTurnRequests` | none |
| `refusal` | `Refusal` | none |
| `cancelled` | `Cancelled` | none |
| `error` | `Error(ErrorStopReason)` | `error: Option<Box<Error>>` |
| any other string | `#[serde(untagged)] Other(OtherStopReason)` | `stopReason: String` plus flattened `fields` |

- The first five are exactly v1 `StopReason` (`agent.rs:4809-4828`).
- `ErrorStopReason` (`client.rs:1454-1470`; schema `:5899`) has one field:
  - `error: Option<Box<Error>>`, with `DefaultOnError` and `default`.
  - `Error` is the JSON-RPC error object: `code` (integer, required), `message` (string, required) and `data` (any, optional, lenient) (schema `:4449`).
  - Omitted, `null` or malformed (`"failed"`) gives `Error` with `error = None`, and the stop reason stays `error` (test `client.rs:5444-5459`).
  - `ErrorStopReason` has **no `_meta`**. The snapshot's `_meta` belongs to `IdleStateUpdate`.
  - Agents SHOULD include `error` (`client.rs:1465`).
- `OtherStopReason` (`client.rs:1493-1506`) has a hand-written `Deserialize` (`:1524-1548`). It:
  - reads the object as a map;
  - removes `stopReason`, which must exist and be a string;
  - **rejects known values** (`end_turn`, `max_tokens`, `max_turn_requests`, `refusal`, `cancelled`, `error`, `:1550-1558`);
  - then strips `stopReason`, `usage` and `_meta` from `fields` (`remove_idle_state_update_fields`, `:1560-1566`).
  - The constructor applies the same strip (`:1512-1521`).
  - So `Other` keeps every remaining field. `usage` and `_meta` stay on the snapshot.
  - Round-trip tests: `client.rs:5487-5510` (`{"state":"idle","stopReason":"_paused","resumeAfter":30,"_meta":{...}}` gives `Other("_paused", {resumeAfter:30})` with the snapshot `_meta`, and re-encodes identically) and `client.rs:5555-5565`.
- **Malformed or known-but-invalid stop reason.** A non-string `stopReason` fails both the tagged and the untagged path, so the flattened `Option` becomes `None`.
  - A known stop reason can't be read as `Other` (test `client.rs:5512-5552`).
  - In practice no known stop reason fails its own payload. The unit variants ignore extra fields, and `ErrorStopReason.error` is lenient. So "known-but-invalid" has no reachable trigger today, but the rule ("never `Other`; it falls to no stop reason") holds by construction.
- Extra fields beside a known stop reason are dropped. Example: `"error": {...}` beside `"stopReason":"end_turn"` decodes as plain `EndTurn` (test `client.rs:5460-5471`).

### `ToolCallContent::Session` / `SessionReference` (`tool_call.rs:536-611`; schema `:647`, `:1131`)

- `ToolCallContent` is `#[serde(tag = "type", rename_all = "snake_case")] #[non_exhaustive]` (`tool_call.rs:543-548`). The variant `Session(SessionReference)` sits behind `unstable_subagents` (`:564-565`). Discriminator `"type": "session"`.
- `SessionReference` (`:591-611`) is `camelCase`, `skip_serializing_none`:
  - `sessionId: SessionId`: required, strict (`:600`);
  - `_meta: Option<Meta>`: `DefaultOnError`, `default`, `rename = "_meta"`. Lenient, omitted ≡ `null` (`:606-610`).
- Wire: `{"type":"session","sessionId":"sess_child_1"}` (test `tool_call.rs:861-875`).
- Context: Rust `ToolCall.content` and `ToolCallUpdate.content` are decoded with `DefaultOnError<VecSkipError<_>>` (`tool_call.rs:56-59`, `:288-291`), so a Rust receiver skips individual undecodable content items.

### Stable vs unstable

None of these types is in `schema/v1/schema.json` (zero matches for `SessionReference`, `SubagentUpdate`, `StateUpdate`, `subagents` and `session_message`). All are in `schema/v1/schema.unstable.json` only.

## SDK implications

- Every type lands in `:acp-model`, package `com.agentclientprotocol.model`, `@UnstableApi`, as the spec's "Model layer" already places them.
- The existing Kotlin types the spec names exist (Kotlin SDK):
  - `MessageId`: `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/Types.kt:149`;
  - v1 `Usage`: `model/Requests.kt:698`;
  - `JsonRpcError`: `rpc/JsonRpc.kt:111`.
- Test observations a conforming implementation should reproduce. Each mirrors a Rust test cited above.
  - `SubagentUpdate`:
    - `{sessionId, title:false, description:false}` decodes as `{sessionId}`;
    - `{sessionId, cwd:false}`, `{sessionId, additionalDirectories:"/x"}` and `{sessionId, additionalDirectories:["/x", false]}` each fail the update;
    - `null` on each of the 7 patch fields round-trips as `null`.
  - `SessionMessage`:
    - `content:false` or `content:{}` decodes as absent;
    - `content:null` and `content:[]` round-trip **distinctly**;
    - `recipientSessionId:42` decodes as absent;
    - `messageId` that is `null` or `42` fails.
  - `SessionMessageChunk`:
    - `_meta:null` re-encodes as omitted;
    - identity fields on a chunk are dropped;
    - missing, `null` or non-object `content` fails.
  - `StateUpdate` inside `SubagentUpdate`:
    - `"running"`, `{}` or `{"state":4}` decodes as an absent `state`;
    - `{"state":"paused","retryAt":42}` round-trips as `Other`;
    - `{"state":"unknown","_meta":false}` decodes as `Unknown` with no meta.
  - `Idle`:
    - `stopReason` set to `true`, `false` or `null` gives no stop reason, and `_meta` is kept;
    - `{"stopReason":"error","error":"failed"}` gives `Error(error=null)`;
    - `{"stopReason":"_paused","resumeAfter":30,"_meta":{..},"usage":{..}}` gives `Other` with only `resumeAfter`, and `usage` and `_meta` on the snapshot;
    - `{"stopReason":"end_turn","error":{..}}` gives `EndTurn`, and `error` is dropped.
  - Capabilities:
    - `{"cancel":true}`, `{"cancel":false}` and `{"cancel":null}` give `cancel = null`;
    - `clientCapabilities.subagents: null` equals omitted.
  - `ToolCallContent`: `{"type":"session","sessionId":"s"}` round-trips.

## Discrepancies

**Upstream sources against each other:** no substantive conflict found. Minor points:

- The RFD is silent on leniency (`DefaultOnError` and `VecSkipError`). Leniency exists only in the Rust models and as `x-deserialize-*` extensions in the schema, and those two agree field by field.
- The absolute-path MUST for `cwd` and `additionalDirectories` (RFD `:304`, `:307-308`) is prose only. Neither the schema (`type: string`) nor Rust (`PathBuf`) enforces it.
- The RFD makes `usage` conditional on "the draft end-turn usage feature" (`:852-853`), and Rust gates it with `unstable_end_turn_token_usage`. `schema.unstable.json` lists it unconditionally. Kotlin has no feature gates, so the spec's choice (always model it) matches the unstable schema.
- The JSON schema doesn't express the `[]` = clear semantics for `SessionMessage.content` except in its description. On the wire, `[]` and `null` are distinct values.

**Upstream vs the spec's "Model layer" (`docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md:96-181`, Kotlin SDK):**

1. **Revision.** The spec records `c392d7e9`. The current revision is `13b76582`. No subagent model, test or schema entry changed between them.
2. **Rust line numbers** (spec `:13-19`, `:129-130`, `:137`, `:153`, `:158`) are stale in `client.rs`:

   | Item | Spec says | Now |
   |---|---|---|
   | `SessionMessageChunk` | `:200` | `:210` |
   | `SessionMessage` | `:246` | `:256` |
   | `SubagentUpdate` | `:964` | `:974` |
   | `SubagentUpdate.state` | `:1003` | `:1013` |
   | `SubagentSessionCapabilities` | `:1131` | `:1141` |
   | `SessionCancelCapabilities` | `:1187` | `:1197` |
   | `StateUpdate` | `:1234` | `:1244` |
   | `IdleStateUpdate` | `:1298` | `:1308` |
   | `IdleStopReason` | `:1410` | `:1420` |
   | `ClientCapabilities.subagents` | `:3389` | field `:3526`, struct `:3492` |

   `tool_call.rs:564-565` and `:598` are unchanged.
3. **RFD stage.** The spec says "The RFD is an unstable draft" (`:10`). Since 2026-11-13 it is in the **Preview** group (`docs/docs.json:239-240`; RFD revision history `docs/rfds/subagents.mdx:1650-1652`). Preview means "fully implemented and ready for broad review before completion" (`docs/rfds/about.mdx:51`). It is still not Completed, and it is still unstable for v1, behind `unstable_subagents` (RFD `:1293-1295`).
4. **RFD line numbers** cited in the spec's "What the spec requires" section shifted after `:918` (-1 for `:922-1243`, +2 after `:1246`). For example, the spec's `:1240-1242` is now `:1239-1241`. The "Model layer" citations (`:609-665`) are unchanged.
5. **Field names, optionality, tri-state status, leniency, discriminators and type names:** **no differences.** Each "Model layer" claim was checked against the code and tests cited in "Details", including:
   - `cwd` and `additionalDirectories` strict;
   - `content` `VecSkipError` with `null`/`[]` clearing;
   - chunk `_meta` plain optional;
   - `Other` vs the real `Unknown`;
   - `IdleStopReason.Other` stripping `stopReason`, `usage` and `_meta`;
   - a malformed known state decoding as an absent `state`;
   - `usage` lenient;
   - `ToolCallContent` discriminator `session`.

**Precision notes** (consistent with the spec, but the spec doesn't state them; a programmer could get them wrong):

- **`content: []` vs `null`.** Both clear semantically, but Rust decodes them to different values (`Value([])` vs `Null`) and re-encodes each as it came (`client.rs:404-408`, `:449-465`). Collapsing `[]` into `null` would break round-trip parity.
- **All-invalid `content` array.** `VecSkipError` turns `[{"type":"bogus"}]` into `Value([])`, so such an update clears the stored content rather than leaving it unchanged. This is inferred from the `DefaultOnError<MaybeUndefined<VecSkipError<_>>>` composition at `client.rs:293`. No upstream test covers it.
- **`OtherStateUpdate` keeps `_meta`** in its `fields`, unlike `OtherStopReason`. Only `state` is removed (`client.rs:1701-1713`).
- **Extra fields are dropped.** Extra unknown fields beside a known state or known stop reason are dropped, not preserved (`client.rs:5460-5471`).
- **Stop-reason fallback mechanism.** It comes from flattening an `Option`, not from a `DefaultOnError` attribute (`client.rs:1310-1311`). The behavior is the same, but a Kotlin implementation must make the fallback explicit.
- **`usage` without the feature.** With `unstable_end_turn_token_usage` off, Rust drops `usage` (`client.rs:5568-5571`). With it on, which is the schema's view, `usage` is a snapshot field. The spec follows the "on" view.

## Open questions

- **v2 follow-up, out of scope.** At this revision, Rust v2 `StateUpdate` has `Unknown` as a real, ungated state, with `Other` for unrecognized values (`agent-client-protocol-schema/src/v2/client.rs:1606-1619`; RFD `:920-921`, `:1293-1295`). The spec notes that Kotlin v2's `StateUpdate.Unknown` currently means "unrecognized". That naming will need revisiting when v2 subagents are done. Route to whoever owns the v2 follow-up.
- **Not upstream, for awareness.** The working tree currently has an uncommitted, staged rename of `model/v2/MaybeUndefined.kt` to `model/MaybeUndefined.kt`, plus related modified files. Another agent is presumably doing the spec's first "Model layer" step. I did not inspect it or touch it.
