package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/**
 * A list field with the Rust schema's `DefaultOnError<VecSkipError<_>>` semantics: an item that fails to decode
 * is skipped, and a `null` or non-array value decodes as an empty list. Encoding is the plain list encoding.
 */
internal open class LenientListSerializer<T>(private val itemSerializer: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(itemSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            try {
                jsonDecoder.json.decodeFromJsonElement(itemSerializer, item)
            } catch (_: Exception) {
                // VecSkipError parity; see decodeMaybeUndefined for why the catch is broad.
                null
            }
        }
    }
}

/**
 * An optional field with the Rust schema's `DefaultOnError` semantics: a present value that fails to decode
 * becomes `null` instead of failing the enclosing object.
 */
internal open class DefaultOnErrorSerializer<T : Any>(private val serializer: KSerializer<T>) : KSerializer<T?> {
    private val delegate = serializer.nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: T?) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T? {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return try {
            jsonDecoder.json.decodeFromJsonElement(serializer, element)
        } catch (_: Exception) {
            // DefaultOnError parity; see decodeMaybeUndefined for why the catch is broad.
            null
        }
    }
}

@OptIn(UnstableApi::class)
internal object LenientMcpServerListSerializer : LenientListSerializer<McpServer>(McpServer.serializer())

internal object LenientStringListSerializer : LenientListSerializer<String>(String.serializer())

@OptIn(UnstableApi::class)
internal object LenientSessionConfigOptionListSerializer :
    LenientListSerializer<SessionConfigOption>(SessionConfigOption.serializer())

@OptIn(UnstableApi::class)
internal object LenientAvailableCommandListSerializer :
    LenientListSerializer<AvailableCommand>(AvailableCommand.serializer())

@OptIn(UnstableApi::class)
internal object ReplayFromOrNullSerializer : DefaultOnErrorSerializer<ReplayFrom>(ReplayFrom.serializer())
