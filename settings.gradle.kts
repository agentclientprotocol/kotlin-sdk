rootProject.name = "acp-kotlin-sdk"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":acp-model")
include(":acp")
include(":acp-ktor")
include(":acp-ktor-client")
include(":acp-ktor-server")
include(":acp-ktor-test")

// Include sample projects
include(":samples:kotlin-acp-client-sample")
