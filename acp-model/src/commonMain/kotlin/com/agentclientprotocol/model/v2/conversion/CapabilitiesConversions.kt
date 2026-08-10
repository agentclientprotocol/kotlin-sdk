@file:Suppress("unused")

package com.agentclientprotocol.model.v2.conversion

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionDeleteCapabilities
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.v2.AgentAuthCapabilities
import com.agentclientprotocol.model.v2.AgentCapabilities
import com.agentclientprotocol.model.v2.AuthCapabilities
import com.agentclientprotocol.model.v2.ClientCapabilities
import com.agentclientprotocol.model.v2.McpCapabilities
import com.agentclientprotocol.model.v2.McpHttpCapabilities
import com.agentclientprotocol.model.v2.McpStdioCapabilities
import com.agentclientprotocol.model.v2.PromptAudioCapabilities
import com.agentclientprotocol.model.v2.PromptCapabilities
import com.agentclientprotocol.model.v2.PromptEmbeddedContextCapabilities
import com.agentclientprotocol.model.v2.PromptImageCapabilities
import com.agentclientprotocol.model.v2.SessionCapabilities
import com.agentclientprotocol.model.v2.SessionDeleteCapabilities as V2SessionDeleteCapabilities
import com.agentclientprotocol.model.AgentAuthCapabilities as V1AgentAuthCapabilities
import com.agentclientprotocol.model.AgentCapabilities as V1AgentCapabilities
import com.agentclientprotocol.model.AuthCapabilities as V1AuthCapabilities
import com.agentclientprotocol.model.ClientCapabilities as V1ClientCapabilities
import com.agentclientprotocol.model.McpCapabilities as V1McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities as V1PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities as V1SessionCapabilities

// === Prompt capabilities ===

/**
 * Converts these v2 prompt capabilities to their v1 equivalent.
 *
 * Each v2 marker object collapses to the corresponding v1 boolean: present becomes `true`,
 * absent becomes `false`.
 *
 * @throws ProtocolConversionException if any marker carries `_meta` — a v1 boolean has
 * nowhere to put it
 */
@UnstableApi
public fun PromptCapabilities.toV1(): V1PromptCapabilities {
    rejectV2MarkerMeta("PromptCapabilities", "image", image?._meta)
    rejectV2MarkerMeta("PromptCapabilities", "audio", audio?._meta)
    rejectV2MarkerMeta("PromptCapabilities", "embeddedContext", embeddedContext?._meta)
    return V1PromptCapabilities(
        audio = audio != null,
        image = image != null,
        embeddedContext = embeddedContext != null,
        _meta = _meta,
    )
}

/**
 * Converts these v1 prompt capabilities to their v2 equivalent.
 *
 * Each v1 boolean expands to the corresponding v2 marker object: `true` becomes `{}`,
 * `false` becomes absent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1PromptCapabilities.toV2(): PromptCapabilities = PromptCapabilities(
    image = if (image) PromptImageCapabilities() else null,
    audio = if (audio) PromptAudioCapabilities() else null,
    embeddedContext = if (embeddedContext) PromptEmbeddedContextCapabilities() else null,
    _meta = _meta,
)

// === MCP capabilities ===

/**
 * Converts these v2 MCP capabilities to their v1 equivalent.
 *
 * [McpCapabilities.stdio] is dropped: stdio support is implicit in v1 and has no field to
 * carry it, so an agent that opted out of stdio in v2 looks like a stdio-capable agent to
 * a v1 peer. v1's removed `sse` transport is always reported as unsupported.
 *
 * @throws ProtocolConversionException if [McpCapabilities.acp] is set — the v1 Kotlin
 * model has no `acp` field — or if any marker carries `_meta`, which a v1 boolean has
 * nowhere to put
 */
@UnstableApi
public fun McpCapabilities.toV1(): V1McpCapabilities {
    rejectV2MarkerMeta("McpCapabilities", "stdio", stdio?._meta)
    rejectV2MarkerMeta("McpCapabilities", "http", http?._meta)
    if (acp != null) throw unrepresentableV2Field("McpCapabilities", "acp")
    return V1McpCapabilities(
        http = http != null,
        sse = false,
        _meta = _meta,
    )
}

/**
 * Converts these v1 MCP capabilities to their v2 equivalent.
 *
 * [McpCapabilities.stdio] is always synthesized: v1 has no way to *not* support stdio.
 *
 * @throws ProtocolConversionException if `sse` is advertised — the SSE transport was
 * removed in v2
 */
@UnstableApi
public fun V1McpCapabilities.toV2(): McpCapabilities {
    if (sse) throw unrepresentableV1Field("McpCapabilities", "sse")
    return McpCapabilities(
        stdio = McpStdioCapabilities(),
        http = if (http) McpHttpCapabilities() else null,
        acp = null,
        _meta = _meta,
    )
}

