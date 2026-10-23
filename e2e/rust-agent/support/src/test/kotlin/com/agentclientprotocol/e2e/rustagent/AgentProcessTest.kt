package com.agentclientprotocol.e2e.rustagent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class AgentProcessTest {
    @TempDir
    lateinit var tempDir: Path

    private fun script(name: String, body: String): Path {
        val file = Files.writeString(tempDir.resolve(name), "#!/bin/bash\n$body\n")
        file.toFile().setExecutable(true)
        return file.toAbsolutePath()
    }

    @Test
    fun runSetupReturnsPathFromScript() {
        val received = tempDir.resolve("received")
        val setup = script("setup.sh", "echo noise >&2\necho \"\$1\" > '$received'\necho /bin/cat")

        val binary = runSetup(setup, 1)

        assertEquals(Path.of("/bin/cat"), binary)
        assertEquals("1", Files.readString(received).trim())
    }

    @Test
    fun missingSetupScriptPropertyFailsDescriptively() {
        val previous = System.getProperty(SETUP_SCRIPT_PROPERTY)
        System.clearProperty(SETUP_SCRIPT_PROPERTY)
        try {
            val failure = assertFailsWith<SetupFailedException> { setupScriptFromProperty() }

            assertTrue(SETUP_SCRIPT_PROPERTY in failure.message.orEmpty(), failure.message)
        } finally {
            if (previous != null) System.setProperty(SETUP_SCRIPT_PROPERTY, previous)
        }
    }

    @Test
    fun teardownKillsAgentThatIgnoresStdinClose() {
        val pidFile = tempDir.resolve("agent.pid")
        val agent = script("agent.sh", "echo \$\$ > '$pidFile'\nexec sleep 600")
        val stderrLog = tempDir.resolve("logs/agent.log")

        val elapsed = measureTime {
            runBlocking { withAgentProcess(agent, stderrLog) { } }
        }

        assertTrue(elapsed < 10.seconds, "Teardown took $elapsed")
        val pid = Files.readString(pidFile).trim().toLong()
        val handle = ProcessHandle.of(pid)
        // A killed child may linger briefly as a zombie until the JVM reaps it.
        handle.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(handle.map { it.isAlive }.orElse(false), "Agent process $pid is still alive")
        assertTrue(Files.exists(stderrLog.parent))
    }
}
