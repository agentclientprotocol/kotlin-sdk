# gemini

The terminal client of the [simple](../simple/README.md) sample, run against an external agent process.

- Protocol: ACP v1.
- Needs: the Gemini CLI, with the `gemini` command on `PATH`.
- Package: `com.agentclientprotocol.samples.gemini`.

## What it shows

- Starting an external agent as a child process and connecting to its STDIO.
- Running the same interactive terminal client against an agent that is not part of the SDK.

## How to run

From the repository root:

```bash
./gradlew :samples:kotlin-acp-client-sample:runGeminiClient
```

The sample starts `gemini --experimental-acp`. It fails to start if the `gemini` command is not on `PATH`.

The sample reads your messages from the console. Type `exit`, `quit` or `bye` to leave the chat.

## Files

- [`GeminiClientApp.kt`](GeminiClientApp.kt): starts the agent process and runs the terminal client against its STDIO.

The helpers are in the shared `support` package: [`ProcessTransportUtils.kt`](../support/ProcessTransportUtils.kt) for the
process transport and [`TerminalClientSupport.kt`](../support/TerminalClientSupport.kt) for the client.
