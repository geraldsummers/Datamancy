plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.datamancy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-html-builder:2.3.12")
    implementation("io.ktor:ktor-server-sse:2.3.12")

    // Ktor client for proxying
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("org.datamancy.controlpanel.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("control-panel")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
