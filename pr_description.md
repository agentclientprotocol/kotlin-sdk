## Summary

Adds first-class JSON-RPC 2.0 batch support to the shared ACP protocol and both built-in transports, while refactoring the JSON-RPC wire model, serialization, and request lifecycle handling.

## Changes

- Introduces `TransportFrame` with single, non-empty batch, and receive-only malformed variants.
- Replaces the ad hoc JSON-RPC decoder and manual JSON assembly with custom kotlinx.serialization serializers that validate envelopes and isolate invalid batch entries without discarding valid siblings.
- Models responses as distinct success and error types, adds explicit null request IDs, and guarantees that wire responses contain exactly one of `result` or `error`.
- Extends `Protocol` to process mixed request/notification batches, aggregate replies, correlate responses by ID, and handle malformed entries, cancellation, duplicate IDs, and notification-only batches correctly.
- Adds `sendBatchRequestRaw` for explicit outgoing batches. Results follow request order, notifications are omitted, and sibling requests retain independent success or failure outcomes.
- Replaces implicit post-request coroutine scheduling with `RequestOutcome`, making response-before-stream ordering and cleanup explicit for session initialization and ACP v2 prompt streaming.
- Keeps existing typed Agent/Client operations standalone.

This intentionally changes low-level JSON-RPC and transport APIs; the higher-level Agent, Client, and session usage remains unchanged.

## Testing

- Adds coverage for parsing and serialization, partial batch failures, response correlation, cancellation and cleanup, singleton batches, and malformed input.
- Expands Stdio and WebSocket transport tests, including queueing, shutdown, and frame-preservation behavior.
