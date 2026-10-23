package com.agentclientprotocol.e2e.rustagent

import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * System property holding the path of the script that builds the agent and prints its binary path.
 */
public const val SETUP_SCRIPT_PROPERTY: String = "acp.e2e.rustAgent.setupScript"

/**
 * System property holding the directory for per-test agent stderr logs.
 */
public const val AGENT_LOG_DIR_PROPERTY: String = "acp.e2e.rustAgent.logDir"

private val SETUP_TIMEOUT = 30.minutes
private const val AGENT_EXIT_TIMEOUT_SECONDS = 5L

/**
 * Runs `bash <setupScript> <protocolVersion>` and returns the agent binary it reports.
 *
 * The script's stderr is forwarded to [System.err] as it arrives. The timeout is generous because
 * a cold cargo build is slow.
 *
 * @throws SetupFailedException if the script fails, times out, or reports no usable binary
 */
public fun runSetup(setupScript: Path, protocolVersion: Int): Path {
    val process = ProcessBuilder("bash", setupScript.toString(), protocolVersion.toString())
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .start()

    // Both streams must be drained concurrently or a chatty build can block on a full pipe.
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val readers = listOf(
        Thread { process.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') } },
        Thread {
            process.errorStream.bufferedReader().forEachLine {
                System.err.println(it)
                stderr.append(it).append('\n')
            }
        },
    )
    readers.forEach { it.isDaemon = true; it.start() }

    if (!process.waitFor(SETUP_TIMEOUT.inWholeMinutes, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw SetupFailedException("setup_sut.sh did not finish within $SETUP_TIMEOUT")
    }
    readers.forEach { it.join() }

    return agentBinaryFromSetupOutput(process.exitValue(), stdout.toString(), stderr.toString())
}

/**
 * Starts the agent [binary], runs [block] with a started [Protocol] connected to it over STDIO,
 * and tears the process down afterwards.
 *
 * The agent's stderr goes to [stderrLog], whose parent directories are created. On exit the protocol
 * is closed and the process gets a few seconds to stop before it is killed.
 */
public suspend fun <T> withAgentProcess(
    binary: Path,
    stderrLog: Path,
    block: suspend CoroutineScope.(Protocol) -> T,
): T {
    stderrLog.parent?.let { Files.createDirectories(it) }
    val process = ProcessBuilder(binary.toString())
        .redirectError(stderrLog.toFile())
        .start()

    try {
        return coroutineScope {
            val transport = StdioTransport(
                parentScope = this,
                ioDispatcher = Dispatchers.IO,
                input = flow {
                    val reader = process.inputStream.bufferedReader()
                    while (true) emit(reader.readLine() ?: break)
                },
                output = { line ->
                    process.outputStream.write("$line\n".toByteArray())
                    process.outputStream.flush()
                },
            )
            val protocol = Protocol(this, transport)

            try {
                protocol.start()
                block(protocol)
            } finally {
                // The protocol and transport are children of this scope, which cannot complete until they close.
                protocol.close()
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            // End of stdin is the agent's cue to exit.
            runCatching { process.outputStream.close() }
            if (!process.waitFor(AGENT_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}
