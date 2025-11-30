plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("application")
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
