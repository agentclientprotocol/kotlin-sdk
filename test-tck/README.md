# ACP Test Compatibility Kit Integration

This directory contains tooling to validate the ACP Kotlin SDK against the
[Agent Client Protocol](https://agentclientprotocol.com) using the
[ACP Test Compatibility Kit (acp-tck)](https://github.com/agentclientprotocol/acp-tck).

acp-tck launches an agent as a stdio subprocess (a fresh process per test), drives it through the protocol, and
reports which requirements pass, fail, don't apply, or were never exercised.

## Contents

- **`acp-tck-agent-v1/`**: Stable (protocol v1) agent built with `com.agentclientprotocol.agent.Agent`
- **`acp-tck-agent-v2/`**: Protocol v2 agent built with `com.agentclientprotocol.agent.v2.Agent`
- **`acp-tck/`**: The acp-tck repository (gitignored, cloned at a pinned commit by `setup_tck.sh`)
- **`setup_tck.sh`**: Clone acp-tck and sync its Python environment
- **`run_tck.sh`**: Build one of the agents and run the matching acp-tck suite against it

Both agents keep sessions in memory and advertise every capability the suites check, so that capability-gated
requirements are exercised instead of skipped. Every prompt is answered with a word-by-word echo, slow enough for the
cancellation tests to land while the turn is still running.

## Requirements

- JDK 21, used by the rest of the build as well (`JAVA_HOME` must point to it, since the agent launcher uses it)
- [uv](https://docs.astral.sh/uv/); acp-tck needs Python 3.14, which uv downloads if it is missing

## Quick start

1. **Set up the Test Compatibility Kit:**
   ```bash
   ./setup_tck.sh
   ```

2. **Run the suite for a protocol version:**
   ```bash
   ./run_tck.sh v1 --report-json reports/v1.json
   ./run_tck.sh v2 --report-json reports/v2.json
   ```

`run_tck.sh` always passes `--protocol-version`, `--auth-method tck`, and `--allow-logout`; any other
[acp-tck option](https://github.com/agentclientprotocol/acp-tck#options), such as `-k EXPR` or `-v`, is passed through.
The exit code is acp-tck's verdict: `0` only when the agent is conformant.

To move to a newer acp-tck revision, update `COMMIT_HASH` in `setup_tck.sh` and delete the local `acp-tck` directory.
