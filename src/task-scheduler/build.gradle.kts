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

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("org.datamancy.scheduler.TaskSchedulerKt")
}

kotlin {
    jvmToolchain(21)
}

// Configure Shadow to produce a runnable fat JAR
val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("task-scheduler.jar")
}

tasks.withType<Jar> {
    manifest { attributes["Main-Class"] = "org.datamancy.scheduler.TaskSchedulerKt" }
}

tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(shadowJarTask)
    classpath = files(shadowJarTask.get().archiveFile)
}

tasks.named<Tar>("distTar") { dependsOn(shadowJarTask) }
tasks.named<Zip>("distZip") { dependsOn(shadowJarTask) }
