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

    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
}

application {
    mainClass.set("org.datamancy.alphaexecutionagent.ApplicationKt")
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.withType(Jar::class) {
    archiveBaseName.set("alpha-execution-agent")
}

tasks.test {
    useJUnitPlatform()
}
