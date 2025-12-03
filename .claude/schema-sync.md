# Schema Synchronization

Instructions for updating the model files to match the latest ACP schema from https://github.com/agentclientprotocol/agent-client-protocol

## Process

### 1. Fetch Latest Schema Files

Schema URLs:
- https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.json
- https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.json
- https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/schema.unstable.json
- https://raw.githubusercontent.com/agentclientprotocol/agent-client-protocol/main/schema/meta.unstable.json

### 2. Analyze Schema Changes

Use the Task agent to analyze and compare schemas:
- Read the JSON schema files (large files, read in sections)
- Identify all types, requests, responses, and notifications
- Compare with `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/`
- Find missing types, fields, or methods
- Identify items marked as "unstable" in schema

### 3. Update Model Files

Key files in `acp-model/src/commonMain/kotlin/com/agentclientprotocol/model/`:
- `Types.kt` - Core types and value classes
- `Capabilities.kt` - Client and Agent capabilities
- `Requests.kt` - Request and Response types
- `SessionUpdate.kt` - Session update types
- `ToolCall.kt` - Tool call related types
- `Terminal.kt` - Terminal related types
- `Methods.kt` - AcpMethod enum with all protocol methods

Rules:
- Mark unstable API with `@UnstableApi` annotation
- If a type is marked `@UnstableApi`, don't mark its methods
- Add `_meta: JsonElement?` field to types extending `AcpWithMeta`
- Use `@EncodeDefault` for optional fields with defaults in capabilities
- Use `@JsonClassDiscriminator` for sealed classes

### 4. Update Agent/Client Integration

If request/response signatures changed:
- `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/Agent.kt`
- `acp/src/commonMain/kotlin/com/agentclientprotocol/agent/AgentInfo.kt`
- `acp/src/commonMain/kotlin/com/agentclientprotocol/client/Client.kt`
- `acp/src/commonMain/kotlin/com/agentclientprotocol/client/ClientInfo.kt`

### 5. Update API Dumps

Run: `./gradlew :acp-model:apiDump :acp:apiDump :acp-ktor:apiDump :acp-ktor-client:apiDump :acp-ktor-server:apiDump`

### 6. Update Schema Version Markers

Clone schema repo to get metadata:
- Repository: https://github.com/agentclientprotocol/agent-client-protocol
- Get commit hash, date, and SHA256 checksums of schema files

Update in `acp-model/`:
- `SCHEMA_VERSION.md` - commit hash, date, checksums, changelog
- `.schema-checksums` - SHA256 checksums for all 4 schema files
- `.schema-revision` - commit hash

### 7. Verify Build

Run: `./gradlew build`

## Code Patterns

### New Type
```kotlin
@Serializable
public data class NewType(
    val requiredField: String,
    val optionalField: String? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta
```

### Unstable API
```kotlin
/**
 * **UNSTABLE**
 *
 * This capability is not part of the spec yet, and may be removed or changed at any point.
 */
@UnstableApi
@Serializable
public data class UnstableType(
    val field: String,
    override val _meta: JsonElement? = null
) : AcpWithMeta
```

### New Method
```kotlin
// In Methods.kt
public object NewMethod : AcpRequestResponseMethod<NewRequest, NewResponse>(
    "method/name",
    NewRequest.serializer(),
    NewResponse.serializer()
)
```
