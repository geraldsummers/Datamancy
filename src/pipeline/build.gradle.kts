plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "org.datamancy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HTTP Server (for monitoring endpoints)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Config
    implementation("com.charleskorn.kaml:kaml:0.55.0")

    // RSS Parsing
    implementation("com.rometools:rome:2.1.0")

    // Qdrant
    implementation("io.qdrant:client:1.9.0") {
        exclude(group = "io.grpc")
    }
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("io.grpc:grpc-protobuf:1.63.0")
    implementation("io.grpc:grpc-netty:1.63.0")

    // ClickHouse (for future market data)
    implementation("com.clickhouse:clickhouse-jdbc:0.6.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // BZip2 compression (for Wikipedia dumps)
    implementation("org.apache.commons:commons-compress:1.26.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// Override root project's warnings-as-errors for pipeline module
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        allWarningsAsErrors = false
    }
}

application {
    mainClass.set("org.datamancy.pipeline.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("pipeline")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
