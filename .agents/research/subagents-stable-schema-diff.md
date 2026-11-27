# Do the v1 subagent model types in `:acp-model` match the stable v1 schema, so `@UnstableApi` can be dropped with no wire change?

**Sources checked:** `agent-client-protocol` @ `01a54c84f39508f73fab4d41362baf51e1320374` (2026-11-22, `git pull --ff-only` said "Already up to date"). It contains the stabilization commit `9d44c77a` (2026-11-20, "feat(schema): stabilize subagent sessions (#2939)"). This repo (`acp-kotlin-sdk`) @ `0d98ac0` (branch `eugenethedev/subagents`, clean tree), read only. Rust reference SDK not consulted, because the question is about schema shape.
**Confidence:** high. A field-by-field programmatic comparison (descriptions stripped) of the stable `schema/v1/schema.json` at HEAD against `schema/v1/schema.unstable.json` at `c392d7e9`, which our models were written against, finds exactly one shape difference: `usage` on the idle state. The Rust models and the stable page agree with the schema.

All upstream paths are in `agent-client-protocol` at `01a54c84`. Paths marked "Kotlin" are in this repo, relative to `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/`.

## Answer

Yes, with one exception. Every v1 subagent wire shape our models implement is in the stable v1 schema with the same wire names, required fields, nullability, discriminators, open-union fallbacks and leniency markers: `clientCapabilities.subagents`, `subagent_update`, `session_message`, `session_message_chunk`, `StateUpdate` with its four states and its `other` fallback, the idle `stopReason` union with its `other` and `none` branches, `ErrorStopReason`, `SubagentSessionCapabilities`/`SessionCancelCapabilities`, and `ToolCallContent` `session` (`SessionReference`).

`ToolCallContent.Session` and `session_message*` **are** in the stable schema.

The exception is **`StateUpdate.Idle.usage`**. The stable `IdleStateUpdate` has no `usage`, which upstream keeps unstable under the Draft "end-turn token usage" RFD. Kotlin can drop `@UnstableApi` from `StateUpdate`/`Idle` without a wire change only if `Idle.usage` keeps a property-level `@property:UnstableApi`, as `PromptResponse.usage` already does. `Usage` itself must stay `@UnstableApi`.

Two API-level follow-ons are not wire questions:

- `MaybeUndefined` is `@UnstableApi`, and the stable v1 types expose it.
- `MessageId` is `@UnstableApi`, but upstream it is a stable schema type.

Both need a decision. Upstream's earlier changes since `c392d7e9` (gates removed, `BTreeMap` replaced by `IndexMap` in the `Other` payloads, description text) change no wire shape. No `model.v2.conversion` code is affected.

## Requirements

