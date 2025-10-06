plugins {
    id("acp.multiplatform")
    id("acp.publishing")
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":acp"))
                api(project(":acp-model"))
                api(libs.ktor.utils)
                api(libs.ktor.client.websockets)
            }
        }
    }
}
