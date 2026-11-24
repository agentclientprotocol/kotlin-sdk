package com.agentclientprotocol.samples.subagents.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A stand-in for a native agent that the sample's agent wraps (scenario 10, see `NativeRelayedSubagentSession`). It
 * uses no SDK type: it reports all its workers on one flat event stream.
 */
internal object FakeNativeAgent {
    /**
     * What the native agent reports for one request: a planner, and a notes writer that the planner starts.
     */
    fun events(): Flow<NativeEvent> = flowOf(
        NativeEvent.Spawned("planner", parent = null, title = "Release planner"),
        NativeEvent.Output("planner", "Splitting the release into steps"),
        NativeEvent.Spawned("notes", parent = "planner", title = "Release notes writer"),
        NativeEvent.Output("notes", "Collected 12 changes"),
        NativeEvent.Finished("notes"),
        NativeEvent.Output("planner", "The plan is ready"),
        NativeEvent.Finished("planner"),
    )
}

/**
 * An event of the native agent's flat stream: each one names its worker.
 */
internal sealed class NativeEvent {
    abstract val worker: String

    class Spawned(override val worker: String, val parent: String?, val title: String) : NativeEvent()
    class Output(override val worker: String, val text: String) : NativeEvent()
    class Finished(override val worker: String) : NativeEvent()
}
