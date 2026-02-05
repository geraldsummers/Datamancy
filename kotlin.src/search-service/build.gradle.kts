plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
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

    // PostgreSQL JDBC for full-text search
    implementation("org.postgresql:postgresql:42.7.1")

    // Connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Exposed ORM (optional, for easier PostgreSQL queries)
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")

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

// Configure JAR naming via application extension (shadow plugin respects this)
tasks.withType(Jar::class) {
    archiveBaseName.set("search-service")
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts
}

// JVM toolchain configured in root build.gradle.kts
