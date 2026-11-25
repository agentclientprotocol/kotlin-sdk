# auth

The unstable `auth/status` query, asked without a session.

- Protocol: v1 and v2.
- Needs: nothing.
- Package: `com.agentclientprotocol.samples.auth`.

## What it shows

`AuthStatusApp.kt` extends the v1 and the v2 echo agents to advertise and answer `auth/status`, then queries both from
a client. Each flow initializes the connection and asks for the status without creating a session or sending a prompt.

The `auth/status` API is unstable and needs `@OptIn(UnstableApi::class)`. The SDK side is described in the
[Authentication status](../../../../../../../../../README.md#authentication-status-unstable) section of the
repository README.

## How to run

From the repository root:

```bash
./gradlew :samples:kotlin-acp-client-sample:runAuthStatus
```

Set `SAMPLE_AGENT_API_KEY` before the run to see the agents report configured credentials. Without it, they report `authenticated=false` and a
message that asks you to set it.

## Files

- [`AuthStatusApp.kt`](AuthStatusApp.kt): the agents with their status handlers, and the v1 and v2 client queries.

It builds on the agents of the [simple](../simple/README.md) and [v2](../v2/README.md) packages.
