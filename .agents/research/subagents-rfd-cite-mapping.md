# Mapping of the subagents-RFD line citations to the stable v1 pages

**Sources checked:** `agent-client-protocol` @ `01a54c84f39508f73fab4d41362baf51e1320374` (2026-11-22, `main`, `git pull --ff-only` reported "Already up to date"). The stable page `docs/protocol/v1/subagents.mdx` was last changed by `bde1e35f` (2026-11-20), `prompt-turn.mdx` by `bde1e35f`, `tool-calls.mdx` by `9d44c77a`, `initialization.mdx` by `19b2d237`. The old RFD revisions were read with `git show c392d7e9:docs/rfds/subagents.mdx` and `git show 13b76582:...`.
**Confidence:** high for the mapping (every stable range was read and compared with the RFD text). Medium for the "unchanged" judgement on a few reworded rules, which is flagged per row.

## Answer

- **The citations are into `13b76582`, not `c392d7e9`.** The design spec says so itself (`docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md:9-13`, `:2470-2472`). I checked it: `:1416-1417` is "Lose a child's activity feed ... without inventing an outcome" at `13b76582`, but "retain the parent report and do not synthesize a tree total" at `c392d7e9`. The "RFD range" column below is therefore the `13b76582` range. `c392d7e9` has 1727 lines, `13b76582` has 1737, and the latest RFD has 1820.
- **Every citation is in one file** except two KDoc mentions. Nothing under `AGENTS.md`, `CLAUDE.md`, `README.md` or tests cites the subagents RFD with line numbers.
  - Line-number citations: only `docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md` (about 110 refs, 65 distinct ranges, table A).
  - Two KDoc mentions with no line numbers: `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/RemoteClientSessionOperations.kt:42-43` and `:200` (table C).
  - About 35 more lines of the spec name "the RFD" without a line number (table D).
- **The stable pages carry almost every rule.** Only a handful of the cited ranges are "only in RFD": the fork rule, the v2 contrasts, per-item cancel, the FAQ follow-up and the test-plan line (table B).
- **Material changes since `13b76582`** that a pure renumbering would hide (details in table A, column "Changed", and in "Discrepancies"):
  1. Cost: reversed. A parent's cost now includes its descendants (`:1195-1225`).
  2. Late response: "MUST NOT restart cancelled work" is now "SHOULD NOT" (`:1188-1189`).
  3. Ungranted requests for a child: the page now defines the behavior (ignore an ungranted `session/cancel`, SHOULD reject anything else with `-32602`). Spec lines 2253-2255 and 462 say the RFD defines no such rule.
  4. `session` tool call content: on the stable pages the capability rule has no MUST keyword (`:663-665`, `:76-78`).
  5. Several phrases the design spec quotes as RFD text are no longer on the stable page (table E).
  6. The RFD sentence "SDKs may tolerate non-conforming Agents by buffering early traffic" was deleted (RFD changelog 2026-11-19). The design spec does not cite it.

## Citation format to use

Define once, in the spec's "Sources" section (replacing `docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md:9-13`), and use the short form everywhere:

```markdown
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
```

- In prose, write `(sub:86)` or `(sub:52-59, sub:525)`, in backticks as before. Replace `RFD` with "the spec" or "the subagents page" where the text says what the protocol requires.
- In Kotlin KDoc, use the full path with the heading: `docs/protocol/v1/subagents.mdx`, "Checking support".
- If the page changes later, bump the pinned revision and re-derive. `git log -1 -- docs/protocol/v1/subagents.mdx` must print `bde1e35f` for the numbers below to hold.
- The v1 draft copy `docs/protocol/v1/draft/subagents.mdx` is identical apart from `/draft/` links (diffed). Cite the stable page.

## Table A: ranges the stable page carries

Column "Spec line(s)" = line(s) of `docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md` where the range is cited. "RFD" = range at `13b76582`. "Changed" = **same** (same rule, same keyword), **reworded** (same meaning, different words) or **MATERIAL**. Replace the old range with the "Use" cell.

