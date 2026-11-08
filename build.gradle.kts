plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "com.reviewcopilot"
version = "0.1.0"

repositories {
    // Google's mirror of Maven Central first: Maven Central has been slow or timing out
    // for some large artifacts (e.g. the Kotlin compiler jar) on CI runners.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val ktorVersion = "3.3.0"

dependencies {
    // HTTP server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // HTTP client (calls the LLM / embedding APIs)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Logs go to stderr, which keeps stdout clean for the MCP stdio transport
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.reviewcopilot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