| # | Requirement | Tier | Binds | Stable/unstable | Citation |
|---|-------------|------|-------|-----------------|----------|
| 1 | Agent MUST NOT send `subagent_update`, `session_message` or `session_message_chunk` unless the Client advertised `subagents`. It sends `session` tool content only to such a Client | MUST NOT, capability:`subagents` | agent | **stable** v1 | `docs/protocol/v1/subagents.mdx:59`; `docs/protocol/v1/tool-calls.mdx:358`; schema descriptions `schema/v1/schema.json:4015`, `:4031`, `:4047`, `:595` |
| 2 | `clientCapabilities.subagents` is optional and nullable. Omitted or `null` means unsupported, `{}` means supported | wire shape | client | stable | `subagents.mdx:52`; `schema.json:5622` (`anyOf [SubagentCapabilities, null]`, `x-deserialize-default-on-error`) |
| 3 | `SubagentUpdate`: `sessionId` is required. Every other field is a tri-state patch: omitted = unchanged, `null` = clear, value = replace | wire semantics | both | stable | `subagents.mdx:97-131`; `schema.json:5184-5249`, `required: ["sessionId"]` |
| 4 | `cwd` and each `additionalDirectories` entry MUST be absolute. This is prose only. The schema type is a plain `string` | MUST | agent | stable | `subagents.mdx:122-129`, `:230`; `schema.json:5230`, `:5234` |
| 5 | `SessionMessage`: `messageId` is required. Participant ids, references, `content` and `_meta` are tri-state patches. `content: []` also clears | wire semantics | both | stable | `docs/protocol/v1/prompt-turn.mdx:40-60`; `schema.json:5250-5327` |
| 6 | `SessionMessageChunk`: `messageId` and a single `content` block are required. Chunk `_meta` is local, and omitted ≡ `null` | wire shape | agent | stable | `prompt-turn.mdx:47-52`; `schema.json:5328-5355`, `required: ["messageId","content"]` |
| 7 | Clients keep an unrecognized state or stop reason together with its payload. Values starting with `_` are implementation-specific | SHOULD-level statement (plain prose, no keyword) | client | stable | `subagents.mdx:378`; schema `title: "other"` branches `schema.json:4895`, `:5072` |
| 8 | `ToolCallContent` `type: "session"`: `sessionId` is required and MUST already be known. `_meta` is optional and nullable, omitted ≡ `null` | MUST (known id), wire shape | agent | stable | `tool-calls.mdx:336-358`; `schema.json:594-608`, `:1084-1103` |
| 9 | Idle `usage` | MAY (optional) | agent | **unstable** (Draft RFD `rfds/end-turn-token-usage`, `docs/docs.json:208` in the "Draft" group at `:201`) | `schema/v1/schema.unstable.json:6306`; absent from stable `IdleStateUpdate` `schema.json:4983-5159`; RFD `docs/rfds/subagents.mdx:1310-1312` |
| 10 | RFD stage | — | — | **Completed** (`docs/docs.json:248` group, `rfds/subagents` at `:274`); revision history `docs/rfds/subagents.mdx:1720` | — |

## Details

### Per-item comparison

Columns: stable schema location (`schema/v1/schema.json`), Kotlin location, and verdict. "Leniency" means the schema's `x-deserialize-default-on-error` / `x-deserialize-skip-invalid-items` markers. The spec repo's `AGENTS.md` ("Schema rules") calls them SDK leniency, not protocol behavior.

