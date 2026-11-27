# v2

A v2 client and a v2 agent, first connected directly and then through version negotiation.

- Protocol: v2 (unstable), and negotiated for `runV2Negotiation`.
- Needs: nothing.
- Package: `com.agentclientprotocol.samples.v2`.

## What it shows

- `runV2SimpleAgent`: a v2 client and a v2 agent talking directly, with no negotiation. Read this first.
- `runV2Negotiation`: `ClientNegotiator` chooses between v1 and v2, then the same v2 conversation runs with the selected
  client.
- In both: a session, a config option change, a prompt and the update stream.

The v2 APIs are unstable (`@UnstableApi`) and need an explicit opt-in.

## How to run

From the repository root:

```bash
./gradlew :samples:kotlin-acp-client-sample:runV2SimpleAgent
./gradlew :samples:kotlin-acp-client-sample:runV2Negotiation
```

Both run without input and exit when the conversation ends.

## The response mode

The v2 echo agent exposes a response mode through session config options. The client selects "Uppercase once" with
`session/set_config_option`. After replying, the agent resets to Echo and sends a `config_option_update`.

- `ClientSession.setConfigOption` returns the complete option list rather than the one option that changed, because
  one choice can affect the others.
- Later changes arrive on `session.updates` as `SessionUpdate.ConfigOptionUpdate`, which also carries the full list.
- Agents report those changes with `agent.v2.ClientOperations.notify(SessionUpdate.ConfigOptionUpdate(...))`. This works
  while the session is idle as well as mid-turn.
- ACP v2 uses config options for mode selection. Dedicated modes remain available in the SDK's v1 API.

## Files

- [`V2SimpleAgentApp.kt`](V2SimpleAgentApp.kt): a v2 client and a v2 agent connected directly, with no negotiation.
- [`V2NegotiationApp.kt`](V2NegotiationApp.kt): the same conversation, but the client first uses `ClientNegotiator` to
  pick the version.
- [`V2ClientExamples.kt`](V2ClientExamples.kt): the two client flows and the conversation they share.
- [`V2SampleSupport.kt`](V2SampleSupport.kt): the v2 echo agent and its response mode config option.

The [auth](../auth/README.md) sample builds on the agent of this package.
