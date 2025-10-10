plugins {
    id("acp.multiplatform")
}

kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation(project(":acp-ktor"))
                implementation(project(":acp-ktor-client"))
                implementation(project(":acp-ktor-server"))
                implementation(kotlin("test"))
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.client.core)
            }
        }
    }
}
