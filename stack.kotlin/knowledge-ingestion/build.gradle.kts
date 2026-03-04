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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.rometools:rome:2.1.0")

    implementation("org.jsoup:jsoup:1.17.2")

    implementation("org.apache.commons:commons-compress:1.26.0")

    implementation("org.apache.parquet:parquet-hadoop:1.14.1")
    implementation("org.apache.hadoop:hadoop-client:3.3.6") {
        exclude(group = "org.slf4j")
        exclude(group = "log4j")
        exclude(group = "javax.servlet")
    }
    implementation("org.apache.avro:avro:1.11.3")

    // Ktor server (monitoring)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.datamancy.pipeline.KnowledgeIngestionMainKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
