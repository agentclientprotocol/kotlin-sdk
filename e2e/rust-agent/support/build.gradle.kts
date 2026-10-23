plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":acp"))
    api(libs.junit.jupiter.api)
    api(libs.kotlinx.coroutines.core)
    // The SDK logs through kotlin-logging, which needs an SLF4J backend at runtime.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test-junit5"))
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
        }
    }
}

kotlin {
    jvmToolchain(21)
}
