plugins {
    id("acp.multiplatform") apply false
    id("acp.publishing") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

private val buildNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
private val isReleasePublication = System.getenv("RELEASE_PUBLICATION")?.toBoolean() ?: false

private val baseVersion = "0.5.0"

allprojects {
    group = "com.agentclientprotocol"
    version = when {
        isReleasePublication -> baseVersion
        buildNumber == null -> "$baseVersion-SNAPSHOT"
        else -> "$baseVersion-dev-$buildNumber"
    }
}