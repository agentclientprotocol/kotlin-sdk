# simple

The terminal client and a simple agent, joined in one process over STDIO pipes. This is the best sample to read first.

- Protocol: ACP v1.
- Needs: nothing.
- Package: `com.agentclientprotocol.samples.simple`.

## What it shows

- Connecting an `Agent` and a `Client` with two in-process pipes.
- An agent that implements `AgentSupport` and `AgentSession`: it echoes the prompt, sends a plan and a tool call, and
  uses the client's file system and terminal when the client offers them.
- Session config options exposed by the agent.
- An interactive terminal client that prints session updates and answers the agent's requests.

## How to run

From the repository root:

```bash
./gradlew :samples:kotlin-acp-client-sample:runSimpleAgent
```

The plain `run` task starts this sample too. See [How to run](../../../../../../../../README.md#how-to-run) for the
shared details.

The sample reads your messages from the console. Type `exit`, `quit` or `bye` to leave the chat. The task does not
return to the shell after that, so stop it with Ctrl+C.

## Files

- [`SimpleAgentApp.kt`](SimpleAgentApp.kt): the entry point. It connects an agent and the terminal client with two
  in-process pipes. Read this first.
- [`SimpleAgentSupport.kt`](SimpleAgentSupport.kt): the agent. It implements `AgentSupport` and `AgentSession`, and
  exposes session config options.

The client side is [`TerminalClientSupport.kt`](../support/TerminalClientSupport.kt) in the shared `support` package. The
[gemini](../gemini/README.md) sample uses it too.
