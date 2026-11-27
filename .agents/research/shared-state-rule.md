# Shared-state rule (settled with the user, 2026-11-27)

Goes into `AGENTS.md`, "Runtime and Lifecycle Rules", as a new subsection right before "Read-loop ordering and
hooks". Use the text verbatim; it lands with the first code slice of the concurrency milestone.

```markdown
### Shared state

- Keep state that several coroutines or threads share in immutable values behind atomics, with one atomic for all the state that one rule connects, as `SessionTable` does. Don't split a rule's state over several atomics and keep the rule true by the order of writes and reads. A plain `var` holds only state that one coroutine owns at a time.
- Decide from one `value` load. If a decision needs two atomics, a rule connects them: fold them. The value may be stale, so a decision that changes state is made in the write itself.
- Write with a pure CAS (`update`, `getAndUpdate`, `compareAndSet`). The lambda can run more than once, so it only computes the new value from the old one: it assigns nothing outside, reads no other atomic, and calls nothing with an effect. It may throw to reject the update. Read results from the snapshot that `getAndUpdate` returns.
- Take a lock (`SynchronizedObject`) only when no one may see the update without an effect that can't lag it, such as a send whose place on the wire matters. Every writer of that atomic then takes the lock and publishes with `value =`, and readers still don't lock. If a failed effect makes the section restore the old value, say so at the site: a reader may have seen the new one.
- Under a lock, call only code that doesn't suspend, block, take another lock, or call back into code that takes this lock. App code, and completing or cancelling a deferred or a job, can do any of that, so do them after the lock.
- Use a `Mutex` only for a section that must suspend.
- An operation may change several components in steps. State that can't be a value, such as a job, a deferred or a channel, follows one deciding CAS: its winner runs the other steps once, after it. Each step must be harmless to observe before the others, and removals compare identity, so a late step changes nothing.
```

## Edits that land with it

- `AGENTS.md`, v1 agent, the child's-lock bullet ("A child's lock orders its sends against dropping or removing
  it. Hold it only around …"): shrink to "A child's lock orders its sends against dropping or removing it."
- `Transport.send` KDoc (`acp/.../transport/Transport.kt`): add "It must not suspend, block, or call listeners."
  Documentation only, no API change.
- With registry B (its own slice): the v1 client bullets "A holder's children and its `subagents` list share one
  atomic state …" and "Every removal of a root's tree takes the root's entry out no later than its descendants' …".

## Sites the rule requires changing (audit by reading, 2026-11-27; Ktor modules have no atomics)

1. Agent `SubagentSessionWrapper`: `_phase`, `_cancelGranted`, `_session` + `factoryRunning`, `factoryState`,
   `isCancelLatched` → one `State` value, still written under `lock` (sends). Keep the grant's publish-before-send
   with restore on failure (`sendOnParentStreamLocked`), with a note at the site.
2. Client registry: `_sessions` + holders' `children` + root `CLOSING` → design B
   (`client-registry-sync-design.md`), decisions pending.
3. Agent `PromptSession`: `runningCancels` + `_isCancelRequested` → #13, `Mutex` over plain fields.
4. `ClientSubagentSession`: 7 atomics → one value (patch applied in one write); `heldCapabilities`/`heldState`
   stay plain (consumer-owned).
5. `SubagentPendingPermissions`: `signal` + `workState` ordering pair → gone with #19, else fold.
6. `SubagentSessionHolder`: `opening` + `_subagent` → session moves into `OpeningState`.
7. v2 agent `SessionWrapper`: `_activePrompt` + `_turn` → one `atomic<Job?>` with a lazy job (in or out of PR: ask).
8. `ElicitationSessionStore`: `counter` + `entries` → #17.
Plus #7 purification at the agent (`createSession`, `removeRoot`) and `Protocol` sites (`:295` cancels inside the
lambda; `:393` captured var).
