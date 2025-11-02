
rootProject.name = "acp-kotlin-sdk"


pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            val envFile = file(".env")
            val envProps = java.util.Properties()
            if (envFile.exists()) {
                envFile.inputStream().use { envProps.load(it) }
            }

            fun getEnvProperty(name: String): String {
                return envProps.getProperty(name)
                    ?: System.getenv(name)
                    ?: throw Exception("Space username is not defined" + envFile.absolutePath)
            }

            url = uri("https://packages.jetbrains.team/maven/p/jcp/github-mirror-public")
//            credentials {
//                username = getEnvProperty("SPACE_USERNAME")
//                password = getEnvProperty("SPACE_TOKEN")
//            }

        }
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
include(":acp-schema-generator")
include(":acp-schema-kmp")

// Include sample projects
include(":samples:kotlin-acp-client-sample")
include("acp-schema-kmp")