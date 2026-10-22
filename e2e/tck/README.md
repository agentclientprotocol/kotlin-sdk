# ACP Test Compatibility Kit Integration

This directory contains tooling to validate the ACP Kotlin SDK against the
[Agent Client Protocol](https://agentclientprotocol.com) using the
[ACP Test Compatibility Kit (acp-tck)](https://github.com/agentclientprotocol/acp-tck).

acp-tck launches an agent as a stdio subprocess (a fresh process per test), drives it through the protocol, and
reports which requirements pass, fail, don't apply, or were never exercised.

## Contents

- **`agent-v1/`**: Stable (protocol v1) agent built with `com.agentclientprotocol.agent.Agent`
- **`agent-v2/`**: Protocol v2 agent built with `com.agentclientprotocol.agent.v2.Agent`
- **`runner/`**: A uv project with no code of its own; its `pyproject.toml` and `uv.lock` pin acp-tck to a Git commit
- **`run.sh`**: Build one of the agents and run the matching acp-tck suite against it

Both agents keep sessions in memory and advertise every capability the suites check, so that capability-gated
requirements are exercised instead of skipped. Every prompt is answered with a word-by-word echo, slow enough for the
cancellation tests to land while the turn is still running.

## Requirements

- JDK 21, used by the rest of the build as well (`JAVA_HOME` must point to it, since the agent launcher uses it)
- [uv](https://docs.astral.sh/uv/); acp-tck needs Python 3.14, which uv downloads if it is missing

## Quick start

Run the suite for a protocol version:

```bash
./run.sh 1 --report-json reports/v1.json
./run.sh 2 --report-json reports/v2.json
```

Run the script from `e2e/tck/`. uv fetches and builds acp-tck at the pinned commit on first use, so no separate setup step is needed.

`run.sh` always passes `--protocol-version`, `--auth-method tck`, and `--allow-logout`; any other
[acp-tck option](https://github.com/agentclientprotocol/acp-tck#options), such as `-k EXPR` or `-v`, is passed through.
The exit code is acp-tck's verdict: `0` only when the agent is conformant.

To move to a newer acp-tck revision, change `rev` in `runner/pyproject.toml`, run `uv lock` in `runner/`, and commit both files.
