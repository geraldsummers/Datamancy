plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

dependencies {
    // Jackson
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)

    // SSH client for OpsSshPlugin
    implementation(libs.sshj)

    // JDBC drivers for DataSourceQueryPlugin
    implementation(libs.postgres.jdbc)
    implementation(libs.mariadb.jdbc)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // SQL parser for query validation
    implementation("com.github.jsqlparser:jsqlparser:4.9")

    // Tests
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    // Ktor client for HTTP integration tests
    testImplementation(libs.bundles.ktor.client)

    // Kotlinx serialization for JSON parsing in tests
    testImplementation(libs.kotlinx.serialization.json)

    // Logging for integration test extension
    testImplementation(libs.bundles.logging)
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts
}

application {
    mainClass.set("org.example.MainKt")
}

// JVM toolchain configured in root build.gradle.kts