| # | Spec line(s) | RFD (`13b76582`) | What it says (short quote) | Use (stable) | Stable text | Changed |
|---|---|---|---|---|---|---|
| A1 | 64 | `:62-78` | "optional `subagents` object ... `{}` means supported"; "Agent **MUST NOT** send the updates defined by this RFD unless the Client advertised `subagents`" | `sub:52-59` | `sub:52` field optional and nullable, `{}` supported; `sub:59` MUST NOT send the three named updates. Also `init:237-243` | **MATERIAL (tier)**: RFD covers "the updates defined by this RFD" (incl. `session` content via `:663-665`). The page names three updates with MUST NOT, and says `session` content is sent "only to a Client that advertised it" (no keyword). See A24 |
| A2 | 64 | `:1002-1004` | v1 Clients without `subagents` get ordinary parent history, no child updates | `sub:525` | "A Client that did not advertise `subagents` gets the parent's history without `subagent_update`, `session_message`, or `session_message_chunk`" | same |
| A3 | 66 | `:89-93` | first update for an unknown `update.sessionId` announces ownership; later ones patch | `sub:86` | "The first `subagent_update` for a child `sessionId` announces that the parent created and owns the child. Later updates ... patch" | reworded |
| A4 | 66, 1033 | `:165-169` | outer `sessionId` is the immediate parent; "cannot reparent an existing session, refer to itself, or create a cycle" | `sub:142` | "A `subagent_update` cannot move an existing session to another parent, name its own outer session as the child, or create a cycle" | reworded (the quoted phrase is gone, see table E) |
| A5 | 68, 369 | `:95-98` | MUST send the announcing `subagent_update` before any request or notification bearing the child's id, incl. a live `session_message` naming it | `sub:88-93` (short-lived work: `sub:95`) | MUST list: child `session/update`, `session_message` naming the child, `session` content, and permission/elicitation/fs/terminal requests | same |
| A6 | 68 | `:195-199` | "MUST NOT guess parentage or temporarily announce a nested child under the root" | `sub:150` | "It **MUST NOT** guess the parent or announce a nested child under the root session in the meantime" | same |
| A7 | 69, 1033 | `:122-124` | `update.sessionId` unique on the connection, not reused for an unrelated session | `sub:97-101` (only `sessionId` required: `sub:131`) | "unique on the connection ... The Agent **MUST NOT** reuse it for an unrelated session" | reworded ("within the ACP connection" becomes "on the connection") |
| A8 | 72 | `:141-163` | patch semantics; `capabilities` replaced whole; `capabilities: null` / `{ "cancel": null }` revoke cancel | `sub:131-136` (labels: `sub:138`) | `sub:134`: "`capabilities: null` and `capabilities: {}` both permit nothing. ... an omitted or `null` `cancel` means unsupported, not unchanged" | reworded. **`_meta` patch rule is not on the page** (the spec line 70 lists `_meta`): only `rfd:139,141-145` and the schema |
| A9 | 75 | `:773-775` | v1 reports work state only through the parent association | `sub:332` | "The `state` field is the only report" | reworded. The v1/v2 contrast is only in the RFD (table B) |
| A10 | 76 | `:841-921` | work state: states, MUST `running`/`idle`, `unknown`, unrecognized values kept | `sub:330-420` (states `sub:354-370`; rules `sub:372-378`; error `sub:380-402`; unknown `sub:413-420`) | see rows A12-A15, A25, A27-A28 | reworded; sub-rules below |
| A11 | 80, 2178 | `:787-789` | "Work can start and stop many times"; idle does not end the association | `sub:352` | "There is no separate subagent lifecycle. Work can start and stop many times in one child, and `idle` does not end the association" | same |
| A12 | 81, 1594, 2179 | `:857-858` | MUST report `running`/`idle` when it observes foreground work start, resume or stop; SHOULD `requires_action`, `stopReason` | `sub:372-373` | same MUST/SHOULD | same |
| A13 | 82, 2176 | `:820-821` | v1 has no child prompt response | `sub:332` | "The Client sends no `session/prompt` for a child, so no response reports that the child's work stopped" | same |
| A14 | 82, 2176 | `:865-867` | a v1 child has no prompt response, so the `idle`/`error` snapshot is the only place the Client learns of failure | `sub:380` (and `sub:332`) | "This snapshot is the only place the Client learns that the child's work failed" | same |
| A15 | 473 | `:863-864` | `error` only for "a failure that ends the child's foreground work" | `sub:380` | "A failure that ends the child's foreground work is `idle` with `stopReason: "error"`" (the quote still matches) | same |
| A16 | 83 | `:428-597` | `session_message` upsert, participant ids, linking the two views, `session_message_chunk`, report only observed views | `pt:40-116` and `sub:274-285`, `sub:318-329` | prose in `pt:40-111` (upsert and patch `pt:86-92`, links and MUST NOT link by equal ids `pt:93-102`) | reworded (condensed). Note `pt:42`: the Agent "**MAY** report" messages once the Client advertised `subagents` |
| A17 | 86 | `:1067-1093` | restricted methods allowlist, responses still delivered, children not in `session/list` | `sub:422-436` | table `sub:426-430`; list `sub:432-436` | reworded; extended (see A26, A63) |
| A18 | 88 | `:1106-1157` | cancellation, cascade, closing a parent | `sub:438-485` (cancel `sub:438-463`, pending requests `sub:465-476`, closing `sub:478-485`) | rows A19, A30-A36, A51-A57 | reworded, with changes in A56 |
| A19 | 91, 458, 2187 | `:1131-1137` | a cancel racing with ended work "does not rewrite that work's outcome"; report cancellation from evidence, else `unknown` | `sub:455-456` | `sub:456` "does not change that work's outcome"; `sub:455` reports `cancelled` "when it observes that the work stopped. If it can no longer observe the child, it reports `unknown`" | reworded (phrases lost, table E) |
| A20 | 93 | `:959-1061` | replay and reconnect | `sub:493-533` (what the Client keeps `sub:497-508`, what the Agent sends `sub:510-519`, replayed history `sub:521-533`) | sub-rows A37-A41, A50, A65 | same |
| A21 | 201, 2180 | `:841` | "The v1 payload mirrors the v2 state object" | `sub:354-370` for the states; the "mirrors v2" claim is only in `rfd:838` | page lists the states, no v2 comparison | only-in-RFD for the v2 claim |
| A22 | 162 | `:609-665` | `ToolCallContent` `session` variant: display-only reference, MUST be known, replaces the whole array | `tc:336-360` (also `sub:314-316`) | `tc:350-352` "MUST already be known through ordinary session setup or a `subagent_update`"; `tc:355-357` display-only | same |
| A23 | 393, 2291 | `:76-78` | MUST NOT send the updates without the capability | `sub:59`, `init:241-242` | `init:241` "Agents **MUST NOT** send these updates without this capability" | **MATERIAL (tier)**, see A1/A24 |
| A24 | 402 | `:663-665` | "`session` content is covered by the `subagents` Client capability like the rest of this RFD" | `tc:357`, `sub:59` | `tc:357` "Agents send it only when the Client advertises the `subagents` capability" (no keyword); `sub:59` "sends `session` tool call content only to a Client that advertised it" | **MATERIAL (tier)**: the RFD ties it to the MUST NOT of `:76-78`, the stable pages state it without MUST. The SDK check in `RemoteClientSessionOperations` (throws) goes beyond the stated keyword |
| A25 | 459 | `:893-896` | `unknown` does not close or cancel, resolve requests or revoke capabilities; the Agent updates `capabilities` | `sub:419` | "`unknown` does not close or cancel the child, does not resolve its pending requests, and does not withdraw its capabilities. If a capability is no longer available, the Agent sends new `capabilities`" | same |
| A26 | 462 | `:1075-1079` | mutations: "prompting, queueing, steering, cancellation, closing, deletion, mode or configuration changes, and forking"; load/resume "not read-only" | `sub:430`, `sub:434` | `sub:430` lists prompt, load, resume, close, delete, set_mode, set_config_option "and every other request" as not permitted; `sub:434` load/resume. The queueing/steering/forking enumeration is gone | reworded |
| A27 | 473 | `:845-847` | `unknown` is "a nonterminal work state, not a work outcome" | `sub:367-370`, `sub:413-420` | "The Agent cannot currently determine foreground activity" (the "not a work outcome" phrase is gone) | reworded (table E) |
| A28 | 474, 489 | `:1416-1417` | lost feed on a live connection: "report `unknown` without inventing an outcome" | `sub:415`, `sub:420`, `sub:455`. The test-plan line is only in the RFD (table B) | `sub:415` "MUST report the `unknown` state"; `sub:420` "MUST NOT invent a final status for them" | reworded; line itself only in RFD (`rfd:1466-1467`) |
| A29 | 481, 488, 545, 1306 | `:1153-1157`, `:1154-1157`, `:1154-1156` | closing the parent cancels all descendant work, background children included; "does not ... require a new terminal-state notification" | `sub:480`, `sub:483` | `sub:480` "`session/close` on a parent session cancels the work of all its descendants, including children that keep running after the parent's own work ended"; `sub:483` "the Agent need not send a final update for each child" | reworded |
| A30 | 492 | `:1140-1143` | Client SHOULD mark a cancelled session's unfinished tool calls cancelled, and a descendant's after `idle(cancelled)` | `sub:463` | same SHOULD | same |
| A31 | 492 | `:1169-1171` | a descendant's requests are cleaned up once it reports `idle` + `cancelled` | `sub:472` | "Once that descendant reports `idle` with `stopReason: "cancelled"`, the Client handles its requests as if it had cancelled the descendant directly. Until then, the user can still answer them" | reworded |
| A32 | 493, 2284 | `:1135-1138` | "rather than inventing a cancelled outcome" | `sub:455` | "If it can no longer observe the child, it reports `unknown`" (phrase gone) | reworded (table E) |
| A33 | 575 | `:1112-1117` | cancelling also cancels the descendant work it waits on; other descendants up to the Agent | `sub:459` | same, plus "A child that does not advertise `cancel` is still stopped when an ancestor's cancellation covers it" | same (extended) |
| A34 | 581, 822 | `:1026-1034` | with replay the successful response is the freshness boundary; snapshots before it are history | `sub:508` (also `sub:501-502`, `sub:513-514`) | `sub:508` "The successful response is the boundary. ... historical. They **MUST NOT** permit the Client to send anything for the child or confirm that the child is working" | same |
| A35 | 596 | `:1031-1034` | current snapshots come after the response as ordinary patches, no extra announcement phase | `sub:508`, `sub:512`, `sub:514` | "The Agent sends the current `capabilities` and `state` after the response"; `sub:512` "the Agent need not repeat it after the response" | reworded |
| A36 | 600 | `:1009-1015` | on load/resume the Client MUST invalidate cached work state and grants; effective capabilities start empty; no mutations until success and a current grant | `sub:499-502` | "it **MUST** discard the state and capabilities it holds ... MUST NOT send `session/cancel` for a child until the request succeeds and a current `subagent_update` advertises `cancel`" | same |
| A37 | 610, 823 | `:1045-1048` | without replay, snapshots may arrive before or after the response; they are current; controls wait for success | `sub:507`, `sub:502` | `sub:507` "Nothing is replayed, so every `subagent_update` and every child update is current. They can arrive before or after the response" | same |
| A38 | 613 | `:1018-1021` | before live child traffic the ancestor chain is established parent-before-child during this load/resume | `sub:512` | "Ancestors first. ... parent before child. A replayed `subagent_update` counts" | same |
| A39 | 824 | `:1014-1016` | if the operation fails, state and authorization received during that attempt are invalidated | `sub:503` | "If the request fails, the Client discards the child state and capabilities it received during that request" | same |
| A40 | 2346 | `:1008-1016` | the Client MUST invalidate child state and grants on every load/resume | `sub:499-503` | see A36, A39 | same |
| A41 | 698 | `:667-694` | parent's task to a child: SHOULD be the incoming `session_message`, SHOULD NOT be `user_message_chunk`; no sender evidence means no `senderSessionId`, MUST NOT assume the parent | `sub:278-312` (rules `sub:282-285`); also `pt:105-116` | same SHOULD / SHOULD NOT / MUST NOT; `sub:312` description is not the task message | same |
| A42 | 699 | `:182-186` | one-shot children conform | `sub:146` | "Reuse is not required. An Agent whose runtime creates a new worker for each delegation announces a new child each time and sends nothing more for it once its work ends" | reworded |
| A43 | 761 | `:314-320` | unreported or cleared `cwd`/`additionalDirectories` is inherited from the immediate parent's effective value; inherited independently | `sub:232` | "the child's effective value is its immediate parent's current effective value. For a root session, that is the value from session setup. The two fields are inherited separately" | same |
| A44 | 764 | `:147-148` | clearing `cwd`/`additionalDirectories` makes the child inherit again | `sub:136` | same | same |
| A45 | 776, 2230 | `:392-426` | child `config_option_update` snapshots; reporting does not permit set | `sub:265-271` (after load: `sub:517`) | `sub:267` SHOULD report; `sub:269-270` no inference, no `set_config_option` | reworded |
| A46 | 776 | `:285-287` | a child's `current_mode_update`; no `set_mode` | `sub:272` | same | same |
| A47 | 793 | `:146` | clearing `state`: "unconfirmed, not idle" | `sub:135`, `sub:376` | "Clearing it leaves the child's activity unconfirmed. It does not report `idle`" | reworded (quote gone, table E) |
| A48 | 1306, 2452 | `:1174-1175` | closing the parent: the Client "cleans up their requests together with the parent's" | `sub:482` | "The Client handles the pending requests of the closed session's descendants together with the parent's own, as Pending requests describes" | reworded (table E) |
| A49 | 1590, 2083 | `:1163-1165`, `:1165-1167` | MUST answer the cancelled session's pending `session/request_permission` with `cancelled`; SHOULD dismiss elicitations with `cancel` | `sub:471` | same MUST / SHOULD | same |
| A50 | 1592 | `:1169-1173` | descendant keeps running keeps its requests; "the Client never denies a tool use of work that continues" | `sub:472` | "A descendant that keeps running keeps its requests." (the "never denies" sentence is gone) | reworded (table E) |
| A51 | 1593 | `:1184-1185` | "Idle alone is not a cancellation of unrelated background requests" | `sub:473` | "`idle` alone does not cancel the requests of unrelated work that continues" | reworded (table E) |
| A52 | 1595 | `:1131-1132` | cancel racing ended work "does not rewrite that work's outcome" | `sub:456` | "does not change that work's outcome" | reworded (table E) |
| A53 | 1607 | `:1188-1189` | late response must not restart cancelled work or authorize a new assignment | `sub:475` | "The Agent **SHOULD NOT** use it to restart cancelled or abandoned work, and **MUST NOT** use it to permit a new task for the same child" | **MATERIAL**: restart is now SHOULD NOT (was MUST NOT; RFD changelog 2026-11-20, `rfd:1719`). The "new assignment" rule stays MUST NOT |
| A54 | 1607, 2084 | `:1182-1184` | Client SHOULD dismiss an "interactive request" whose work ended | `sub:473` | "If the Client learns that the work behind a request has ended and the request no longer applies, it **SHOULD** dismiss the control and send the matching cancellation response" | reworded ("interactive request" dropped) |
| A55 | 2232 | `:1195-1225` | cost: "must not add up the tree" | `sub:535-545`, `pt:533` | `sub:539` "its cost includes the costs of its descendants"; `sub:542` "Clients **MUST NOT** add a child's cost to an ancestor's"; `sub:543` context usage not summed; `sub:541` missing cost is unknown, not zero | **MATERIAL**: the RFD at `13b76582` said costs "may overlap" and gave no rule on inclusion. The page says they are inclusive. The Client rule (do not add) is unchanged. See "Discrepancies" |
| A56 | 2255 | `:1106-1107`, `:1070-1073` | "defines no error or idempotency rule for an ungranted cancel": cancel only while granted; allowlist | `sub:428`, `sub:433`, `sub:440`; allowlist `sub:424` | `sub:433` "The Agent ignores a `session/cancel` for a child that does not advertise `cancel`. It **SHOULD** reject any other request that is not permitted for a child with `-32602`. This includes `session/close` and `session/delete`" | **MATERIAL**: the claim in the spec (462, 2253-2255) that the spec has no rule is stale. Rule added upstream 2026-11-17 |