// === Session capabilities ===

/**
 * The v1 agent capability fields represented by v2 [SessionCapabilities].
 *
 * v2 folds these four top-level v1 fields into a single nested `session` group, so a
 * dual-version implementation can build capabilities once in v2 shape and derive the v1
 * shape from it.
 */
@UnstableApi
public data class V1SessionCapabilityParts(
    val sessionCapabilities: V1SessionCapabilities,
    val promptCapabilities: V1PromptCapabilities,
    val loadSession: Boolean,
    val mcpCapabilities: V1McpCapabilities,
)

/**
 * Splits these v2 session capabilities into the v1 agent capability fields that v2 groups
 * under `session`.
 *
 * Advertising `session` at all commits a v2 agent to the session baseline, so the v1
 * markers for the methods that became baseline — `list`, `resume`, `close` and
 * `loadSession` — are synthesized here.
 *
 * @throws ProtocolConversionException if a nested capability has no v1 representation
 */
@UnstableApi
public fun SessionCapabilities.toV1Parts(): V1SessionCapabilityParts {
    return V1SessionCapabilityParts(
        sessionCapabilities = V1SessionCapabilities(
            fork = fork,
            list = SessionListCapabilities(),
            resume = SessionResumeCapabilities(),
            delete = delete?.let { SessionDeleteCapabilities(_meta = it._meta) },
            close = SessionCloseCapabilities(),
            additionalDirectories = additionalDirectories,
            _meta = _meta,
        ),
        promptCapabilities = (prompt ?: PromptCapabilities()).toV1(),
        loadSession = true,
        mcpCapabilities = (mcp ?: McpCapabilities()).toV1(),
    )
}

/**
 * Builds v2 session capabilities from the v1 agent capability fields that now live under
 * `session` in v2.
 *
 * A v1 agent only has a v2 representation once it already meets the v2 baseline, so this
 * refuses capabilities that gate any of the now-mandatory session methods.
 *
 * @throws ProtocolConversionException if [loadSession] is `false`, if any of the v1
 * `list` / `resume` / `close` markers is absent or carries `_meta` — v2 has no marker
 * object left to hang it off — or if a nested capability has no v2 representation
 */
@UnstableApi
public fun SessionCapabilities.Companion.fromV1(
    sessionCapabilities: V1SessionCapabilities,
    promptCapabilities: V1PromptCapabilities,
    loadSession: Boolean,
    mcpCapabilities: V1McpCapabilities,
): SessionCapabilities {
    if (!loadSession) throw unrepresentableV1Field("AgentCapabilities", "loadSession")

    val list = sessionCapabilities.list ?: throw unrepresentableV1Field("SessionCapabilities", "list")
    rejectV1MarkerMeta("SessionCapabilities", "list", list._meta)

    val resume = sessionCapabilities.resume ?: throw unrepresentableV1Field("SessionCapabilities", "resume")
    rejectV1MarkerMeta("SessionCapabilities", "resume", resume._meta)

    val close = sessionCapabilities.close ?: throw unrepresentableV1Field("SessionCapabilities", "close")
    rejectV1MarkerMeta("SessionCapabilities", "close", close._meta)

    return SessionCapabilities(
        prompt = promptCapabilities.toV2(),
        mcp = mcpCapabilities.toV2(),
        delete = sessionCapabilities.delete?.let { V2SessionDeleteCapabilities(_meta = it._meta) },
        additionalDirectories = sessionCapabilities.additionalDirectories,
        fork = sessionCapabilities.fork,
        _meta = sessionCapabilities._meta,
    )
}

// === Agent capabilities ===

/**
 * Converts these v2 agent auth capabilities to their v1 equivalent.
 *
 * The v1 `logout` marker is left unset here: in v2 logout support is advertised by a
 * non-empty [authMethods][com.agentclientprotocol.model.v2.InitializeResponse.authMethods]
 * list, so it is materialized when converting the whole initialize response rather than
 * the capability object on its own.
 *
 * This conversion is total: every v2 value has a v1 representation.
 */
@UnstableApi
public fun AgentAuthCapabilities.toV1(): V1AgentAuthCapabilities = V1AgentAuthCapabilities(
    logout = null,
    _meta = _meta,
)

/**
 * Converts these v1 agent auth capabilities to their v2 equivalent.
 *
 * @throws ProtocolConversionException if `logout` is set — v2 dropped the marker, and
 * logout support is instead implied by a non-empty `authMethods` list on the initialize
 * response, which this object alone cannot express
 */
@UnstableApi
public fun V1AgentAuthCapabilities.toV2(): AgentAuthCapabilities {
    if (logout != null) throw unrepresentableV1Field("AgentAuthCapabilities", "logout")
    return AgentAuthCapabilities(_meta = _meta)
}

/**
 * Converts v1's non-optional agent auth capabilities to v2's optional field, collapsing an
 * all-default v1 object to `null`.
 */
