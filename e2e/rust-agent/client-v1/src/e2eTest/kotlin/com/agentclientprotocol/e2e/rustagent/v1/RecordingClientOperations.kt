package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.GlobalElicitationHandler
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.ElicitationOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationSchema
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.MultiSelectItems
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StringFormat
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * One client method the agent invoked, with the arguments a test asserts on.
 */
data class RecordedCall(val method: String, val args: List<String> = emptyList())

/**
 * Client operations that answer every agent request for real and record each call in order.
 *
 * Permission requests pick the `allow_once` option, the fs methods read and write files, terminals run the real
 * process, and form elicitations are accepted with values that satisfy the requested schema.
 */
@OptIn(UnstableApi::class)
class RecordingClientOperations : ClientSessionOperations, FileSystemOperations, TerminalOperations, ElicitationOperations {
    private val recorded = CopyOnWriteArrayList<RecordedCall>()
    private val notifications = CopyOnWriteArrayList<SessionUpdate>()
    private val terminals = ConcurrentHashMap<String, RunningTerminal>()
    private val nextTerminalId = AtomicInteger(1)

    /**
     * Every recorded call, in the order the agent made them.
     */
    val calls: List<RecordedCall> get() = recorded.toList()

    /**
     * Session updates delivered outside a prompt turn.
     */
    val notified: List<SessionUpdate> get() = notifications.toList()

    private fun record(method: String, vararg args: Any?) {
        recorded += RecordedCall(method, args.map { it.toString() })
    }

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        record("requestPermissions", toolCall.toolCallId.value)
        val allowOnce = permissions.first { it.kind == PermissionOptionKind.ALLOW_ONCE }
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(allowOnce.optionId))
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        notifications += notification
    }

    override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?): ReadTextFileResponse {
        record("fsReadTextFile", path, line, limit)
        val lines = Path(path).readLines()
        val from = ((line ?: 1u).toInt() - 1).coerceIn(0, lines.size)
        val selected = lines.drop(from).let { if (limit != null) it.take(limit.toInt()) else it }
        return ReadTextFileResponse(selected.joinToString("\n", postfix = if (selected.isEmpty()) "" else "\n"))
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse {
        record("fsWriteTextFile", path, content)
        Path(path).writeText(content)
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        record("terminalCreate", command, args)
        val builder = ProcessBuilder(listOf(command) + args).redirectErrorStream(true)
        if (cwd != null) builder.directory(File(cwd))
        env.forEach { builder.environment()[it.name] = it.value }

        val terminalId = "terminal-${nextTerminalId.getAndIncrement()}"
        terminals[terminalId] = RunningTerminal(builder.start(), outputByteLimit)
        return CreateTerminalResponse(terminalId)
    }

    override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?): TerminalOutputResponse {
        record("terminalOutput", terminalId)
        val terminal = terminal(terminalId)
        val exitStatus = if (terminal.process.isAlive) null else TerminalExitStatus(terminal.process.exitValue().toUInt())
        val (output, truncated) = terminal.output()
        return TerminalOutputResponse(output, truncated, exitStatus)
    }

    override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?): WaitForTerminalExitResponse {
        record("terminalWaitForExit", terminalId)
        val process = terminal(terminalId).process
        val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
        return WaitForTerminalExitResponse(exitCode.toUInt())
    }

    override suspend fun terminalKill(terminalId: String, _meta: JsonElement?): KillTerminalCommandResponse {
        record("terminalKill", terminalId)
        terminal(terminalId).process.destroy()
        return KillTerminalCommandResponse()
    }

    override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?): ReleaseTerminalResponse {
        record("terminalRelease", terminalId)
        terminals.remove(terminalId)?.process?.destroy()
        return ReleaseTerminalResponse()
    }

    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
        record("createElicitation", request.message)
        return acceptElicitation(request)
    }

    override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
        record("completeElicitation", notification.elicitationId.value)
    }

    private fun terminal(terminalId: String): RunningTerminal =
        terminals[terminalId] ?: error("Unknown terminal $terminalId")

    /**
     * A started terminal process whose combined output is collected as it arrives.
     */
    private class RunningTerminal(val process: Process, private val byteLimit: ULong?) {
        private val buffer = StringBuffer()
        private val reader = Thread { process.inputStream.bufferedReader().forEachLine { buffer.append(it).append('\n') } }
            .apply { isDaemon = true; start() }

        fun output(): Pair<String, Boolean> {
            if (!process.isAlive) reader.join()
            val text = buffer.toString()
            val limit = byteLimit?.toLong() ?: return text to false
            val bytes = text.toByteArray()
            if (bytes.size <= limit) return text to false
            return bytes.copyOfRange((bytes.size - limit).toInt(), bytes.size).decodeToString() to true
        }
    }
}

/**
 * Answers the elicitations that are not scoped to a session, like [RecordingClientOperations] does, and records them.
 */
@OptIn(UnstableApi::class)
class RecordingElicitationHandler : GlobalElicitationHandler {
    private val recorded = CopyOnWriteArrayList<RecordedCall>()

    /**
     * Every recorded elicitation, in arrival order.
     */
    val calls: List<RecordedCall> get() = recorded.toList()

    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
        recorded += RecordedCall("createElicitation", listOf(request.message))
        return acceptElicitation(request)
    }
}

/**
 * Accepts [request]: a form gets an answer that satisfies its schema, and a URL needs no content.
 */
@OptIn(UnstableApi::class)
private fun acceptElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
    val action = when (val mode = request.mode) {
        is ElicitationMode.Form -> ElicitationAction.Accept(answerFor(mode.requestedSchema))
        is ElicitationMode.Url -> ElicitationAction.Accept()
    }
    return CreateElicitationResponse(action)
}

/**
 * A fixed answer that satisfies [schema]: every property gets a value valid for its type, format, and bounds.
 */
@OptIn(UnstableApi::class)
private fun answerFor(schema: ElicitationSchema): Map<String, ElicitationContentValue> =
    schema.properties.mapValues { (_, property) ->
        when (property) {
            is ElicitationPropertySchema.StringProperty -> ElicitationContentValue.StringValue(
                property.enumValues?.first()
                    ?: property.oneOf?.first()?.value
                    ?: when (property.format) {
                        StringFormat.EMAIL -> "e2e@example.com"
                        StringFormat.URI -> "https://example.com/e2e"
                        StringFormat.DATE -> "2026-01-01"
                        StringFormat.DATE_TIME -> "2026-01-01T00:00:00Z"
                        null -> "e2e"
                    }
            )
            is ElicitationPropertySchema.NumberProperty -> ElicitationContentValue.NumberValue(property.minimum ?: 0.0)
            is ElicitationPropertySchema.IntegerProperty -> ElicitationContentValue.IntegerValue(property.minimum ?: 0)
            is ElicitationPropertySchema.BooleanProperty -> ElicitationContentValue.BooleanValue(true)
            is ElicitationPropertySchema.ArrayProperty -> ElicitationContentValue.StringArrayValue(
                when (val items = property.items) {
                    is MultiSelectItems.Untitled -> listOf(items.items.values.first())
                    is MultiSelectItems.Titled -> listOf(items.items.options.first().value)
                }
            )
        }
    }