Notes on table A:
- Rows that cite the same range from several lines are merged. Apply the "Use" cell to every listed line.
- A16 also covers the "Each view goes on the stream of the session whose transcript it belongs to" sentence (spec line 85): `pt:40-46` ("The outer `params.sessionId` identifies the transcript being updated") and `sub:324`.
- Not an RFD citation but touched by the above: spec lines 16-22 cite `client.rs:NNN` / `tool_call.rs:NNN` at the Rust schema revision. That is the schema researcher's area. I did not check them.

## Table B: cited ranges the stable page does not carry ("only in RFD")

Give the latest-RFD range. Line numbers are in `docs/rfds/subagents.mdx` at `01a54c84`. The RFD is Completed, so it defers to the pages (stage group "Completed", `docs/docs.json:248`, `:274`; changelog `rfd:1720`).

| # | Spec line(s) | RFD (`13b76582`) | Short quote | Latest RFD | Where the normative text is instead | Changed |
|---|---|---|---|---|---|---|
| B1 | 75 | `:1239-1241` | "V1 uses only the parent-carried snapshot" (v2 contrast) | `rfd:1246-1249` | v1 half: `sub:332`. v2: `v2sub:331`, `v2sub:352` | same |
| B2 | 201, 2180 (from A21) | `:841` | "The v1 payload mirrors the v2 state object" | `rfd:838` | not on the page (schema describes it) | same |
| B3 | 615, 2302 | `:1095-1102` | forking: "MUST consistently remap the copied session IDs ... Copying history does not itself create live child runtimes" | `rfd:1099-1106` | none. `session/fork` is not stable ("The `session/fork` rules ... stay unstable with their own RFDs", `rfd:1307-1312`). The fork RFD is the source | **reworded**: the tail now says "Deleting an active session closes it first" (was "is not a substitute for closing active sessions"). See "Discrepancies" |
| B4 | 474, 489 | `:1416-1417` | test plan: "Lose a child's activity feed while ACP stays connected: report `unknown` without inventing an outcome" | `rfd:1466-1467` | the rule itself: `sub:415`, `sub:420`, `sub:455` | same |
| B5 | 2042, 2190 | `:791-800` | v2: Agents SHOULD mirror child state onto the parent's `subagent_update.state`; idempotent | `rfd:788-797` | `v2sub:331`, `v2sub:352` (v2 page, unstable) | same |
| B6 | 2061 (labelled `7ec5185c`) | `:1079-1082` | "A child's tool call or terminal that advertises its own `capabilities.cancel` enables `tool_call/cancel` or `terminal/cancel`" | `rfd:1078-1081` | `v2sub:375` (v2 only). No v1 page has `tool_call/cancel` or `terminal/cancel` | same |
| B7 | 2070 | `:1590-1594` | FAQ: "The expected follow-up is a per-child `prompt` capability in `subagent_update.capabilities`" | `rfd:1641-1645` (heading "Why not allow prompting or steering a subagent?" at `rfd:1633`) | none | same |
| B8 | 2186-2187 (only the `:1131-1137` part is in A19; the "SDK may tolerate" idea is not cited) | n/a | see "Discrepancies" item 6 | n/a | n/a | n/a |

