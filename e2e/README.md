# End-to-End Suites

Suites that check the ACP Kotlin SDK against external counterparts. They need tools outside the Gradle build,
pin the upstream revision they run against, and run in their own CI workflows, so `./gradlew check` does not run them.
Integration tests that need only the Gradle build live in `:acp-integration-test`.

| Suite          | Checks                                                                                            | Requires  |
|----------------|---------------------------------------------------------------------------------------------------|-----------|
| [`tck`](tck/)  | v1 and v2 SDK agents against the [ACP Test Compatibility Kit](https://github.com/agentclientprotocol/acp-tck) | JDK 21, uv |
| [`rust-agent`](rust-agent/) | v1 and v2 SDK clients against the [Rust SDK](https://github.com/agentclientprotocol/rust-sdk)'s `testy` agent | JDK 21, git, Rust toolchain |

Each suite has a documented entry point (a `run.sh` or a Gradle task) and a README with its requirements and options.
