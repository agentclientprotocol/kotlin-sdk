<task>
## Problem

`Agent` registers `session/list` via `setPaginatedRequestHandler`:
https://github.com/agentclientprotocol/kotlin-sdk/blob/22a619ee12468c654b42725eaa640ede2ccff479/acp/src/commonMain/kotlin/com/agentclientprotocol/agent/Agent.kt#L179

That handler requires the agent to return `Sequence<SessionInfo>` — a non-suspend iterator. An agent whose data source is a database cannot do lazy per-page fetches and must materialize the entire list on the first request.

## Proposal

Add a library hook with a suspend per-page fetch, e.g. `suspend (cursor: String?) -> Page<SessionInfo>`, so that a DB-backed agent can lazily fetch one page at a time instead of materializing the whole session list up front.
</task>

Let's implement this in both v1 and v2.