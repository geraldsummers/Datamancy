plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    // Trading SDK
    implementation(project(":trading-sdk"))

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor Server
    implementation(libs.bundles.ktor.server)
    implementation("io.ktor:ktor-server-cors:3.0.2")

    // Database
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.47.0")

    // HTTP client for worker communication
    implementation(libs.okhttp)
    implementation(libs.gson)

    // LDAP client
    implementation("com.unboundid:unboundid-ldapsdk:6.0.11")

    // JWT validation
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.auth0:jwks-rsa:0.22.1")

    // Logging
    implementation(libs.bundles.logging)

    // Prometheus metrics
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.2")

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("org.datamancy.txgateway.ApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("tx-gateway")
}

tasks.test {
    useJUnitPlatform()
}
