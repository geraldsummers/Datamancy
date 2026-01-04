plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Qdrant client
    implementation(libs.qdrant.client)
    implementation(libs.protobuf.java)

    // ClickHouse JDBC
    implementation(libs.bundles.clickhouse)

    // HTTP client (for embedding service)
    implementation(libs.okhttp)

    // JSON
    implementation(libs.gson)

    // Logging
    implementation(libs.bundles.logging)

    // Tests
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass.set("org.datamancy.searchservice.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("search-service")
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts
}

// JVM toolchain configured in root build.gradle.kts
