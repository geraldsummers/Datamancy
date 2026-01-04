plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    // Test commons (for config and ServicePorts)
    implementation(project(":test-commons"))

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // HTTP client
    implementation(libs.okhttp)

    // JSON
    implementation(libs.gson)

    // Qdrant client
    implementation(libs.qdrant.client)

    // ClickHouse JDBC with LZ4 compression support
    implementation(libs.bundles.clickhouse)

    // PostgreSQL JDBC for job state persistence
    implementation(libs.postgres.jdbc)

    // HikariCP for connection pooling
    implementation(libs.hikaricp)

    // Logging
    implementation(libs.bundles.logging)

    // Ktor server for API and SSE (using standardized 2.3.12)
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.sse)

    // Tests
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass.set("org.datamancy.unifiedindexer.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("unified-indexer")
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts
}

// JVM toolchain configured in root build.gradle.kts
