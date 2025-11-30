plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("application")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    mainClass.set("org.datamancy.stackdiscovery.MainKt")
}

kotlin {
    jvmToolchain(21)
}
