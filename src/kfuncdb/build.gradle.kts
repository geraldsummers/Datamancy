plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // JUnit 5 API/engine for kotlin-test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.20")
    // SSH client for OpsSshPlugin
    implementation("com.hierynomus:sshj:0.37.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.example.MainKt")
}

kotlin {
    jvmToolchain(17)
}