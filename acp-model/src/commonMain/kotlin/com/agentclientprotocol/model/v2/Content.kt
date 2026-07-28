@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model.v2

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpWithMeta
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Theme an icon is designed for.
 *
 * This is an open enum: unrecognized wire values deserialize to [Unknown] instead of
 * failing, so newer ACP variants and `_`-prefixed extensions degrade gracefully.
 */
@UnstableApi
@Serializable(with = IconThemeSerializer::class)
public sealed class IconTheme {
    /**
     * The wire-format string for this theme.
     */
    public abstract val value: String

    /**
     * Icon designed for light backgrounds.
     */
    public data object Light : IconTheme() {
        override val value: String = "light"
    }

    /**
     * Icon designed for dark backgrounds.
     */
    public data object Dark : IconTheme() {
        override val value: String = "dark"
    }

    /**
     * Custom or future icon theme.
     *
     * Values beginning with `_` are reserved for implementation-specific extensions.
     * Unknown values that do not begin with `_` are reserved for future ACP variants
     * and MUST NOT be treated as custom extensions.
     *
     * Receivers that cannot act on an unknown theme SHOULD preserve it when storing,
     * replaying, proxying, or forwarding protocol data, and otherwise degrade gracefully
     * according to the field's semantics.
     */
    public data class Unknown(override val value: String) : IconTheme()

    public companion object {
        /**
         * Creates an implementation-specific extension theme.
         *
         * Extension values must begin with `_` — all other values are reserved for ACP,
         * including future ACP variants.
         *
         * @throws IllegalArgumentException if [value] does not begin with `_`
         */
        public fun extension(value: String): Unknown {
            require(value.startsWith('_')) {
                "Extension values must begin with '_'; values without the prefix are reserved for ACP (got '$value')"
            }
            return Unknown(value)
        }
    }
}

@OptIn(UnstableApi::class)
internal object IconThemeSerializer : OpenStringEnumSerializer<IconTheme>(
    serialName = "com.agentclientprotocol.model.v2.IconTheme",
    knownValues = listOf(
        IconTheme.Light,
        IconTheme.Dark,
    ),
    wireValue = IconTheme::value,
    unknown = IconTheme::Unknown,
)

/**
 * A sized icon that the client can display in a user interface.
 */
@UnstableApi
@Serializable
public data class Icon(
    val src: String,
    val mimeType: String? = null,
    val sizes: List<String>? = null,
    val theme: IconTheme? = null,
)

/**
 * Optional annotations for the client. The client can use annotations to inform how
 * objects are used or displayed.
 *
 * Unlike v1, [audience] uses the open v2 [Role], so unknown roles are preserved.
 */
@UnstableApi
@Serializable
public data class Annotations(
    val audience: List<Role>? = null,
    val priority: Double? = null,
    val lastModified: String? = null,
    override val _meta: JsonElement? = null,
) : AcpWithMeta

/**
 * Resource content that can be embedded in a message.
 *
 * This union is discriminated by shape, not by a `type` field: a payload with `text` is
 * text-based, a payload with `blob` is binary. It stays **closed** in v2 — without a
 * discriminator there is nothing safe to key a fallback on.
 */
@UnstableApi
@Serializable(with = EmbeddedResourceResourceSerializer::class)
public sealed class EmbeddedResourceResource : AcpWithMeta {
    /**
     * Text resource contents embedded directly in the message.
     */
    @Serializable
    public data class TextResourceContents(
        val text: String,
        val uri: String,
        val mimeType: String? = null,
        override val _meta: JsonElement? = null,
    ) : EmbeddedResourceResource()

    /**
     * Binary resource contents embedded directly in the message.
     */
    @Serializable
    public data class BlobResourceContents(
        val blob: String,
        val uri: String,
        val mimeType: String? = null,
        override val _meta: JsonElement? = null,
    ) : EmbeddedResourceResource()
}

@OptIn(UnstableApi::class)
internal object EmbeddedResourceResourceSerializer : KSerializer<EmbeddedResourceResource> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.agentclientprotocol.model.v2.EmbeddedResourceResource")

    override fun serialize(encoder: Encoder, value: EmbeddedResourceResource) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is EmbeddedResourceResource.TextResourceContents ->
                jsonEncoder.json.encodeToJsonElement(EmbeddedResourceResource.TextResourceContents.serializer(), value)
            is EmbeddedResourceResource.BlobResourceContents ->
                jsonEncoder.json.encodeToJsonElement(EmbeddedResourceResource.BlobResourceContents.serializer(), value)
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): EmbeddedResourceResource {
        val jsonDecoder = decoder as JsonDecoder
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            "text" in jsonObject -> jsonDecoder.json.decodeFromJsonElement(
                EmbeddedResourceResource.TextResourceContents.serializer(),
                jsonObject,
            )
            "blob" in jsonObject -> jsonDecoder.json.decodeFromJsonElement(
                EmbeddedResourceResource.BlobResourceContents.serializer(),
                jsonObject,
            )
            else -> throw SerializationException(
                "Cannot determine EmbeddedResourceResource shape; expected a 'text' or 'blob' field"
            )
        }
    }
}

