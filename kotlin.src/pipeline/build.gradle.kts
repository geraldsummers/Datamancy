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

    // PostgreSQL (for document staging)
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.47.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // HTML Parsing (for web scraping legislation)
    implementation(libs.jsoup)

    // BZip2 compression (for Wikipedia dumps)
    implementation("org.apache.commons:commons-compress:1.26.0")

    // Parquet (for Open Australian Legal Corpus)
    implementation("org.apache.parquet:parquet-hadoop:1.14.1")
    implementation("org.apache.hadoop:hadoop-client:3.3.6") {
        exclude(group = "org.slf4j")
        exclude(group = "log4j")
        exclude(group = "javax.servlet")
    }
    implementation("org.apache.avro:avro:1.11.3")

    // Tokenization (for accurate token counting)
    implementation("com.knuddels:jtokkit:1.1.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // H2 in-memory database for testing
    testImplementation("com.h2database:h2:2.2.224")


}

tasks.test {
    useJUnitPlatform()

    // Increase heap size for tests to avoid OOM
    maxHeapSize = "2g"

    // Enable detailed test logging
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass.set("org.datamancy.pipeline.MainKt")
}
