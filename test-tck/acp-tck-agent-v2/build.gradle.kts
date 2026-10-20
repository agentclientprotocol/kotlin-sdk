plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":acp"))
    // Logs go to stderr: stdout carries the ACP stream.
    runtimeOnly(libs.logback.classic)
}

application {
    applicationName = "acp-tck-agent-v2"
    mainClass.set("com.agentclientprotocol.tck.v2.MainKt")
}

kotlin {
    jvmToolchain(21)
}
