@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Annotations as V1Annotations
import com.agentclientprotocol.model.ContentBlock as V1ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource as V1EmbeddedResourceResource
import com.agentclientprotocol.model.v2.Annotations
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.EmbeddedResourceResource

/**
 * Converts these v2 annotations to their v1 equivalent.
 *
 * This conversion is total: audience roles that have no v1 representation are
 * skipped rather than failing — annotations are display hints, not semantics.
 */
@UnstableApi
public fun Annotations.toV1(): V1Annotations = V1Annotations(
    audience = audience?.mapNotNull { role ->
        try {
            role.toV1()
        } catch (_: ProtocolConversionException) {
            null
        }
    },
    priority = priority,
    lastModified = lastModified,
    _meta = _meta,
)

/**
 * Converts these v1 annotations to their v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1Annotations.toV2(): Annotations = Annotations(
    audience = audience?.map { it.toV2() },
    priority = priority,
    lastModified = lastModified,
    _meta = _meta,
)

/**
 * Converts this v2 embedded resource to its v1 equivalent.
 *
 * This conversion is total: the v1 and v2 shapes are identical.
 */
@UnstableApi
public fun EmbeddedResourceResource.toV1(): V1EmbeddedResourceResource = when (this) {
    is EmbeddedResourceResource.TextResourceContents -> V1EmbeddedResourceResource.TextResourceContents(
        text = text,
        uri = uri,
        mimeType = mimeType,
        _meta = _meta,
    )
    is EmbeddedResourceResource.BlobResourceContents -> V1EmbeddedResourceResource.BlobResourceContents(
        blob = blob,
        uri = uri,
        mimeType = mimeType,
        _meta = _meta,
    )
}

/**
 * Converts this v1 embedded resource to its v2 equivalent.
 *
 * This conversion is total: the v1 and v2 shapes are identical.
 */
@UnstableApi
public fun V1EmbeddedResourceResource.toV2(): EmbeddedResourceResource = when (this) {
    is V1EmbeddedResourceResource.TextResourceContents -> EmbeddedResourceResource.TextResourceContents(
        text = text,
        uri = uri,
        mimeType = mimeType,
        _meta = _meta,
    )
    is V1EmbeddedResourceResource.BlobResourceContents -> EmbeddedResourceResource.BlobResourceContents(
        blob = blob,
        uri = uri,
        mimeType = mimeType,
        _meta = _meta,
    )
}

/**
 * Converts this v2 content block to its v1 equivalent.
 *
 * @throws ProtocolConversionException if this is an [ContentBlock.Unknown] block, or a
 * [ContentBlock.ResourceLink] with non-empty `icons` — v1 cannot represent either
 * without data loss
 */
@UnstableApi
public fun ContentBlock.toV1(): V1ContentBlock = when (this) {
    is ContentBlock.Text -> V1ContentBlock.Text(
        text = text,
        annotations = annotations?.toV1(),
        _meta = _meta,
    )
    is ContentBlock.Image -> V1ContentBlock.Image(
        data = data,
        mimeType = mimeType,
        uri = uri,
        annotations = annotations?.toV1(),
        _meta = _meta,
    )
    is ContentBlock.Audio -> V1ContentBlock.Audio(
        data = data,
        mimeType = mimeType,
        annotations = annotations?.toV1(),
        _meta = _meta,
    )
    is ContentBlock.ResourceLink -> {
        if (!icons.isNullOrEmpty()) {
            throw ProtocolConversionException("v2 ResourceLink.icons cannot be represented in v1")
        }
        V1ContentBlock.ResourceLink(
            name = name,
            uri = uri,
            description = description,
            mimeType = mimeType,
            size = size,
            title = title,
            annotations = annotations?.toV1(),
            _meta = _meta,
        )
    }
    is ContentBlock.Resource -> V1ContentBlock.Resource(
        resource = resource.toV1(),
        annotations = annotations?.toV1(),
        _meta = _meta,
    )
    is ContentBlock.Unknown -> throw unknownV2EnumVariant("ContentBlock", type)
}

/**
 * Converts this v1 content block to its v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1ContentBlock.toV2(): ContentBlock = when (this) {
    is V1ContentBlock.Text -> ContentBlock.Text(
        text = text,
        annotations = annotations?.toV2(),
        _meta = _meta,
    )
    is V1ContentBlock.Image -> ContentBlock.Image(
        data = data,
        mimeType = mimeType,
        uri = uri,
        annotations = annotations?.toV2(),
        _meta = _meta,
    )
    is V1ContentBlock.Audio -> ContentBlock.Audio(
        data = data,
        mimeType = mimeType,
        annotations = annotations?.toV2(),
        _meta = _meta,
    )
    is V1ContentBlock.ResourceLink -> ContentBlock.ResourceLink(
        name = name,
        uri = uri,
        description = description,
        mimeType = mimeType,
        size = size,
        title = title,
        annotations = annotations?.toV2(),
        _meta = _meta,
    )
    is V1ContentBlock.Resource -> ContentBlock.Resource(
        resource = resource.toV2(),
        annotations = annotations?.toV2(),
        _meta = _meta,
    )
}
