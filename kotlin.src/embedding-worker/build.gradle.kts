plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    id("com.gradleup.shadow")
}

group = "org.datamancy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pipeline-common"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("io.qdrant:client:1.9.0") {
        exclude(group = "io.grpc")
    }
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("io.grpc:grpc-core:1.63.0")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("io.grpc:grpc-protobuf:1.63.0")
    implementation("io.grpc:grpc-netty:1.63.0")
    implementation("io.grpc:grpc-services:1.63.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.datamancy.pipeline.EmbeddingWorkerMainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
