plugins {
    id("acp.multiplatform")
    id("acp.publishing")
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":acp-ktor"))
                api(libs.ktor.server.core)
                api(libs.ktor.server.websockets)
            }
        }
    }
}
