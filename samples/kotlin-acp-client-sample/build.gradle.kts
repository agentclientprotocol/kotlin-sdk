plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":acp"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.io.core)
    implementation(libs.logback.classic)
}

/*
 * The tests run scripted samples end to end, so that a change in the SDK that breaks a sample fails `check`.
 */
testing {
    suites {
        @Suppress("UnstableApiUsage")
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())

            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
        }
    }
}

application {
    mainClass.set(
        providers.gradleProperty("mainClass")
            .orElse("com.agentclientprotocol.samples.simple.SimpleAgentAppKt")
    )
}

kotlin {
    jvmToolchain(21)
}

/*
 * Each sample has one run task. The plain `run` task starts the simple agent by default; override it with
 * `-PmainClass=<fully qualified main class>`.
 */
fun registerSampleTask(name: String, sampleDescription: String, sampleMainClass: String) =
    tasks.register<JavaExec>(name) {
        group = "samples"
        description = sampleDescription

        // Interactive samples read the console, so wire stdin and stdout to the Gradle process.
        standardInput = System.`in`
        standardOutput = System.out

        mainClass.set(sampleMainClass)
        classpath = sourceSets.main.get().runtimeClasspath
    }

registerSampleTask(
    "runSimpleAgent",
    "Runs the terminal client against the simple in-process agent (ACP v1).",
    "com.agentclientprotocol.samples.simple.SimpleAgentAppKt"
)
registerSampleTask(
    "runGeminiClient",
    "Runs the interactive terminal client against an external Gemini ACP agent (requires the gemini CLI).",
    "com.agentclientprotocol.samples.gemini.GeminiClientAppKt"
)
registerSampleTask(
    "runV2SimpleAgent",
    "Runs a v2 client and a v2 agent directly against each other.",
    "com.agentclientprotocol.samples.v2.V2SimpleAgentAppKt"
)
registerSampleTask(
    "runV2Negotiation",
    "Runs ClientNegotiator, then a v2 conversation with the selected client.",
    "com.agentclientprotocol.samples.v2.V2NegotiationAppKt"
)
registerSampleTask(
    "runAuthStatus",
    "Queries the unstable auth/status method over v1 and v2 without a session.",
    "com.agentclientprotocol.samples.auth.AuthStatusAppKt"
)
registerSampleTask(
    "runSubagents",
    "Runs a scripted agent that delegates to subagents, and a client that shows, cancels and closes them (ACP v1).",
    "com.agentclientprotocol.samples.subagents.SubagentsAppKt"
).configure {
    systemProperty("logback.configurationFile", "logback-subagents.xml")
}