| # | Item | Stable schema | Wire shape (stable) | Kotlin | Verdict |
|---|------|---------------|---------------------|--------|---------|
| 1 | `ClientCapabilities.subagents` | `:5622` (in `ClientCapabilities` `:5587`) | optional, `anyOf [SubagentCapabilities, null]`, lenient. Omitted ≡ `null` | `Capabilities.kt:98-100`, `@property:UnstableApi`, `LenientSubagentCapabilitiesSerializer` (`Subagents.kt:119-139`): `null` and malformed decode to `null`, and `null` is omitted on encode (`explicitNulls = false`) | MATCH |
| 2 | `SubagentCapabilities` | `:5794` | object. Only `_meta` (`object\|null`, lenient). No required fields | `Subagents.kt:33-37`, serializer `:76-86` (`lenientMeta`) | MATCH |
| 3 | `SessionUpdate` discriminator `subagent_update` | `:4015-4029` | tag `sessionUpdate`, `allOf SubagentUpdate` | `SessionUpdate.kt:301-312`; encode `:499-504`, decode `:592-595` | MATCH |
| 4 | `SubagentUpdate.sessionId` | `:5188` | required `SessionId` (string), strict | `SubagentSessionUpdates.kt:29` (`requiredString`) | MATCH |
| 5 | `.title` / `.description` | `:5196`, `:5201` | `string\|null`, optional, lenient | `MaybeUndefined<String>`, `lenientMaybeString` (`SubagentSessionUpdates.kt:30-31`, `:111-116`) | MATCH |
| 6 | `.capabilities` | `:5206` | `anyOf [SubagentSessionCapabilities, null]`, lenient | `decodeMaybeUndefined` (`:32`) | MATCH |
| 7 | `.state` | `:5218` | `anyOf [StateUpdate, null]`, lenient | `decodeMaybeUndefined` (`:33`) | MATCH |
| 8 | `.cwd` | `:5230` | `string\|null`, **no** leniency marker (strict) | `strictMaybeString` (`:34`, `:122-127`) | MATCH |
| 9 | `.additionalDirectories` | `:5234` | `array\|null` of `string`, **no** leniency marker (strict, no skip-invalid) | `strictMaybeStringList` (`:35`, `:134-142`) | MATCH |
| 10 | `SubagentUpdate._meta` | `:5241` | `object\|null`, lenient, tri-state ("Omitted means unchanged. `null` removes the metadata.") | `lenientMaybeMeta` (`:36`, `:148-154`) | MATCH |
| 11 | `SubagentSessionCapabilities` | `:4791` | `cancel`: `anyOf [SessionCancelCapabilities, null]`, lenient. `_meta` lenient. No required fields | `Subagents.kt:53-58`, serializer `:100-113` (`decodeOrNull`, `lenientMeta`) | MATCH |
| 12 | `SessionCancelCapabilities` | `:4815` | only `_meta`, lenient | `Subagents.kt:70-74`, `:88-98` | MATCH |
| 13 | `StateUpdate` union | `:4827-4952` | `anyOf` tagged on **`state`**: `running`, `idle`, `requires_action`, `unknown`, plus `title: "other"` (`:4895`), any string not in the known four, `additionalProperties: true` | `SubagentState.kt:33-93`, serializer `:171-215`. Known values go to their own variants, any other string goes to `Other(state, fields)`, and a missing or non-string `state` fails | MATCH |
| 14 | `RunningStateUpdate` / `RequiresActionStateUpdate` / `UnknownStateUpdate` | `:4953`, `:5160`, `:5172` | only `_meta`, lenient | `Running` `:41-43`, `RequiresAction` `:64-66`, `Unknown` `:75-77` (`lenientMeta`) | MATCH |
| 15 | `IdleStateUpdate` (stable) | `:4983-5159` | properties `_meta` (lenient) and synthesized `stopReason` (`string\|null`, lenient, `:4993`). `anyOf` of `stopReason` consts, `title: "other"` (`:5072`) and `title: "none"` (`null` or omitted). **No `usage`** | `Idle(stopReason, usage, _meta)` `:53-57`; decode `:181-185` | **DIFFERS**, additive only: Kotlin has the extra `usage` member, which exists only in `schema.unstable.json:6306` (lenient `anyOf [Usage, null]`). The stable fields match |
| 16 | `IdleStopReason` | no `$defs` entry; inlined in `IdleStateUpdate` (`:5004-5090`) | tag **`stopReason`**, flattened beside `state`/`_meta`: `end_turn`, `max_tokens`, `max_turn_requests`, `refusal`, `cancelled`, `error` (`allOf ErrorStopReason`), and `other` | `SubagentState.kt:106-158`; decode `:221-233`, encode `:235-256`. A non-string `stopReason` gives `null`. `Other` strips `state`, `stopReason`, `usage` and `_meta` | MATCH. Rust's `remove_idle_state_update_fields` strips `usage` even in a stable-only build (`agent-client-protocol-schema/src/v1/client.rs:2030-2034`; test `:6021-6044`) |
| 17 | `ErrorStopReason` | `:4965` | `error`: `anyOf [Error, null]`, lenient. No `_meta` | `IdleStopReason.Error(error: JsonRpcError?)` `:139-141`, `decodeOrNull` `:230`. `JsonRpcError(code, message, data?)` (`rpc/JsonRpc.kt:111-115`) matches `Error` `schema.json:3582` (`required: code, message`) | MATCH |
| 18 | `SessionUpdate` `session_message` | `:4031-4045` | tag `sessionUpdate` | `SessionUpdate.kt:342-352`; `:506-511`, `:596-599` | MATCH |
| 19 | `SessionMessage` fields | `:5250-5327` | `messageId` required. `senderSessionId`, `recipientSessionId`, `senderMessageId` and `recipientMessageId` are `anyOf [.., null]`, lenient. `content` is `array\|null` of `ContentBlock`, lenient + skip-invalid-items. `_meta` is lenient | `SubagentSessionUpdates.kt:56-79` (`lenientMaybeString`, `decodeMaybeUndefinedList` = VecSkipError, `lenientMaybeMeta`) | MATCH |
| 20 | `SessionUpdate` `session_message_chunk` | `:4047-4061` | tag `sessionUpdate` | `SessionUpdate.kt:370-376`; `:513-518`, `:600-603` | MATCH |
| 21 | `SessionMessageChunk` fields | `:5328-5355` | `messageId` and `content` (single `ContentBlock`) required, strict. `_meta` is `object\|null`, lenient, plain optional (not a patch) | `SubagentSessionUpdates.kt:81-100`. A non-object `content` fails, and `_meta` is `lenientMeta`, omitted when `null` | MATCH |
| 22 | `ToolCallContent` `session` | variant `:594-608` in `ToolCallContent` `:543`; `SessionReference` `:1084-1103` | tag `type` = `"session"`. `sessionId` required. `_meta` is `object\|null`, lenient | `ToolCall.kt:115-123` (`@SerialName("session")`, `LenientMetaSerializer`) | MATCH |
| 23 | `MessageId` | `:4067` | plain `string`. Used in stable `ContentChunk.messageId` and in the three subagent message types | `Types.kt:146-152` (`@UnstableApi` value class) | MATCH on the wire. API note below |
| 24 | `MaybeUndefined` usage | n/a (no wire form) | tri-state is expressed in the schema as "optional + nullable" plus the description text | `MaybeUndefined.kt:29`, `:79`, both `@UnstableApi` | n/a on the wire. API note below |

