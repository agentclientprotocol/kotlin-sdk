package com.agentclientprotocol.rpc

import kotlinx.serialization.Serializable

/**
 * One complete JSON-RPC wire value: a single message, a batch, or malformed input.
 * Transports preserve frame boundaries so the protocol can collect batch replies into one array.
 * Use [parseTransportFrame] for wire text so syntax errors become parsing outcomes rather than
 * transport failures. Encode through this interface's serializer, not a concrete frame subtype.
 */
@Serializable(with = TransportFrameSerializer::class)
public sealed interface TransportFrame {
    /**
     * A single message or malformed value, used either on its own or as a batch member.
     * Nested batches are not supported; an array inside a batch is represented as [Malformed].
     */
    public sealed interface Entry : TransportFrame

    /**
     * One JSON-RPC message, encoded as an object rather than a one-element batch array.
     *
     * @property message The request, notification, or response carried by this entry.
     */
    public data class Single(public val message: JsonRpcMessage) : Entry

    /**
     * A non-empty JSON-RPC array whose members retain their individual parsing outcomes.
     * Malformed members do not discard valid siblings. Even a one-element batch remains an array.
     *
     * @property entries Members in source order, copied from the constructor argument so later
     * changes to the input list do not affect this batch. Members cannot themselves be batches.
     * @throws IllegalArgumentException if the supplied list is empty.
     */
    public class Batch(entries: List<Entry>) : TransportFrame {
        public val entries: List<Entry> = entries.toList()

        init {
            require(this.entries.isNotEmpty()) { "A JSON-RPC batch must not be empty" }
        }

        override fun equals(other: Any?): Boolean = other is Batch && entries == other.entries
        override fun hashCode(): Int = entries.hashCode()
        override fun toString(): String = "Batch($entries)"
    }

    /**
     * Input that cannot be decoded as a valid JSON-RPC message, retained for error handling.
     * This may represent an entire invalid frame or one invalid member of an otherwise valid batch.
     * Serialization rejects this subtype: the protocol must construct an error response instead.
     *
     * @property error The associated protocol error: parsing uses Parse Error (-32700) for input
     * rejected by kotlinx.serialization and Invalid Request (-32600) for invalid JSON-RPC envelopes
     * or batch members.
     * This error is not necessarily sent to the peer; see [isResponse].
     * @property isResponse Whether the invalid value has a response-only shape: an object with
     * `result` or `error` but no `method`. The protocol suppresses replies to these values to avoid
     * responding to malformed responses, following the Rust SDK's handling. This is a shape
     * classification, not confirmation of a valid response; unparseable JSON leaves it false.
     */
    public data class Malformed(
        public val error: JsonRpcError,
        public val isResponse: Boolean = false,
    ) : Entry
}
