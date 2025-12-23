# ACP Schema Version

This file tracks the version of the Agent Client Protocol schema that the model classes are based on.

## Schema Source
- Repository: https://github.com/agentclientprotocol/agent-client-protocol
- Branch: main
- Release: v0.9.1

## Schema Files

### Stable Schema
- **schema.json**
  - Commit: `37d12f4bb4b0cf1dca60421d48cb17decc119b8e`
  - Date: 2025-12-20 18:25:51 +0000
  - SHA256: `07f2cdfd7814496ebdab83fb7417787fa1dddc6276b11cd31b51be2f8ba95f2b`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.json

- **meta.json**
  - Commit: `37d12f4bb4b0cf1dca60421d48cb17decc119b8e`
  - Date: 2025-12-20 18:25:51 +0000
  - SHA256: `f242b95def9a9cbfddd2db1a45d8d3d489ad1b4f564a1322547c90a94c647637`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.json

### Unstable Schema
- **schema.unstable.json**
  - Commit: `37d12f4bb4b0cf1dca60421d48cb17decc119b8e`
  - Date: 2025-12-20 18:25:51 +0000
  - SHA256: `75d180b9c589cd7d79a5a9cbb6680d52e1bce71238839871441d30c753b497a9`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/schema.unstable.json

- **meta.unstable.json**
  - Commit: `37d12f4bb4b0cf1dca60421d48cb17decc119b8e`
  - Date: 2025-12-20 18:25:51 +0000
  - SHA256: `e87ae9b3fc3b05f88da6dfc06d003e5263d6a041b1c934ed13b83173b39ed111`
  - URL: https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/meta.unstable.json

**Note:** All types from unstable schema must be marked with `@UnstableApi` annotation.

## Last Updated
- Date: 2025-12-20
- Updated by: Manual schema synchronization

## Changes in This Version
- Added `session/fork` method to agent methods (unstable)
- Added `session/list` method to agent methods (unstable)
- Added `session/resume` method to agent methods (unstable)
- Added `session/set_config_option` method to agent methods (unstable)
- Added `session/set_model` method to agent methods (unstable)
- Added `$/cancel_request` protocol method (unstable)
- Updated stable schema with bug fixes and improvements

## Verification
To verify the schema files match:
```bash
# Stable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.json | sha256sum
# Expected: 07f2cdfd7814496ebdab83fb7417787fa1dddc6276b11cd31b51be2f8ba95f2b

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.json | sha256sum
# Expected: f242b95def9a9cbfddd2db1a45d8d3d489ad1b4f564a1322547c90a94c647637

# Unstable schemas
curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.unstable.json | sha256sum
# Expected: 75d180b9c589cd7d79a5a9cbb6680d52e1bce71238839871441d30c753b497a9

curl -s https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.unstable.json | sha256sum
# Expected: e87ae9b3fc3b05f88da6dfc06d003e5263d6a041b1c934ed13b83173b39ed111
```
