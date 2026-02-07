plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("maven-publish")
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // HTTP client
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Logging
    implementation(libs.bundles.logging)

    // Testing
    testImplementation(libs.bundles.testing)
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
