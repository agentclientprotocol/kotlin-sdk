plugins {
    id("acp.multiplatform") apply false
    id("acp.publishing") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

val buildNumber: String? = System.getenv("GITHUB_RUN_NUMBER")

allprojects {
    group = "com.agentclientprotocol"
    version = "1.1.${buildNumber ?: "EAP"}"
}