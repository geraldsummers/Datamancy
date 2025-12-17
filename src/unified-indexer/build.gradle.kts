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
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Qdrant client
    implementation("io.qdrant:client:1.9.1")
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")

    // ClickHouse JDBC
    implementation("com.clickhouse:clickhouse-jdbc:0.6.5")
    implementation("com.clickhouse:clickhouse-http-client:0.6.5")

    // PostgreSQL JDBC for job state persistence
    implementation("org.postgresql:postgresql:42.7.1")

    // HikariCP for connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // Ktor server for API and SSE
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-html-builder:2.3.12")
    implementation("io.ktor:ktor-server-sse:2.3.12")
}

application {
    mainClass.set("org.datamancy.unifiedindexer.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("unified-indexer")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

kotlin {
    jvmToolchain(21)
}
