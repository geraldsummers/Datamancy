plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":trading-sdk"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.gson)
    implementation(libs.bundles.logging)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)
    implementation(libs.commons.compress)

    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
}

application {
    mainClass.set("org.datamancy.marketdataarchiveimporter.ApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("market-data-archive-importer")
}

tasks.test {
    useJUnitPlatform()
}
