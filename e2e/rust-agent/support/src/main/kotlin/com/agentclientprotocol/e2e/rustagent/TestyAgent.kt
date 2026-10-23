package com.agentclientprotocol.e2e.rustagent

import com.agentclientprotocol.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JUnit 5 extension that builds the Rust `testy` agent once per test class and starts a fresh agent
 * process for every [run] call.
 *
 * Register it in a companion object: `@JvmField @RegisterExtension val agent = TestyAgent(1)`.
 * Each test's agent stderr is written to `<TestClass>/<testMethod>.log` under the directory named by
 * [AGENT_LOG_DIR_PROPERTY], which defaults to `build/e2e-logs`.
 *
 * @param protocolVersion ACP protocol version the agent is built for, passed to the setup script
 */
public class TestyAgent(private val protocolVersion: Int) : BeforeAllCallback, BeforeEachCallback {
    private lateinit var binary: Path

    @Volatile
    private var stderrLog: Path? = null

    override fun beforeAll(context: ExtensionContext) {
        binary = runSetup(setupScriptFromProperty(), protocolVersion)
    }

    override fun beforeEach(context: ExtensionContext) {
        val logDir = Paths.get(System.getProperty(AGENT_LOG_DIR_PROPERTY) ?: "build/e2e-logs")
        stderrLog = logDir
            .resolve(context.requiredTestClass.simpleName)
            .resolve("${context.requiredTestMethod.name}.log")
    }

    /**
     * Runs [block] against a freshly started agent process that is stopped when the block ends.
     */
    public suspend fun <T> run(block: suspend CoroutineScope.(Protocol) -> T): T {
        val log = checkNotNull(stderrLog) { "TestyAgent is not registered as a JUnit extension or no test is running" }
        return withAgentProcess(binary, log, block)
    }
}
