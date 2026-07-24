import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("acp.publishing")
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val generateLibVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated-sources/libVersion")
    outputs.dir(outputDir)
    val packageName = project.name.replace("-", "")
    val versionString = project.version

    doLast {
        val sourceFile = outputDir.get().file("com/agentclientprotocol/$packageName/LibVersion.kt").asFile
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package com.agentclientprotocol.$packageName

            public const val LIB_VERSION: String = "$versionString"

            """.trimIndent()
        )
    }
}

kotlin {
    explicitApi = ExplicitApiMode.Strict
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }

    sourceSets {
        main {
            kotlin.srcDir(generateLibVersion)
        }
    }
}

dependencies {
    api(project(":acp"))
    api(libs.javax.websocket.api)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}
