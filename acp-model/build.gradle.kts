plugins {
    id("acp.multiplatform")
    id("acp.publishing")
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

kotlin {
    js {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.collections.immutable)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

    }
}
