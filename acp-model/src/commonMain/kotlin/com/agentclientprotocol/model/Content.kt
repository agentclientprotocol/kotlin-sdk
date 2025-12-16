@file:Suppress("unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.agentclientprotocol.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Common discriminator key used for polymorphic serialization in ACP.
 */
internal const val TYPE_DISCRIMINATOR = "type"

/**
 * Content blocks represent displayable information in the Agent Client Protocol.
 *
 * They provide a structured way to handle various types of user-facing content—whether
 * it's text from language models, images for analysis, or embedded resources for context.
 *
 * See protocol docs: [Content](https://agentclientprotocol.com/protocol/content)
 */
@Serializable
@JsonClassDiscriminator(TYPE_DISCRIMINATOR)
public sealed class ContentBlock : AcpWithMeta {
    public abstract val annotations: Annotations?

    /**
     * Plain text content
     *
     * All agents MUST support text content blocks in prompts.
     */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
        override val annotations: Annotations? = null,
        override val _meta: JsonElement? = null
    ) : ContentBlock()

    /**
     * Images for visual context or analysis.
     *
     * Requires the `image` prompt capability when included in prompts.
     */
    @Serializable
    @SerialName("image")
    public data class Image(
        val data: String,
        val mimeType: String,
        val uri: String? = null,
        override val annotations: Annotations? = null,
        override val _meta: JsonElement? = null
    ) : ContentBlock()

    /**
     * Audio data for transcription or analysis.
     *
     * Requires the `audio` prompt capability when included in prompts.
     */
    @Serializable
    @SerialName("audio")
    public data class Audio(
        val data: String,
        val mimeType: String,
        override val annotations: Annotations? = null,
        override val _meta: JsonElement? = null
    ) : ContentBlock()

    /**
     * References to resources that the agent can access.
     *
     * All agents MUST support resource links in prompts.
     */
    @Serializable
    @SerialName("resource_link")
    public data class ResourceLink(
        val name: String,
        val uri: String,
        val description: String? = null,
        val mimeType: String? = null,
        val size: Long? = null,
        val title: String? = null,
        override val annotations: Annotations? = null,
        override val _meta: JsonElement? = null
    ) : ContentBlock()

    /**
     * Complete resource contents embedded directly in the message.
     *
     * Preferred for including context as it avoids extra round-trips.
     *
     * Requires the `embeddedContext` prompt capability when included in prompts.
     */
    @Serializable
    @SerialName("resource")
    public data class Resource(
        val resource: EmbeddedResourceResource,
        override val annotations: Annotations? = null,
        override val _meta: JsonElement? = null
    ) : ContentBlock()
}

/**
 * Resource content that can be embedded in a message.
 */
@Serializable(with = EmbeddedResourceResourceSerializer::class)
public sealed class EmbeddedResourceResource : AcpWithMeta {
    /**
     * Text-based resource contents.
     */
    @Serializable
    @SerialName("TextResourceContents")
    public data class TextResourceContents(
        val text: String,
        val uri: String,
        val mimeType: String? = null,
        override val _meta: JsonElement? = null
    ) : EmbeddedResourceResource()

    /**
     * Binary resource contents.
     */
    @Serializable
    @SerialName("BlobResourceContents")
    public data class BlobResourceContents(
        val blob: String,
        val uri: String,
        val mimeType: String? = null,
        override val _meta: JsonElement? = null
    ) : EmbeddedResourceResource()
}

/**
 * Embedded resources are discriminator-less in the protocol; choose subtype by fields if
 * discriminator is absent, but still honor an explicit discriminator when provided.
 */
internal object EmbeddedResourceResourceSerializer :
    JsonContentPolymorphicSerializer<EmbeddedResourceResource>(EmbeddedResourceResource::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<EmbeddedResourceResource> {
        val obj = element.jsonObject

        val explicitType = obj[TYPE_DISCRIMINATOR]?.jsonPrimitive?.content
        when (explicitType) {
            EmbeddedResourceResource.TextResourceContents::class.simpleName -> return EmbeddedResourceResource.TextResourceContents.serializer()
            EmbeddedResourceResource.BlobResourceContents::class.simpleName -> return EmbeddedResourceResource.BlobResourceContents.serializer()
        }

        if (EmbeddedResourceResource.TextResourceContents::text.name in obj) return EmbeddedResourceResource.TextResourceContents.serializer()
        if (EmbeddedResourceResource.BlobResourceContents::blob.name in obj) return EmbeddedResourceResource.BlobResourceContents.serializer()

        throw SerializationException("Cannot determine EmbeddedResourceResource type; expected '${EmbeddedResourceResource.TextResourceContents::text.name}' or '${EmbeddedResourceResource.BlobResourceContents::blob.name}'")
    }
}

/**
 * The contents of a resource, embedded into a prompt or tool call result.
 */
@Serializable
public data class EmbeddedResource(
    val resource: EmbeddedResourceResource,
    val annotations: Annotations? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta

/**
 * A resource that the server is capable of reading, included in a prompt or tool call result.
 */
@Serializable
public data class ResourceLink(
    val name: String,
    val uri: String,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val title: String? = null,
    val annotations: Annotations? = null,
    override val _meta: JsonElement? = null
) : AcpWithMeta
