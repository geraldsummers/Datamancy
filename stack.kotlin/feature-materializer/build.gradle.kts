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
    mainClass.set("org.datamancy.pipeline.materializer.FeatureMaterializerApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("feature-materializer")
}

tasks.test {
    useJUnitPlatform()
}
