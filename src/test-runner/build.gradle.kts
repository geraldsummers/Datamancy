plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    // Ktor client (v3.0.0)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.logging)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // Logging
    implementation(libs.bundles.logging)

    // Database drivers for direct DB tests
    implementation(libs.postgres.jdbc)
    implementation(libs.bundles.clickhouse)

    // Shared test commons (for @IntegrationTest if needed)

    // Unit testing dependencies
    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
}

application {
    mainClass.set("org.datamancy.testrunner.MainKt")
}