## Table C: citations outside the spec file

| File:line | Text now | Replace with |
|---|---|---|
| `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/RemoteClientSessionOperations.kt:42` | "The other updates the subagents RFD defines need the `subagents` client" | "The other updates the subagents page defines need the `subagents` client" |
| `.../RemoteClientSessionOperations.kt:43` | (`docs/rfds/subagents.mdx`, "Capability negotiation") | (`docs/protocol/v1/subagents.mdx`, "Checking support", and `docs/protocol/v1/initialization.mdx`, "Subagents"). The MUST NOT is for `subagent_update`, `session_message`, `session_message_chunk`; for `session` content the page says "only" (A24) |
| `.../RemoteClientSessionOperations.kt:200` | "Whether the subagents RFD defines this update, so a v1 agent may send it only with the `subagents` client capability. That includes tool call content that references a session." | "Whether the v1 subagents pages define this update, ..." (`docs/protocol/v1/subagents.mdx`, `docs/protocol/v1/tool-calls.mdx`, "Sessions") |

Searched and found nothing about the subagents RFD in: `AGENTS.md` (the checked-in repo guide is `CLAUDE.md` here, also nothing), `README.md` (`:344` is about authentication), test sources, `samples/`, `e2e/`. The other "RFD" hits (elicitation, session config options, session notices, compaction, v2 enum variants) are different RFDs. Nothing to change for this question.

