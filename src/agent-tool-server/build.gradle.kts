plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    testImplementation(kotlin("test"))
    // JUnit 5 API/engine for kotlin-test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")

    // Shared test commons
    testImplementation(project(":test-commons"))

    // Ktor client for HTTP integration tests
    testImplementation("io.ktor:ktor-client-core:2.3.+")
    testImplementation("io.ktor:ktor-client-cio:2.3.+")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.+")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:2.3.+")

    // Kotlinx serialization for JSON parsing in tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.+")

    // Logging for integration test extension
    testImplementation("io.github.oshai:kotlin-logging-jvm:5.1.+")
    testImplementation("ch.qos.logback:logback-classic:1.4.+")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // SSH client for OpsSshPlugin
    implementation("com.hierynomus:sshj:0.37.0")
    // JDBC drivers for DataSourceQueryPlugin
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.example.MainKt")
}

kotlin {
    jvmToolchain(21)
}