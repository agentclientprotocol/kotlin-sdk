import java.net.URI

plugins {
    kotlin("jvm")
//    id("com.jetbrains.jcp.api.codegen.gradle.ktor-server-stubs")
}

//repositories {
//    maven {
//        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-private-dependencies")
//        credentials {
//            username = env["JB_SPACE_USERNAME"] ?: System.getenv("JB_SPACE_USERNAME") ?: findProperty("spaceUsername") as String
//            password = env["JB_SPACE_TOKEN"] ?: System.getenv("JB_SPACE_TOKEN") ?: findProperty("spaceToken") as String
//        }
//    }
//}

// Configuration for the schema version
val schemaVersion by extra("v0.6.2")
val schemaReleaseUrl = "https://github.com/agentclientprotocol/agent-client-protocol/releases/download/$schemaVersion/schema.json"

// Create a task to download the schema.json
val downloadSchema by tasks.registering {
    group = "schema"
    description = "Download schema.json from GitHub release"

    val outputDir = layout.buildDirectory.dir("schema")
    val outputFile = outputDir.map { it.file("schema.json") }

    outputs.file(outputFile)
    outputs.upToDateWhen { outputFile.get().asFile.exists() }

    doLast {
        val dir = outputFile.get().asFile.parentFile
        dir.mkdirs()

        println("Downloading schema from: $schemaReleaseUrl")
        URI(schemaReleaseUrl).toURL().openStream().use { input ->
            outputFile.get().asFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        println("Schema downloaded to: ${outputFile.get().asFile}")
    }
}

// Create a task to convert JSON schema to OpenAPI YAML
val convertToOpenApi by tasks.registering(JavaExec::class) {
    group = "schema"
    description = "Convert schema.json to openapi.yaml"

//    dependsOn(downloadSchema)

    val inputFile = layout.buildDirectory.file("schema/schema.json")
    val outputFile = layout.buildDirectory.file("schema/openapi.yaml")

    inputs.file(inputFile)
    outputs.file(outputFile)

    mainClass.set("com.acp.schema.SchemaConverterKt")
    classpath = sourceSets["main"].runtimeClasspath

    args(
        inputFile.get().asFile.absolutePath,
        outputFile.get().asFile.absolutePath,
        schemaVersion
    )
}

// Make build depend on schema generation
tasks.named("build") {
    dependsOn(convertToOpenApi)
}

// Ensure converter is compiled before running convertToOpenApi
tasks.named("convertToOpenApi") {
    dependsOn(tasks.named("classes"))
}

//ktorApiSeverConfig {
//    inputSpec = layout.buildDirectory.file("schema/openapi.yaml").get().asFile.absolutePath
//    groupId = "com.jetbrains.jcp.orgservice.test"
//    artifactId = "org-internal-test-client"
//    packagePrefix = "com.jetbrains.jcp.orgservice.internal_test_client.generated"
//}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
}
