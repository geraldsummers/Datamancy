plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")

    // Ktor server
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-jackson:3.0.3")

    // Jackson JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    // Libvirt Java bindings - Note: not in Maven Central, using JNA/ProcessBuilder approach instead
    // implementation("org.libvirt:libvirt:0.5.4")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    // SSH key generation (BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.datamancy.vmprov.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("vm-provisioner")
    archiveClassifier.set("")
    archiveVersion.set("")
    archiveFileName.set("vm-provisioner.jar")
}