### What changed upstream since `c392d7e9`, and why none of it changes the wire

- `9d44c77a` removes the `unstable_subagents` Cargo feature and its `cfg` gates. It also rewrites doc comments and publishes the types in `schema.json`. The non-gate diff lines in `agent-client-protocol-schema/src/v1/{client,tool_call,agent}.rs` are all doc comments or `cfg` simplifications, with no serde attribute changes.
- Programmatic diff with descriptions stripped, `c392d7e9:schema/v1/schema.unstable.json` against HEAD `schema/v1/schema.json`: `SubagentCapabilities`, `SubagentSessionCapabilities`, `SessionCancelCapabilities`, `SubagentUpdate`, `SessionMessage`, `SessionMessageChunk`, `StateUpdate`, `Running/RequiresAction/UnknownStateUpdate`, `ErrorStopReason`, `SessionReference`, `MessageId`, `Error`, `ContentBlock`, the `subagents` property, the three `SessionUpdate` variants and the `session` `ToolCallContent` variant are all identical. Only `IdleStateUpdate` differs, by the removed `usage`.
- HEAD `schema.json` against HEAD `schema.unstable.json`: the same defs differ only in "See protocol docs" links (`/v1/` vs `/v1/draft/`), plus `usage` on `IdleStateUpdate`.
- `5a0e4a92` switched `OtherStateUpdate.fields` and `OtherStopReason.fields` from `BTreeMap` to `IndexMap`, which keeps key order (`client.rs:2133-2139`, `:1972-1978`). Kotlin's `JsonObject(obj - key)` already keeps insertion order, and JSON object key order has no meaning. No change is needed.

### Stable-page statements checked against the model

- The patch semantics and `capabilities` replace-whole behavior (`subagents.mdx:131-136`) match the KDoc at `SessionUpdate.kt:280-300`.
- The state union, the idle `stopReason` being "a stop reason or `error`, omitted or `null` = none reported" (`subagents.mdx:354-370`), and keeping unrecognized values (`:378`) match `SubagentState.kt`.
- The error snapshot example with `stopReason: "error"` + `error` (`subagents.mdx:380-400`) matches item 17.
- The `content`/`_meta` patch rules for session messages and chunk-local `_meta` (`prompt-turn.mdx:47-56`) match items 19 and 21.
- The `session` tool content rules (`tool-calls.mdx:336-358`) match item 22.

