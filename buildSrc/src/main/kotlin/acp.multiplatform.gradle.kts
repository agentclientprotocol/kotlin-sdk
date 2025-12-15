@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// Generation library versions
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
    jvm {
        compilerOptions.jvmTarget = JvmTarget.JVM_1_8
    }
    js { nodejs() }
    wasmJs { nodejs() }
    // Future multiplatform targets can be added here without changing the code
//     linuxX64(); macosX64(); mingwX64()

    explicitApi = ExplicitApiMode.Strict
    jvmToolchain(21)

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLibVersion)
        }
    }
}