import java.util.Properties

plugins {
    `maven-publish`
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

    publications.withType<MavenPublication> {
        groupId = project.group.toString()
        version = project.version.toString()
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

//signing {
//    isRequired = false
//    useGpgCmd()
//    sign(publishing.publications)
//}