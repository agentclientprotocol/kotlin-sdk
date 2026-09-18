---
name: check-rust-sdk
description: Check the current official Agent Client Protocol Rust SDK reference implementation, including runtime behavior, lifecycle semantics, error handling, tests, and API patterns. Use when this Kotlin SDK must be compared with the latest Rust reference behavior. Do not use it as the source of truth for protocol schemas or specification text.
---

# Check the ACP Rust SDK

Use the official [Agent Client Protocol Rust SDK](https://github.com/agentclientprotocol/rust-sdk) as the reference implementation for ACP runtime behavior.

The Rust SDK is implementation evidence, not the normative protocol specification. Use the separate specification skill for current documentation, RFDs, and protocol models or schemas. If the implementation and specification disagree, report the discrepancy instead of silently treating the Rust behavior as the protocol contract.

## Locate the repository

The `.repo` file next to this `SKILL.md` is gitignored and contains one absolute path to a local clone of the Rust SDK repository. Read and trim that path before doing any reference-implementation research.

If `.repo` does not exist, explicitly ask the user for permission to clone the repository and for their preferred clone location. Do not clone it until permission is granted. If the user grants permission without choosing a location, clone the repository as `acp-rust-sdk` beside the current ACP Kotlin SDK checkout. After cloning, write the clone's absolute path to `.repo`.

If `.repo` exists but its value does not identify a usable Git checkout, report the problem and ask the user whether to correct the path or create a clone. Do not silently replace an existing checkout.

## Refresh before research

Before exploring or searching the local Rust SDK checkout, update it:

```bash
git -C "<absolute path read from .repo>" pull --ff-only
```

Run this on every use of the skill, even if the checkout was used recently. If the pull fails, report the failure and do not describe the checkout as current. Do not discard local changes, reset branches, or otherwise repair the checkout without the user's authorization.

After the pull succeeds, read the checkout's applicable `AGENTS.md` or other agent-guidance files before researching it, including more specific guidance in subdirectories you inspect.

## Search the checkout

If the `context-search` skill is available, combine it with this skill when the relevant file, behavior, or subsystem is not already known. Run its semantic search from the Rust SDK checkout and follow its search-and-inspect workflow. Prefer this semantic bootstrap to broad keyword grepping.

If `context-search` is unavailable, fails, or returns no useful result, continue with other exploration approaches such as `rg --files`, focused `rg` queries, directory inspection, and direct file reads. A semantic-search miss must not block the research. When the relevant file or symbol is already known, navigate to it directly instead of invoking semantic search.

## Check the reference implementation

Trace the complete Rust behavior relevant to the question rather than relying on similarly named types or isolated functions:

- Start at the public API or protocol entry point and follow control flow through the owning runtime and transport layers.
- Read focused tests alongside the implementation to confirm observable behavior, failure handling, cancellation, cleanup, and ordering.
- Check examples when the question concerns supported API usage or lifecycle wiring.
- Check model and serialization code when it directly affects how the reference implementation consumes or produces protocol data.

Compare externally observable semantics rather than translating Rust structure mechanically into Kotlin. Account for ownership, task and channel behavior, error propagation, and shutdown rules explicitly when they affect parity.

Report the upstream revision checked and cite concrete repository-relative files and lines. Distinguish behavior demonstrated by code or tests from interpretation, and note any relevant feature flags, platform constraints, or untested paths.