## Table D: spec lines that say "the RFD" with no line number

All are in `docs/superpowers/specs/2026-11-07-v1-subagents-architecture.md`. Replace the source with the page and cite it. The rule behind each is on the page unless noted.

| Spec line | Claim | Use | Note |
|---|---|---|---|
| 9-13 | Sources: pinned revision, "RFD is in the Preview stage ... v1 still has it only behind `unstable_subagents`" | rewrite with the "Citation format" text above | stale: stage is Completed (2026-11-20) and v1 subagents are in the default schema, per `rfd:1307-1312`, `rfd:1720` |
| 23, 42 | "re-read the RFD and the Rust models" | "re-read the subagents pages and the Rust models" | |
| 135, 2106 | "the RFD's real `unknown` state" | `sub:367-370`, `sub:413-420` | |
| 205 | "the RFD models v1 and v2 subagents on v2" | `rfd:838`, `rfd:1236` ("The two versions use the same ownership and directed-message model") | only in RFD |
| 392 | "The RFD says a v1 agent MUST NOT send the updates it defines" | `sub:59`, `init:241-242` | tier note A1 |
| 472, 487, 489 | idle(error) "forbidden", terminal notification, `unknown` | A27-A29 rows | phrases: table E |
| 792 | state `null` "unconfirmed, not idle" | `sub:135` | table E |
| 814 | "copy the RFD's grant rules" | `sub:424-433`, `sub:134` | |
| 821 | "The RFD makes the successful response the freshness boundary" | `sub:508` | |
| 969 | "the RFD lets historical data stay visible" | `sub:501` ("History the Client already shows can stay visible") | |
| 1026 | quote "The first `subagent_update` for an unknown id announces" (RFD) | `sub:86` | quote reworded, table E |
| 1305 | "The RFD: closing a parent ends the work of all its descendants and needs no terminal notification" | `sub:480`, `sub:483` | |
| 1589 | "The RFD: when the client cancels a session's work, it MUST answer ..." | `sub:471` | |
| 2069 | "per-child `prompt` capability is the RFD's expected follow-up" | `rfd:1641-1645` | only in RFD (B7) |
| 2082 | "The subagents RFD says the client SHOULD dismiss ..." | `sub:471`, `sub:473` | |
| 2093 | "the RFD's term is "interactive request"" | the page says "request" (`sub:473`); the RFD still says "interactive request" at `rfd:1193` | choose: cite `rfd:1193` or drop "the RFD's term" |
| 2104 | "added on 2026-10-01 according to the RFD changelog" | unrelated to this mapping (v2 `ErrorStopReason`); the changelog is in the RFD | leave |
| 2186 | "The RFD says a cancellation racing with already-ended work doesn't rewrite its outcome" | `sub:456` | |
| 2254 | "The RFD defines no error or idempotency rule for an ungranted cancel" | **stale**: `sub:433` | A56 |
| 2329 | "the RFD distinguishes only replay versus no replay" | `sub:505-508` | |
| 2345 | "the RFD requires invalidating child state and grants on every load or resume" | `sub:499-503` | |
| 2470-2472 | decision 26 history of RFD revisions | keep as history. Add "and the RFD became Completed on 2026-11-20 (`9d44c77a`), with the rules on the stable page" | history, no replacement |
| 2482 | "the RFD puts it under the same capability as `session_message`" | `tc:357`, `sub:59` | weaker on the page (A24) |

