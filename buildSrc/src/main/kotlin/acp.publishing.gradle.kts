import java.util.Properties

plugins {
    `maven-publish`
    id("com.vanniktech.maven.publish")
    signing
}


// Load .env file from project root
val envFile = rootProject.file(".env")
val envProps = Properties()
if (envFile.exists()) {
    envFile.inputStream().use { envProps.load(it) }
}

fun getEnvProperty(name: String): String {
    return envProps.getProperty(name)
        ?: project.findProperty(name) as String?
        ?: System.getenv(name)
        ?: ""
}

val spaceUsername: String = getEnvProperty("SPACE_USERNAME")
val spacePassword: String = getEnvProperty("SPACE_PASSWORD")


mavenPublishing {
    pom {
        name = project.name
        description = "Kotlin implementation of Agent Client Protocol (ACP)"
        url = "https://github.com/JetBrains/acp-kotlin-sdk" // TODO: change the repo once it is moved

        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "JetBrains"
                name = "JetBrains Team"
                organization = "JetBrains"
                organizationUrl = "https://www.jetbrains.com"
            }
        }

        scm {
            url = "https://github.com/JetBrains/acp-kotlin-sdk" // TODO: change the repo once it is moved
        }
    }
}
publishing {
    repositories {
        maven {
            name = "Space"
            url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-private-dependencies")
            credentials {
                username = spaceUsername
                password = spacePassword
            }
        }
    }
}
