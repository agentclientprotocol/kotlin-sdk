package com.agentclientprotocol.model.v2

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Base serializer for open string enums in the v2 protocol schema.
 *
 * Open enums list their known values explicitly and capture any unrecognized string in an
 * `Unknown` fallback variant instead of failing deserialization. This keeps older
 * implementations forward-compatible when newer ACP versions or extensions add variants:
 *
 * - Values beginning with `_` are reserved for implementation-specific extensions.
 * - Values that do not begin with `_` are reserved for ACP, including future ACP variants.
 * - Unknown values are preserved as-is so they survive storing, replaying, proxying,
 *   and forwarding.
 *
 * See the v2 [Enum Variant Extension](https://agentclientprotocol.com/rfds/v2/enum-variant-extension) RFD.
 *
 * @param serialName fully-qualified serial name of the enum type
 * @param knownValues all known values of the enum, excluding the `Unknown` fallback
 * @param wireValue extracts the wire-format string of a value
 * @param unknown creates the fallback variant carrying an unrecognized wire string
 */
internal abstract class OpenStringEnumSerializer<T : Any>(
    serialName: String,
    knownValues: List<T>,
    private val wireValue: (T) -> String,
    private val unknown: (String) -> T,
) : KSerializer<T> {
    private val known: Map<String, T> = knownValues.associateBy(wireValue)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(wireValue(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return known[raw] ?: unknown(raw)
    }
}