## Table E: quoted phrases the design spec attributes to the RFD that the stable page no longer has

Where the spec puts words in double quotes, the quote must change to the page's wording, or the quote must be dropped.

| Spec line | Quoted now (RFD) | Stable wording | Cite |
|---|---|---|---|
| 473 | `unknown` is "not a work outcome" | none. Page: the `unknown` state "cannot currently determine foreground activity" | `sub:367-370` |
| 474, 489 | "without inventing an outcome" | none. Page: "MUST NOT invent a final status for them" (`sub:420`) | `sub:420` |
| 493, 2284 | "rather than inventing a cancelled outcome" | none. Page: "If it can no longer observe the child, it reports `unknown`" | `sub:455` |
| 793 | "unconfirmed, not idle" | "leaves the child's activity unconfirmed. It does not report `idle`" | `sub:135` |
| 1026 | "The first `subagent_update` for an unknown id announces" | "The first `subagent_update` for a child `sessionId` announces that the parent created and owns the child" | `sub:86` |
| 1031-1033 | "unique within the ACP connection", "MUST NOT be reused for an unrelated session", "cannot reparent an existing session, refer to itself, or create a cycle" | "unique on the connection", "**MUST NOT** reuse it for an unrelated session", "cannot move an existing session to another parent, name its own outer session as the child, or create a cycle" | `sub:98-100`, `sub:142` |
| 1306 | "cleans up their requests together with the parent's" | "handles the pending requests of the closed session's descendants together with the parent's own" | `sub:482` |
| 1590-1593 | "as if it had cancelled the descendant directly" | same words | `sub:472` (still matches) |
| 1592 | "the Client never denies a tool use of work that continues" | none. Page: "A descendant that keeps running keeps its requests." | `sub:472` |
| 1593 | "Idle alone is not a cancellation of unrelated background requests" | "`idle` alone does not cancel the requests of unrelated work that continues" | `sub:473` |
| 1595 | "does not rewrite that work's outcome" | "does not change that work's outcome" | `sub:456` |
| 2452 | "the RFD says the client cleans up the requests together with the parent's" | see 1306 | `sub:482` |
| 615 | "does not itself create live child runtimes" | only in RFD | `rfd:1103-1104` |
| 473 | "a failure that ends the child's foreground work" | same words | `sub:380` (still matches) |

