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

    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Qdrant client
    implementation("io.qdrant:client:1.9.1")
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")

    // ClickHouse JDBC
    implementation("com.clickhouse:clickhouse-jdbc:0.6.5")
    implementation("com.clickhouse:clickhouse-http-client:0.6.5")

    // HTTP client (for embedding service)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("org.datamancy.searchservice.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("search-service")
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
