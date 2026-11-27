package com.agentclientprotocol.samples.subagents

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.samples.subagents.agent.DelegatingAgentSupport
import com.agentclientprotocol.samples.subagents.client.SubagentsSessions
import com.agentclientprotocol.samples.subagents.client.runSubagentsClient
import com.agentclientprotocol.samples.support.createInMemoryTransportPair
import kotlinx.coroutines.coroutineScope

/**
 * Runs the subagents sample (ACP v1): an agent that delegates work to subagents, and a client that shows them, in one
 * process. It prints a transcript of what each side sees.
 *
 * The client's script:
 * 1. initializes the connection with the `subagents` capability and opens a session;
 * 2. sends a prompt that the agent delegates to three subagents: the reviewer completes, the user cancels the cleaner
 *    while its permission request is pending, and the tester fails after it started a grandchild;
 * 3. sends a prompt that the agent relays to a native agent, whose workers become nested subagents;
 * 4. closes the session, which closes every subagent below it;
 * 5. loads the session again: the agent replays its history and its subagents, and grants cancel again to the
 *    reviewer, which the user then cancels;
 * 6. closes the session again.
 *
 * Each line names the side and the session it belongs to. The streams of different sessions are delivered
 * concurrently, so their lines may interleave differently from run to run; the lines of one session keep their order.
 *
 * Start reading the agent at `agent/DelegatingAgentSession.kt` and the client at `client/SubagentsClient.kt`.
 */
suspend fun main() {
    runSubagentsSample(Transcript { line -> println(line.render()) })
}

/**
 * Connects the agent and the client of the sample in this process and runs the client's script.
 *
 * @param declareSubagents whether the client declares the `subagents` capability
 * @return the opened and the loaded root session, both closed
 */
suspend fun runSubagentsSample(
    transcript: Transcript,
    declareSubagents: Boolean = true,
): SubagentsSessions = coroutineScope {
    val transports = createInMemoryTransportPair()
    val agentProtocol = Protocol(this, transports.agent)
    Agent(agentProtocol, DelegatingAgentSupport(transcript))
    agentProtocol.start()

    try {
        runSubagentsClient(transports.client, transcript, declareSubagents)
    } finally {
        agentProtocol.close()
    }
}
