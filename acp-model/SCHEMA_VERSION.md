# ACP Schema Version

This file tracks the version of the Agent Client Protocol schema that the model classes are based on.

## Schema Source
- Repository: https://github.com/agentclientprotocol/agent-client-protocol
- Branch: main
- Baseline commit: `a26839cc3ab0a746eff1fb95a02e87a5946ad631`
- Baseline commit date: 2026-04-19 12:29:33 +0100
- Baseline commit subject: `Optional current provider (#1021)`

## Schema Files

### Stable Schema
- **schema.json**
  - Commit: `a26839cc3ab0a746eff1fb95a02e87a5946ad631`
  - Date: 2026-04-19 12:29:33 +0100
  - SHA256: `38751907a86016c73662ea2ad1ded2c8cbbdb99fe40b8368b6dd2794726be236`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.json

- **meta.json**
  - Commit: `a26839cc3ab0a746eff1fb95a02e87a5946ad631`
  - Date: 2026-04-19 12:29:33 +0100
  - SHA256: `71036d4c70f1e964c445569c1878a513e2fadfc797161d7582224b4647669330`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.json

### Unstable Schema
- **schema.unstable.json**
  - Commit: `a26839cc3ab0a746eff1fb95a02e87a5946ad631`
  - Date: 2026-04-19 12:29:33 +0100
  - SHA256: `cb3cf31dae7e482dc45389f15e23edff037abaeccbffbe3b6d8eb1f9c00b88a5`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.unstable.json

- **meta.unstable.json**
  - Commit: `a26839cc3ab0a746eff1fb95a02e87a5946ad631`
  - Date: 2026-04-19 12:29:33 +0100
  - SHA256: `0a030d3641d0cbc71c433fc2ef68e33ee3c5707220c3ceb662ccb2e6f656e026`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.unstable.json

**Note:** All types from unstable schema must be marked with `@UnstableApi` annotation.

## Last Updated
- Date: 2026-04-19
- Updated by: Manual schema synchronization

## Changes in This Version
- `ProviderInfo.current` is optional now (`required` no longer includes `current`).
- Disabled provider is valid both when `current` is omitted and when it is explicitly `null`.
- Unstable providers schema (`providers/list`, `providers/set`, `providers/disable`) is synced to current `main` HEAD.

## Verification
To verify the schema files match:
```bash
# Stable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.json | sha256sum
# Expected: 38751907a86016c73662ea2ad1ded2c8cbbdb99fe40b8368b6dd2794726be236

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.json | sha256sum
# Expected: 71036d4c70f1e964c445569c1878a513e2fadfc797161d7582224b4647669330

# Unstable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.unstable.json | sha256sum
# Expected: cb3cf31dae7e482dc45389f15e23edff037abaeccbffbe3b6d8eb1f9c00b88a5

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.unstable.json | sha256sum
# Expected: 0a030d3641d0cbc71c433fc2ef68e33ee3c5707220c3ceb662ccb2e6f656e026
```
