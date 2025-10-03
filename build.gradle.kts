plugins {
    id("acp.multiplatform") apply false
    id("acp.publishing") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

private val buildNumber: String? = System.getenv("GITHUB_RUN_NUMBER")

private val baseVersion = "0.1"

allprojects {
    group = "com.agentclientprotocol"
    version = when (buildNumber) {
        "-1" -> baseVersion // -1 as a buildNumber means that we are releasing this version
        null -> "$baseVersion-SNAPSHOT"
        else -> "$baseVersion-dev$buildNumber"
    }
}