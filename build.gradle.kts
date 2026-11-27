plugins {
    base
    id("acp.multiplatform") apply false
    id("acp.publishing") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

private val buildNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
private val isReleasePublication = System.getenv("RELEASE_PUBLICATION")?.toBoolean() ?: false

private val baseVersion = "0.34.0"

allprojects {
    group = "com.agentclientprotocol"
    version = when {
        isReleasePublication -> baseVersion
        buildNumber == null -> "$baseVersion-SNAPSHOT"
        else -> "$baseVersion-dev-$buildNumber"
    }
}

/*
 * `base` gives the root project a `check` task, which also runs the tests of the build-logic included build.
 */
tasks.check {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