@UnstableApi
internal fun V1AgentAuthCapabilities.toV2OrNull(): AgentAuthCapabilities? {
    if (logout != null) throw unrepresentableV1Field("AgentAuthCapabilities", "logout")
    return if (_meta == null) null else AgentAuthCapabilities(_meta = _meta)
}

/**
 * Converts these v2 agent capabilities to their v1 equivalent.
 *
 * The session group is un-nested back into v1's four top-level fields — see
 * [toV1Parts] for the details and for what gets synthesized.
 *
 * @throws ProtocolConversionException if [AgentCapabilities.session] is absent — a v1
 * agent has no way to say it does not support the session method surface — or if a nested
 * capability has no v1 representation
 */
@UnstableApi
public fun AgentCapabilities.toV1(): V1AgentCapabilities {
    val session = session
        ?: throw ProtocolConversionException("v2 AgentCapabilities without `session` cannot be represented in v1")
    val parts = session.toV1Parts()
    return V1AgentCapabilities(
        loadSession = parts.loadSession,
        promptCapabilities = parts.promptCapabilities,
        mcpCapabilities = parts.mcpCapabilities,
        sessionCapabilities = parts.sessionCapabilities,
        auth = auth?.toV1() ?: V1AgentAuthCapabilities(),
        nes = nes,
        positionEncoding = positionEncoding,
        providers = providers,
        _meta = _meta,
    )
}

/**
 * Converts these v1 agent capabilities to their v2 equivalent.
 *
 * The four session-scoped v1 fields are folded into [AgentCapabilities.session] — see
 * [fromV1] for the baseline a v1 agent must already meet.
 *
 * @throws ProtocolConversionException if the v1 capabilities do not meet the v2 session
 * baseline, or if a nested capability has no v2 representation
 */
@UnstableApi
public fun V1AgentCapabilities.toV2(): AgentCapabilities = AgentCapabilities(
    session = SessionCapabilities.fromV1(
        sessionCapabilities = sessionCapabilities,
        promptCapabilities = promptCapabilities,
        loadSession = loadSession,
        mcpCapabilities = mcpCapabilities,
    ),
    auth = auth.toV2OrNull(),
    providers = providers,
    nes = nes,
    positionEncoding = positionEncoding,
    _meta = _meta,
)

// === Client capabilities ===

/**
 * Converts these v2 client auth capabilities to their v1 equivalent.
 *
 * @throws ProtocolConversionException if [AuthCapabilities.terminal] is set — the v1
 * Kotlin model has no `terminal` field
 */
@UnstableApi
public fun AuthCapabilities.toV1(): V1AuthCapabilities {
    if (terminal != null) throw unrepresentableV2Field("AuthCapabilities", "terminal")
    return V1AuthCapabilities(_meta = _meta)
}

/**
 * Converts these v1 client auth capabilities to their v2 equivalent.
 *
 * This conversion is total: every v1 value has a v2 representation.
 */
@UnstableApi
public fun V1AuthCapabilities.toV2(): AuthCapabilities = AuthCapabilities(
    terminal = null,
    _meta = _meta,
)

/**
 * Converts these v2 client capabilities to their v1 equivalent.
 *
 * The v1-only fields are filled with their "unsupported" values: `fs` is left unset,
 * `terminal` is `false` and `plan` is left unset. v2 makes `plan_update` baseline, so a v1
 * agent reading this will fall back to the older `plan` session update type.
 *
 * @throws ProtocolConversionException if a nested capability has no v1 representation
 */
@UnstableApi
public fun ClientCapabilities.toV1(): V1ClientCapabilities = V1ClientCapabilities(
    fs = null,
    terminal = false,
    planCapabilities = null,
    auth = auth?.toV1(),
    nes = nes,
    positionEncodings = positionEncodings.takeIf { it.isNotEmpty() },
    elicitation = elicitation,
    _meta = _meta,
)

/**
 * Converts these v1 client capabilities to their v2 equivalent.
 *
 * @throws ProtocolConversionException if `fs`, `terminal` or `plan` advertise support —
 * the `fs` and `terminal` method families were removed in v2, and `plan_update` became
 * baseline, so none of the three has anywhere to go
 */
@UnstableApi
public fun V1ClientCapabilities.toV2(): ClientCapabilities {
    if (fs != null && fs != FileSystemCapability()) throw unrepresentableV1Field("ClientCapabilities", "fs")
    if (terminal) throw unrepresentableV1Field("ClientCapabilities", "terminal")
    if (planCapabilities != null) throw unrepresentableV1Field("ClientCapabilities", "plan")
    return ClientCapabilities(
        auth = auth?.toV2(),
        elicitation = elicitation,
        nes = nes,
        positionEncodings = positionEncodings.orEmpty(),
        _meta = _meta,
    )
}
