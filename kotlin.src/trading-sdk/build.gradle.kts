plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("maven-publish")
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // HTTP client
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Ktor WebSocket client for market data streaming
    implementation(libs.bundles.ktor.client)

    // Database - PostgreSQL/TimescaleDB
    implementation(libs.postgres.jdbc)

    // Logging
    implementation(libs.bundles.logging)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "org.datamancy"
            artifactId = "trading-sdk"
            version = "1.0.0"
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
