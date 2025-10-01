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

val artefactName = "acp-model"
publishing {
    publications {
        withType<MavenPublication> {
            artifactId = when (name) {
                "kotlinMultiplatform" -> artefactName
                else -> "$artefactName-$name"
            }
        }
    }
}