# End-to-End Suites

Suites that check the ACP Kotlin SDK against external counterparts. They need tools outside the Gradle build,
pin the upstream revision they run against, and run in their own CI workflows, so `./gradlew check` does not run them.
Integration tests that need only the Gradle build live in `:acp-integration-test`.

| Suite          | Checks                                                                                            | Requires  |
|----------------|---------------------------------------------------------------------------------------------------|-----------|
| [`tck`](tck/)  | v1 and v2 SDK agents against the [ACP Test Compatibility Kit](https://github.com/agentclientprotocol/acp-tck) | JDK 21, uv |

Each suite has a `run.sh` entry point and a README with its requirements and options.
