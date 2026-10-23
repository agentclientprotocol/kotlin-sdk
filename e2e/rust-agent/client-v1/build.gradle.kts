plugins {
    kotlin("jvm")
}

testing {
    suites {
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit.get())

            dependencies {
                implementation(project(":e2e:rust-agent:support"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                // The SDK logs through kotlin-logging, which needs an SLF4J backend at runtime.
                runtimeOnly(libs.logback.classic)
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

kotlin {
    jvmToolchain(21)
}
