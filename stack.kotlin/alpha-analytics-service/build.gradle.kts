plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":trading-sdk"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.server)
    implementation(libs.gson)
    implementation(libs.bundles.logging)
    implementation(libs.postgres.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
}

application {
    mainClass.set("org.datamancy.alphaanalytics.ApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("alpha-analytics-service")
}

tasks.test {
    useJUnitPlatform()
}
