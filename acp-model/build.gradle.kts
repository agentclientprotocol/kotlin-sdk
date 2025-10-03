plugins {
    id("acp.multiplatform")
    id("acp.publishing")
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
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