## SDK implications

### `@UnstableApi` annotations in `:acp-model` (non-v2) that are subagent-related, candidates to drop

| Annotation | Kotlin location | Action |
|---|---|---|
| `@UnstableApi` on `SubagentCapabilities` | `Subagents.kt:33` | drop |
| `@UnstableApi` on `SubagentSessionCapabilities` | `Subagents.kt:53` | drop |
| `@UnstableApi` on `SessionCancelCapabilities` | `Subagents.kt:70` | drop |
| `@property:UnstableApi` on `ClientCapabilities.subagents` | `Capabilities.kt:98` | drop |
| `@UnstableApi` on `SessionUpdate.SubagentUpdate` | `SessionUpdate.kt:301` | drop |
| `@UnstableApi` on `SessionUpdate.SessionMessage` | `SessionUpdate.kt:342` | drop |
| `@UnstableApi` on `SessionUpdate.SessionMessageChunk` | `SessionUpdate.kt:370` | drop |
| `@UnstableApi` on `StateUpdate` | `SubagentState.kt:33` | drop, **and add `@property:UnstableApi` to `Idle.usage`** (`SubagentState.kt:55`). This mirrors `PromptResponse.usage` (`Requests.kt:717-720`) |
| `@UnstableApi` on `IdleStopReason` | `SubagentState.kt:106` | drop |
| `@UnstableApi` on `ToolCallContent.Session` | `ToolCall.kt:115` | drop |

The KDoc "**UNSTABLE** … not part of the spec yet" blocks on these types must go too: `Subagents.kt:20-24`, `:39-43`, `:60-64`; `SessionUpdate.kt:~273-276` and the matching blocks above `SessionMessage`/`SessionMessageChunk`; `SubagentState.kt:17-21`, `:95-99`; `ToolCall.kt:104-108`. The `@OptIn(UnstableApi::class)` on the internal serializers becomes redundant but harmless (`Subagents.kt:76,88,100,119`; `SubagentSessionUpdates.kt:24,56,81,110,121,133,147`; `SubagentState.kt:171`). They still need it while `MaybeUndefined` stays unstable.

### Need a decision, because a stable type exposes them

- **`MaybeUndefined` + `update()`** (`MaybeUndefined.kt:29`, `:79`). The stable `SubagentUpdate`/`SessionMessage` expose `MaybeUndefined` in public constructor parameters and properties. If it stays `@UnstableApi`, every user of those stable types must still opt in. Upstream has no counterpart question, since `MaybeUndefined` is an SDK type with no wire form. In Rust the type is public in the schema crate (`agent-client-protocol-schema/src/v1/client.rs:25` imports `crate::{IntoMaybeUndefined, IntoOption, MaybeUndefined}`) and not feature-gated. *Hypothesis:* it should become stable in Kotlin too. This is a public-API decision for the orchestrator or user. v2 types that use it stay unstable through their own annotations.
- **`MessageId`** (`Types.kt:146`). Upstream it is a stable schema type, used by stable `ContentChunk.messageId` and the subagent messages. Its KDoc talks about `PromptRequest`/`userMessageId`, which no longer exist upstream (see Open questions). Dropping its annotation is needed for the stable subagent types to be usable without opt-in. The property-level annotations on the chunk `messageId`s are a separate, adjacent question.

### Must stay `@UnstableApi`

- `Usage` (`Requests.kt:696`): Draft RFD; absent from the stable schema.
- `Idle.usage`: see above.
- All other non-subagent annotations in the non-v2 files (elicitation, NES, fork, list, resume, providers, auth, model, config, plan v2, session info, usage update, etc.) are out of scope. They are not subagent-related.

