package com.agentclientprotocol.e2e.rustagent

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

private const val STDERR_TAIL_LINES = 20

private const val SETUP_FAILURE_PREFIX =
    "setup_sut.sh: expected a valid path to the built testy binary as the last line of stdout, but "

/**
 * Thrown when the setup script fails or does not produce a usable agent binary.
 */
public class SetupFailedException(message: String) : IllegalStateException(message)

/**
 * Extracts the agent binary path from the outcome of `setup_sut.sh`.
 *
 * Build tools print progress before the path, so only the last non-blank stdout line is used.
 *
 * @param exitCode exit code of the setup script
 * @param stdout captured standard output of the setup script
 * @param stderr captured standard error of the setup script, quoted in the failure message on a non-zero exit
 * @return the absolute, normalized path of an existing executable file
 * @throws SetupFailedException if the script failed or the last line is not such a path
 */
public fun agentBinaryFromSetupOutput(exitCode: Int, stdout: String, stderr: String): Path {
    if (exitCode != 0) {
        val tail = stderr.lines().takeLast(STDERR_TAIL_LINES).joinToString("\n")
        throw SetupFailedException("${SETUP_FAILURE_PREFIX}the script exited with exit code $exitCode. Last stderr lines:\n$tail")
    }

    val line = stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
        ?: throw SetupFailedException("${SETUP_FAILURE_PREFIX}stdout was empty")

    val path = try {
        Paths.get(line)
    } catch (e: InvalidPathException) {
        throw SetupFailedException("${SETUP_FAILURE_PREFIX}'$line' is not a valid path (${e.reason})")
    }

    if (!path.isAbsolute) throw SetupFailedException("${SETUP_FAILURE_PREFIX}'$line' is not absolute")
    if (!Files.exists(path)) throw SetupFailedException("${SETUP_FAILURE_PREFIX}'$line' does not exist")
    if (!Files.isExecutable(path) || Files.isDirectory(path)) {
        throw SetupFailedException("${SETUP_FAILURE_PREFIX}'$line' is not executable")
    }

    return path.normalize()
}
