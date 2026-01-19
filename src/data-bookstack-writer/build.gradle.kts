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

    // Logging
    implementation(libs.bundles.logging)

    // Ktor server for API
    implementation(libs.bundles.ktor.server)

    // Tests
    testImplementation(libs.bundles.testing)
}

application {
    mainClass.set("org.datamancy.bookstackwriter.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("data-bookstack-writer")
}

tasks.test {
    // useJUnitPlatform() configured in root build.gradle.kts
}
