plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":market-data-ingestion"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.logging)

    testImplementation(libs.bundles.testing)
}

application {
    mainClass.set("org.datamancy.pipeline.sync.MarketDataSyncApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("market-data-sync")
}

tasks.test {
    useJUnitPlatform()
}