## Details

Sources of the numbers:
- Stable lines: `docs/protocol/v1/subagents.mdx` (545 lines), `prompt-turn.mdx:40-116`, `tool-calls.mdx:336-360`, `initialization.mdx:233-246`, all at `01a54c84`.
- RFD `13b76582` lines were read with `git show 13b76582:docs/rfds/subagents.mdx`. Latest-RFD numbers were mapped with a line diff of the two versions and spot-checked by reading (`rfd:1099-1106`, `:1206-1228`, `:1246-1249`, `:1466-1467`, `:1641-1645`, `:788-797`, `:838`).
- RFD changes after `13b76582` (from `git diff`, and the RFD's own changelog `rfd:1719-1730`): cost inclusive (2026-11-17); `-32602` rule (2026-11-17); late response SHOULD NOT (2026-11-20); delete wording (2026-11-19); SDK-buffering sentence removed (2026-11-19); Completed (2026-11-20).
- The stable pages carry no RFD-stage language. The RFD is in the "Completed" group, so the docs are authoritative (`docs/docs.json:248`, `:274`).
- Stable or unstable: all A-table rules are stable v1 (`schema/v1/schema.json`, per the 2026-11-20 stabilization, `rfd:1720`). Exceptions: `session/fork` rules and `usage` on the idle state stay unstable (`rfd:1307-1312`). B5 and B6 are v2 (unstable).

## SDK implications

- The replacement is textual. No product code depends on the line numbers.
- Behaviour-level consequences of the material changes. They need decisions elsewhere:
  - **A56 (`-32602`):** the stable page says an Agent ignores `session/cancel` for a child without `cancel` and SHOULD reject every other not-permitted request with `-32602`. The design spec (about 462) says the agent fails such requests with an explicit "session X is a subagent" error through `acpFail`. Whether `acpFail`'s code is `-32602` is not checked here. A test of a non-conforming client would observe whichever code the agent returns.
  - **A53:** the SDK needs no change. The SDK does not restart cancelled work, so "SHOULD NOT" imposes nothing more.
  - **A55 (cost):** the SDK decision "no tracking, never add up the tree" (spec 2232-2234) still conforms. The Agent app should report a parent's cost as inclusive of its descendants. If an `AgentSubagents` KDoc mentions cost, it should say so (`sub:539`).
  - **A24:** the SDK's capability check for `session` content is stricter than the stated keyword. That is fine (the RFD's reading is the MUST NOT of `:76-78`), but the spec's justification at 2482 should cite `tc:357` and say the page uses no MUST for it.

