plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    // Test commons (for config and ServicePorts)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Qdrant client (uses shaded gRPC internally to avoid conflicts)
    implementation(libs.qdrant.client)
    implementation(libs.protobuf.java)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    // Note: grpc-netty removed - Qdrant client uses grpc-netty-shaded internally
    implementation(libs.guava)

    // ClickHouse JDBC
    implementation(libs.bundles.clickhouse)

    // Connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")

    // LZ4 compression (for ClickHouse)
    implementation("org.lz4:lz4-java:1.8.0")

    // Apache HTTP Client 5 (for ClickHouse performance)
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3")
    implementation("org.apache.httpcomponents.core5:httpcore5:5.2.4")

    // HTTP client (for embedding service)
    implementation(libs.okhttp)

    // JSON
    implementation(libs.gson)

    // Logging
    implementation(libs.bundles.logging)

    // Tests
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
