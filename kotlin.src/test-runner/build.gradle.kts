plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.logging)

    
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    
    implementation(libs.bundles.logging)

    
    implementation(libs.postgres.jdbc)
    implementation(libs.mariadb.jdbc)

    

    
    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
}

tasks.shadowJar {
    mergeServiceFiles()
}

application {
    mainClass.set("org.datamancy.testrunner.MainKt")
}
