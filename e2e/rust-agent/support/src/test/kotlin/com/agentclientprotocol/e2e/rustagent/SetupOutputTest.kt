package com.agentclientprotocol.e2e.rustagent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupOutputTest {
    @TempDir
    lateinit var tempDir: Path

    private fun executable(name: String = "testy"): Path {
        val file = Files.createFile(tempDir.resolve(name))
        file.toFile().setExecutable(true)
        return file.toAbsolutePath().normalize()
    }

    private fun assertSetupFails(exitCode: Int, stdout: String, stderr: String = "", vararg expected: String) {
        val failure = assertFailsWith<SetupFailedException> { agentBinaryFromSetupOutput(exitCode, stdout, stderr) }
        val message = failure.message.orEmpty()
        assertTrue("expected a valid path to the built testy binary" in message, message)
        for (part in expected) {
            assertTrue(part in message, "Expected '$part' in: $message")
        }
    }

    @Test
    fun nonZeroExitFails() {
        val stderr = (1..30).joinToString("\n") { "line $it" } + "\nerror: could not compile"

        val failure = assertFailsWith<SetupFailedException> { agentBinaryFromSetupOutput(101, "", stderr) }

        val message = failure.message.orEmpty()
        assertTrue("expected a valid path to the built testy binary" in message, message)
        assertTrue("exit code 101" in message, message)
        assertTrue("error: could not compile" in message, message)
        assertTrue("line 12\n" in message, message)
        assertTrue("line 11\n" !in message, message)
    }

    @Test
    fun emptyStdoutFails() {
        assertSetupFails(0, "", "", "stdout was empty")
    }

    @Test
    fun blankLastLinesAreIgnored() {
        val exe = executable()

        assertEquals(exe, agentBinaryFromSetupOutput(0, "$exe\n\n  \n", ""))
    }

    @Test
    fun relativePathFails() {
        assertSetupFails(0, "target/debug/testy", "", "target/debug/testy", "is not absolute")
    }

    @Test
    fun missingFileFails() {
        val missing = tempDir.resolve("missing").toAbsolutePath().toString()

        assertSetupFails(0, "$missing\n", "", missing, "does not exist")
    }

    @Test
    fun nonExecutableFileFails() {
        val file = Files.createFile(tempDir.resolve("plain")).toAbsolutePath().toString()

        assertSetupFails(0, "$file\n", "", file, "is not executable")
    }

    @Test
    fun buildNoiseThenPathReturnsPath() {
        val exe = executable()

        assertEquals(exe, agentBinaryFromSetupOutput(0, "Compiling foo\nFinished\n$exe\n", ""))
    }
}
