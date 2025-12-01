plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
    id("application")
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    // Ktor client
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-cio:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    mainClass.set("org.datamancy.probe.Probe_OrchestratorKt")
}

kotlin {
    jvmToolchain(21)
}

// Configure Shadow to produce the runnable fat JAR we ship
val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("probe-orchestrator-kt.jar")
}

// Keep the plain jar minimal (no manual fattening) and only set manifest
tasks.withType<Jar> {
    manifest { attributes["Main-Class"] = "org.datamancy.probe.Probe_OrchestratorKt" }
}

// Ensure application plugin artifacts use the shadow jar and run after it
tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(shadowJarTask)
    // Use the shadow jar as the only classpath entry for the start script
    classpath = files(shadowJarTask.get().archiveFile)
}

tasks.named<Tar>("distTar") { dependsOn(shadowJarTask) }
tasks.named<Zip>("distZip") { dependsOn(shadowJarTask) }