/**
 * Content blocks represent displayable information in the Agent Client Protocol.
 *
 * They provide a structured way to handle various types of user-facing content—whether
 * it's text from language models, images for analysis, or embedded resources for context.
 *
 * This is an open tagged union: an unrecognized `type` discriminator deserializes to
 * [Unknown] with the full raw JSON preserved, so newer ACP variants and `_`-prefixed
 * extensions degrade gracefully.
 *
 * See protocol docs: [Content](https://agentclientprotocol.com/protocol/content)
 */
@UnstableApi
@Serializable(with = ContentBlockSerializer::class)
public sealed class ContentBlock {
    /**
     * Text content. May be plain text or formatted with Markdown.
     *
     * All agents MUST support text content blocks in prompts.
     * Clients SHOULD render this text as Markdown.
     */
    @Serializable
    public data class Text(
        val text: String,
        val annotations: Annotations? = null,
        override val _meta: JsonElement? = null,
    ) : ContentBlock(), AcpWithMeta

    /**
     * Images for visual context or analysis.
     *
     * Requires the `image` prompt capability when included in prompts.
     */
    @Serializable
    public data class Image(
        val data: String,
        val mimeType: String,
        val uri: String? = null,
        val annotations: Annotations? = null,
        override val _meta: JsonElement? = null,
    ) : ContentBlock(), AcpWithMeta

    /**
     * Audio data for transcription or analysis.
     *
     * Requires the `audio` prompt capability when included in prompts.
     */
    @Serializable
    public data class Audio(
        val data: String,
        val mimeType: String,
        val annotations: Annotations? = null,
        override val _meta: JsonElement? = null,
    ) : ContentBlock(), AcpWithMeta

    /**
     * References to resources that the agent can access.
     *
     * All agents MUST support resource links in prompts.
     */
    @Serializable
    public data class ResourceLink(
        val name: String,
        val uri: String,
        val description: String? = null,
        val mimeType: String? = null,
        val size: Long? = null,
        val title: String? = null,
        val icons: List<Icon>? = null,
        val annotations: Annotations? = null,
        override val _meta: JsonElement? = null,
    ) : ContentBlock(), AcpWithMeta

    /**
     * Complete resource contents embedded directly in the message.
     *
     * Preferred for including context as it avoids extra round-trips.
     *
     * Requires the `embeddedContext` prompt capability when included in prompts.
     */
    @Serializable
    public data class Resource(
        val resource: EmbeddedResourceResource,
        val annotations: Annotations? = null,
        override val _meta: JsonElement? = null,
    ) : ContentBlock(), AcpWithMeta

    /**
     * Custom or future content block.
     *
     * Discriminator values beginning with `_` are reserved for implementation-specific
     * extensions. Unknown values that do not begin with `_` are reserved for future ACP
     * variants and MUST NOT be treated as custom extensions.
     *
     * [rawJson] holds the complete payload as received (including the discriminator), so
     * re-serializing emits it byte-identically. Receivers that do not understand this
     * content block type SHOULD preserve it when storing, replaying, proxying, or
     * forwarding content, and otherwise ignore it or display it generically.
     */
    public data class Unknown(val type: String, val rawJson: JsonObject) : ContentBlock()
}

@OptIn(UnstableApi::class)
internal object ContentBlockSerializer : OpenTaggedUnionSerializer<ContentBlock>(
    serialName = "com.agentclientprotocol.model.v2.ContentBlock",
    discriminatorKey = "type",
    known = mapOf(
        "text" to ContentBlock.Text.serializer(),
        "image" to ContentBlock.Image.serializer(),
        "audio" to ContentBlock.Audio.serializer(),
        "resource_link" to ContentBlock.ResourceLink.serializer(),
        "resource" to ContentBlock.Resource.serializer(),
    ),
    discriminator = { value ->
        when (value) {
            is ContentBlock.Text -> "text"
            is ContentBlock.Image -> "image"
            is ContentBlock.Audio -> "audio"
            is ContentBlock.ResourceLink -> "resource_link"
            is ContentBlock.Resource -> "resource"
            is ContentBlock.Unknown -> value.type
        }
    },
    unknown = ContentBlock::Unknown,
    rawJson = { (it as? ContentBlock.Unknown)?.rawJson },
)
