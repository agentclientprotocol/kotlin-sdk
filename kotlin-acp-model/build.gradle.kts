plugins {
    id("acp.multiplatform")
    id("acp.publishing")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.collections.immutable)
            }
        }
    }
}