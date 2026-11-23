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
    iosArm64 {}
    iosSimulatorArm64 {}
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

/*
 * Keep `internal` packages out of the public API. The dump written by the binary compatibility validator is the
 * compiler's own view of the public API, so it is the source of truth for this check.
 */
pluginManager.withPlugin("org.jetbrains.kotlinx.binary-compatibility-validator") {
    val checkInternalPackagesNotPublic by tasks.registering(CheckInternalPackagesNotPublic::class) {
        group = "verification"
        description = "Fails if an API dump lists a public declaration in an internal package."
        apiDumps.from(fileTree(layout.projectDirectory.dir("api")) { include("*.api") })
        marker.set(layout.buildDirectory.file("internal-packages-check/ok"))
    }
    tasks.check { dependsOn(checkInternalPackagesNotPublic) }
}
