# Publishing Guide

This document describes how ACP Kotlin SDK artifacts are versioned, validated, and published. Publishing is an external, potentially irreversible action: do not publish, tag, push, or create a GitHub release without explicit authorization.

## Published Artifacts

Published coordinates use the group `com.agentclientprotocol`. The published Gradle modules are:

- `acp-model`;
- `acp`;
- `acp-ktor`;
- `acp-ktor-client`;
- `acp-ktor-server`.

`:acp-ktor-test` and `:samples:kotlin-acp-client-sample` do not apply the publishing convention and are not release artifacts.

## Versioning

`build.gradle.kts` is the authoritative version source. It defines `baseVersion` and derives the effective version as follows:

| Environment                    | Effective version                   |
|--------------------------------|-------------------------------------|
| `RELEASE_PUBLICATION=true`     | `baseVersion`                       |
| `GITHUB_RUN_NUMBER` is absent  | `baseVersion-SNAPSHOT`              |
| `GITHUB_RUN_NUMBER` is present | `baseVersion-dev-GITHUB_RUN_NUMBER` |

When changing the release version:

1. Update `baseVersion` in the root `build.gradle.kts`.
2. Search documentation and samples for copied artifact versions.
3. Update installation examples in `README.md` where necessary.
4. Confirm the expected effective version before publishing.

Do not manually edit generated `LIB_VERSION` source files. The multiplatform convention generates them from `project.version` under each module's build directory.

## Publishing Configuration

`buildSrc/src/main/kotlin/acp.publishing.gradle.kts` configures:

- Maven publications;
- Maven Central publication with automatic release;
- signing when a GPG key is available;
- the JetBrains Space Maven repository;
- shared POM metadata.

Credential lookup checks, in order where applicable:

1. the root `.env` file;
2. a Gradle project property;
3. an environment variable;
4. the configured fallback.

The relevant values are:

- `SPACE_USERNAME` and `SPACE_PASSWORD` for Space;
- `ORG_GRADLE_PROJECT_mavenCentralUsername` and `ORG_GRADLE_PROJECT_mavenCentralPassword` for Maven Central;
- `GPG_SECRET_KEY` and `SIGNING_PASSPHRASE` for signing.

The signing configuration may read a root `.asc` file as a local fallback when `GPG_SECRET_KEY` is absent. Never commit `.env`, `.asc`, credentials, tokens, or decrypted key material. Do not print secret values in logs or diagnostic output.

## Pre-Publication Validation

Before any publication:

1. Confirm the requested target, version, and source revision.
2. Confirm the working tree contains only intended changes.
3. Use JDK 21 and the Gradle wrapper.
4. Run focused tests for the changed behavior.
5. Update and review affected public API dumps if the public API changed.
6. Run API generation separately from verification.
7. Run the full check or CI-equivalent build.
8. Inspect the publication plan and credentials without exposing secrets.

Typical validation:

```bash
./gradlew check
./gradlew clean build
```

Use `clean build` to reproduce CI or rule out stale outputs. It is not required for every local iteration.

For an intentional public API change, run the affected module's dump first and verification separately:

```bash
./gradlew :acp:apiDump
./gradlew :acp:apiCheck
```

Review every `.api` diff before publication.

## Development Publication to Space

`.github/workflows/publish.yml` publishes development artifacts to the configured JetBrains Space repository.

Trigger:

- a push to `master`.

Workflow behavior:

1. Check out the pushed revision.
2. Set up Temurin JDK 21.
3. Run `./gradlew clean build`.
4. Run `./gradlew publishAllPublicationsToSpaceRepository`.
5. Derive the development version from `GITHUB_RUN_NUMBER`.

The workflow requires the `SPACE_USERNAME` and `SPACE_PASSWORD` GitHub secrets. A successful local build does not prove that CI credentials or the remote repository are available.

For an explicitly authorized local Space publication, the corresponding Gradle task is:

```bash
./gradlew publishAllPublicationsToSpaceRepository
```

Before running it, verify that the effective version is the intended one and that credentials resolve from an approved source.

## Release Publication to Maven Central

`.github/workflows/publishMavenCentral.yml` publishes release artifacts to Maven Central.

Trigger:

- a GitHub release with the `published` event.

Workflow behavior:

1. Check out the release revision.
2. Set up Temurin JDK 21.
3. Run `./gradlew clean build`.
4. Set `RELEASE_PUBLICATION=true`, selecting the unqualified `baseVersion`.
5. Run `./gradlew publishToMavenCentral --no-configuration-cache`.
6. Sign and publish using the configured GitHub secrets.

Required GitHub secrets:

- `OSSRH_USERNAME`;
- `OSSRH_TOKEN`;
- `GPG_SECRET_KEY`;
- `SIGNING_PASSPHRASE`.

For an explicitly authorized local Maven Central publication, use the same release-version environment and Gradle task as CI. Confirm the source revision and effective version immediately before executing the publication.

## Workflow Changes

When changing publication behavior:

- keep workflow triggers consistent with the intended release policy;
- preserve JDK 21 and Gradle wrapper usage;
- keep GitHub Actions pinned to reviewed commit revisions;
- update this file and any affected README instructions in the same change;
- validate YAML and Gradle configuration without triggering publication;
- preserve stable-release behavior when changing development publication, and vice versa;
- distinguish build success from successful remote publication and artifact availability.

Do not add a new publication destination, trigger, credential source, tag, or release channel without explicit approval.

## Post-Publication Checks

After an authorized publication:

1. Record the workflow run or local command and source revision.
2. Confirm the expected version was published to the intended repository.
3. Verify that the actual artifact is downloadable, not merely visible in metadata.
4. Confirm documentation uses the correct coordinates and version.
5. Report any propagation delay, partial publication, or failed target explicitly.

Do not retry publication blindly after a partial failure. First determine which module/version coordinates already exist and whether the repository permits overwriting them.