### `model.v2.conversion`

Not affected:

- v1 `SubagentUpdate`/`SessionMessage`/`SessionMessageChunk` to v2 throws `removedV1EnumVariant` (`v2/conversion/SessionUpdateConversions.kt:349-356`).
- v1 `ToolCallContent.Session` to v2 is dropped as `null` (`v2/conversion/ToolCallConversions.kt:120`).
- Both files have a file-level `@OptIn(UnstableApi::class)` (e.g. `SessionUpdateConversions.kt:2`), so removing annotations from v1 types breaks nothing.
- Whether v1 subagent updates *should* now convert to v2 subagent types is a behavior question, outside this one.

### Tests

Existing serialization tests (`acp-model/src/commonTest/kotlin/com/agentclientprotocol/model/SubagentStateSerializationTest.kt`, `SubagentSessionUpdatesSerializationTest.kt`) cover the wire. Dropping annotations changes no JSON. `acp-model/api/acp-model.api` will show no signature change: annotations are not in the dump, unless the `Idle.usage` property-annotation split changes it. Run `apiDump`/`apiCheck` to confirm.

## Discrepancies

- **Kotlin vs stable schema:** `StateUpdate.Idle.usage` exists in Kotlin but not in the stable `IdleStateUpdate` (`schema.json:4983-5159`). It is present in `schema.unstable.json:6306`. Rust gates it behind `unstable_end_turn_token_usage` (`client.rs:1806`, `:1835`). A stable-only Rust build drops a received `usage` on re-encode (test `client.rs:6040-6043`), while Kotlin keeps it. This is the unstable extension, not a conflict in the stable shape.
- **Upstream sources against each other:** none found. The stable schema, the Rust models (`client.rs:174-189`, `:202-307`, `:1486-1549`, `:1647-2174`, `:3977`, `:4100`; `tool_call.rs:560`, `:588-597`) and the stable pages agree.
- **Spec wording:** the stable page has no keyword for keeping unrecognized states and stop reasons ("Clients keep …", `subagents.mdx:378`). The RFD had SDK guidance to preserve them. The Kotlin `Other` variants satisfy both.

## Open questions

- **API decision (orchestrator or user):** stabilize `MaybeUndefined`/`update()` and `MessageId` together with the subagent types? Without that, the "drop `@UnstableApi`" goal is only partial for users.
- **Adjacent, out of scope:** the stable v1 schema now also has `plan_update`, `plan_removed`, `session_info_update`, `usage_update`, `notice`, `compaction_update` and `compaction_summary_chunk` as `SessionUpdate` variants. `ContentChunk.messageId` is stable too (message-id RFD Completed; `docs/announcements/message-id-stabilized.mdx`, 2026-07-20). Kotlin still marks `PlanUpdateV2`, `PlanRemoved`, `SessionInfoUpdate`, `UsageUpdate` (`SessionUpdate.kt:184`, `:202`, `:247`, `:263`) and the chunk `messageId`s (`SessionUpdate.kt:95`, `:108`, `:121`) as unstable. Route these to a separate stabilization audit.
- **Adjacent, out of scope:** Kotlin's `PromptRequest.messageId` (`Requests.kt:431`) and `PromptResponse.userMessageId` (`Requests.kt:718`) are absent from both the stable and the unstable v1 `PromptRequest`/`PromptResponse` upstream: the properties are only `sessionId`, `prompt`, `_meta` and `stopReason`, `[usage]`, `_meta`. Upstream probably removed them. A researcher should confirm when and why.
- **Adjacent, out of scope:** the runtime `:acp` subagent APIs (`agent/AgentSubagents.kt`, `client/ClientSubagentSession.kt` and others reference `UnstableApi`) need the same stabilization review. That is a public-API decision.
- **Adjacent, out of scope:** the v2 conversion throws for v1 subagent updates. Whether it should now convert, since both versions have subagents in their default schemas, is a behavior question.