## Discrepancies

1. **Premise:** the brief says the pinned revision is `c392d7e9`. The citations match `13b76582` (verified above), as the design spec itself says.
2. **Cost (A55):** RFD `13b76582` (`:1195-1225`) said cost "may overlap" and Clients must not infer a tree total. RFD latest and the page say a session's cost includes its descendants. Client rule unchanged.
3. **Late response (A53):** MUST NOT became SHOULD NOT for restarting cancelled work.
4. **Ungranted requests (A56):** the RFD at `13b76582` was silent. The page defines it.
5. **Delete vs close:**
   - `docs/protocol/v1/subagents.mdx:485`: "`session/delete` ... is not a substitute for closing an active session."
   - latest `rfd:1104-1106`: "Deleting an active session closes it first" (changelog `rfd:1721`: "as the session delete page says").
   - `docs/protocol/v1/session-delete.mdx:90`: "Behavior for deleting an active session is implementation-defined." Only the v2 page (`docs/protocol/v2/session-delete.mdx:95`) says it closes first.
   The SDK does not cite this. Reported only so nobody cites the RFD for it.
6. **Buffering tolerance:** the 13b RFD said "SDKs may tolerate non-conforming Agents by buffering early traffic" (`:192-193`). It was removed (`rfd` changelog 2026-11-19) and is not on the page. The design spec does not cite it by line, but its buffering design (the speculative path) now rests on SDK choice, not on spec text.
7. **Tier of `session` content (A1, A23, A24):** RFD implies MUST NOT, pages use "only".

## Open questions

- Is `StateUpdate.Idle.usage` in the stable schema? The RFD says "the `usage` field on the idle state stay unstable" (`rfd:1307-1312`), and `docs/protocol/v1/subagents.mdx` never mentions it. The design spec models it (spec 2232-2233). Route to the schema-vs-models researcher.
- Does `acpFail` produce `-32602` for not-permitted child methods (A56)? Route to a Kotlin-side check.
- The `client.rs:NNN` / `tool_call.rs:NNN` line refs in spec lines 16-22 are at the old Rust revision. Route to the schema researcher.
