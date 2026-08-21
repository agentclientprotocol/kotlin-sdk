package com.agentclientprotocol.protocol

import com.agentclientprotocol.model.ProtocolVersion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reads `protocolVersion` before an initialize payload's version-specific shape is decoded. */
internal fun readProtocolVersionOrNull(payload: JsonElement?): ProtocolVersion? {
    val field = (payload as? JsonObject)?.get("protocolVersion") ?: return null
    val primitive = field as? JsonPrimitive ?: return null
    return if (primitive.isString) null else primitive.intOrNull
}
