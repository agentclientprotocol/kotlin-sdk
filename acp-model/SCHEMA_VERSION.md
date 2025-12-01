# ACP Schema Version

This file tracks the version of the Agent Client Protocol schema that the model classes are based on.

## Schema Source
- Repository: https://github.com/agentclientprotocol/agent-client-protocol
- Branch: main
- Release: v0.9.1

## Schema Files

### Stable Schema
- **schema.json**
  - Commit: `e23620fe29cb24555db8fb8b58b641b680788e5f`
  - Date: 2025-12-01 17:17:19 +0100
  - SHA256: `796e38e275a1587b5a711a4b4508cb69825448b70e79b14bdb4256b24688b89d`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.json

- **meta.json**
  - Commit: `e23620fe29cb24555db8fb8b58b641b680788e5f`
  - Date: 2025-12-01 17:17:19 +0100
  - SHA256: `f242b95def9a9cbfddd2db1a45d8d3d489ad1b4f564a1322547c90a94c647637`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.json

### Unstable Schema
- **schema.unstable.json**
  - Commit: `e23620fe29cb24555db8fb8b58b641b680788e5f`
  - Date: 2025-12-01 17:17:19 +0100
  - SHA256: `4a886716877f97bc24c4c7f1ae24c9ad06a779107afbdf0718110fc50b5135b7`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.unstable.json

- **meta.unstable.json**
  - Commit: `e23620fe29cb24555db8fb8b58b641b680788e5f`
  - Date: 2025-12-01 17:17:19 +0100
  - SHA256: `8ad80116767e0921970b28766c214d7921268653544d5996a8e2814e1244d4d2`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.unstable.json

**Note:** All types from unstable schema must be marked with `@UnstableApi` annotation.

## Last Updated
- Date: 2025-12-01
- Updated by: Manual schema synchronization

## Changes in This Version
- Added `Implementation` type for client/agent identification
- Added `SessionCapabilities` type (empty, for future expansion)
- Added `clientInfo` field to `InitializeRequest`
- Added `agentInfo` field to `InitializeResponse`
- Added `sessionCapabilities` field to `AgentCapabilities`
- Changed `AvailableCommandInput` from data class to sealed class with `Unstructured` variant
- Added `_meta` field to `ToolCallContent.Terminal`
- Terminal types are now stable (removed unstable warning)

## Verification
To verify the schema files match:
```bash
# Stable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.json | sha256sum
# Expected: 796e38e275a1587b5a711a4b4508cb69825448b70e79b14bdb4256b24688b89d

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.json | sha256sum
# Expected: f242b95def9a9cbfddd2db1a45d8d3d489ad1b4f564a1322547c90a94c647637

# Unstable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.unstable.json | sha256sum
# Expected: 4a886716877f97bc24c4c7f1ae24c9ad06a779107afbdf0718110fc50b5135b7

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.unstable.json | sha256sum
# Expected: 8ad80116767e0921970b28766c214d7921268653544d5996a8e2814e1244d4d2
```
