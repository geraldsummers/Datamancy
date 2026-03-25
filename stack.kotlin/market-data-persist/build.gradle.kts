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
    mainClass.set("org.datamancy.pipeline.persist.MarketDataPersistApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("market-data-persist")
}

tasks.test {
    useJUnitPlatform()
}
