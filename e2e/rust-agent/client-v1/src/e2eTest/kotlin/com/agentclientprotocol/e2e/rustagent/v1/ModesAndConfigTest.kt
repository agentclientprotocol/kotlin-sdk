package com.agentclientprotocol.e2e.rustagent.v1

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.e2e.rustagent.TestyAgent
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class ModesAndConfigTest {
    companion object {
        @JvmField
        @RegisterExtension
        val agent = TestyAgent(1)
    }

    @TempDir
    lateinit var cwd: Path

    private fun List<SessionConfigOption>.verbosity(): SessionConfigOption.Select =
        assertIs<SessionConfigOption.Select>(single { it.id == SessionConfigId("verbosity") })

    @Test
    fun modesAreChatAndPlan() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                assertTrue(session.modesSupported)
                assertEquals(listOf(SessionModeId("chat"), SessionModeId("plan")), session.availableModes.map { it.id })
                assertEquals(SessionModeId("chat"), session.currentMode.value)
            }
        }
    }

    /*
     * `currentMode` follows the agent's `current_mode_update`, and testy reports its current mode only from the
     * `session_updates` scenario, so the change is observed through that scenario.
     */
    @Test
    fun setModeUpdatesCurrentMode() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                session.setMode(SessionModeId("plan"))
                val turn = session.promptTurn(scenario("session_updates"))

                val modeUpdate = turn.updates.filterIsInstance<SessionUpdate.CurrentModeUpdate>().single()
                assertEquals(SessionModeId("plan"), modeUpdate.currentModeId)
                assertEquals(SessionModeId("plan"), session.currentMode.value)
            }
        }
    }

    @Test
    fun configOptionVerbosityHasThreeValues() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                assertTrue(session.configOptionsSupported)
                val verbosity = session.configOptions.value.verbosity()
                val options = assertIs<SessionConfigSelectOptions.Flat>(verbosity.options).options
                assertEquals(listOf("brief", "normal", "verbose"), options.map { it.value.value })
                assertEquals(SessionConfigValueId("normal"), verbosity.currentValue)
            }
        }
    }

    @Test
    fun setConfigOptionUpdatesValue() = runBlocking {
        withTimeout(30.seconds) {
            agent.client { client ->
                val session = client.newSession(cwd)

                val response = session.setConfigOption(SessionConfigId("verbosity"), SessionConfigOptionValue.of("verbose"))

                assertEquals(SessionConfigValueId("verbose"), response.configOptions.verbosity().currentValue)
                assertEquals(SessionConfigValueId("verbose"), session.configOptions.value.verbosity().currentValue)
            }
        }
    }
}
