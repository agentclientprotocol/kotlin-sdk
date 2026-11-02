/*
 * Shared setup for the e2e/rust-agent client modules: an e2eTest suite that drives the Rust SDK testy agent.
 */

plugins {
    kotlin("jvm")
}

// Type-safe catalog accessors are not generated for precompiled script plugins.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

testing {
    suites {
        @Suppress("UnstableApiUsage")
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)

            dependencies {
                implementation(project(":e2e:rust-agent:support"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                runtimeOnly(libs.findLibrary("logback-classic").get())
            }

            targets.all {
                testTask.configure {
                    // Property names match SETUP_SCRIPT_PROPERTY and AGENT_LOG_DIR_PROPERTY in :e2e:rust-agent:support.
                    systemProperty("acp.e2e.rustAgent.setupScript", rootProject.file("e2e/rust-agent/setup_sut.sh").absolutePath)
                    systemProperty("acp.e2e.rustAgent.logDir", layout.buildDirectory.dir("e2e-logs").get().asFile.absolutePath)

                    // Results depend on the externally built agent, so an explicit run always executes.
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                }
            }
        }
    }
}

// Compiling the suite catches SDK API changes that break it; running it needs the external agent, so check never does.
tasks.named("check") {
    dependsOn(tasks.named("compileE2eTestKotlin"))
}

kotlin {
    jvmToolchain(21)
}
