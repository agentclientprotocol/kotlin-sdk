plugins {
    id("acp.multiplatform") apply false
    id("acp.publishing") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

allprojects {
    group = "com.agentclientprotocol"
    version = "0.1.15"
}